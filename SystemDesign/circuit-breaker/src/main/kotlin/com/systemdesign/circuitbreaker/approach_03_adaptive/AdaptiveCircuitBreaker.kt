/**
 * # Approach 03: Adaptive Circuit Breaker
 *
 * ## Pattern Used
 * Adaptive circuit breaker that dynamically adjusts thresholds based on
 * historical performance. Uses exponential backoff for retry delays and
 * gradual traffic ramp-up in the HALF_OPEN state instead of a fixed number
 * of trial calls.
 *
 * ## How It Works
 * 1. Maintains an exponentially weighted moving average (EWMA) of failure rate
 * 2. The effective failure threshold tightens after repeated trips and relaxes
 *    during sustained healthy periods
 * 3. When the circuit opens, the timeout increases with exponential backoff
 *    (capped at a configurable maximum)
 * 4. In HALF_OPEN, traffic is ramped up gradually: the allowed concurrency
 *    doubles with each consecutive success batch until fully closed
 * 5. On full recovery the backoff multiplier decays, rewarding stability
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Self-tuning — adapts to changing downstream health without config changes
 *   - Exponential backoff prevents hammering a struggling service
 *   - Gradual ramp-up reduces thundering-herd risk on recovery
 *   - EWMA smooths out transient spikes
 *
 * - **Cons:**
 *   - More complex to understand, configure, and debug
 *   - Behaviour is non-deterministic from the caller's perspective
 *   - More state to persist if circuit breaker state must survive restarts
 *   - Requires careful tuning of decay/growth factors
 *
 * ## When to Prefer This Approach
 * - When the downstream service has variable latency / error characteristics
 * - In large-scale systems where manual threshold tuning is impractical
 * - When you want to avoid thundering-herd on recovery
 * - When the cost of a false-open is high (adaptive threshold avoids premature trips)
 *
 * ## Comparison with Other Approaches
 * - **vs Count-Based (Approach 01):** Far more nuanced; count-based is simpler but static
 * - **vs Time-Based (Approach 02):** Shares the sliding-window idea but adds dynamic tuning
 */
package com.systemdesign.circuitbreaker.approach_03_adaptive

import com.systemdesign.circuitbreaker.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * @param baseFailureRateThreshold starting failure rate threshold (0.0–1.0)
 * @param windowSizeMs sliding window for failure rate calculation
 * @param minimumCallsInWindow minimum calls before failure rate is evaluated
 * @param baseTimeoutMs initial OPEN → HALF_OPEN timeout
 * @param maxTimeoutMs upper bound for exponential backoff timeout
 * @param backoffMultiplier multiplier applied to timeout on each consecutive trip
 * @param backoffDecayFactor multiplier applied to the backoff exponent on recovery (< 1.0 decays)
 * @param ewmaAlpha smoothing factor for the EWMA failure rate (higher = more reactive)
 * @param halfOpenInitialPermits starting concurrency in HALF_OPEN
 * @param halfOpenMaxPermits cap on concurrency ramp-up in HALF_OPEN
 * @param successBatchForRampUp consecutive successes needed to double concurrency
 */
data class AdaptiveConfig(
    val baseFailureRateThreshold: Double = 0.5,
    val windowSizeMs: Long = 60_000,
    val minimumCallsInWindow: Int = 10,
    val baseTimeoutMs: Long = 30_000,
    val maxTimeoutMs: Long = 300_000,
    val backoffMultiplier: Double = 2.0,
    val backoffDecayFactor: Double = 0.5,
    val ewmaAlpha: Double = 0.3,
    val halfOpenInitialPermits: Int = 1,
    val halfOpenMaxPermits: Int = 16,
    val successBatchForRampUp: Int = 3
) {
    init {
        require(baseFailureRateThreshold in 0.0..1.0) { "baseFailureRateThreshold must be in [0.0, 1.0]" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
        require(minimumCallsInWindow > 0) { "minimumCallsInWindow must be positive" }
        require(baseTimeoutMs > 0) { "baseTimeoutMs must be positive" }
        require(maxTimeoutMs >= baseTimeoutMs) { "maxTimeoutMs must be >= baseTimeoutMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(backoffDecayFactor in 0.0..1.0) { "backoffDecayFactor must be in [0.0, 1.0]" }
        require(ewmaAlpha in 0.0..1.0) { "ewmaAlpha must be in (0.0, 1.0]" }
        require(halfOpenInitialPermits > 0) { "halfOpenInitialPermits must be positive" }
        require(halfOpenMaxPermits >= halfOpenInitialPermits) { "halfOpenMaxPermits must be >= halfOpenInitialPermits" }
        require(successBatchForRampUp > 0) { "successBatchForRampUp must be positive" }
    }
}

private data class CallRecord(val timestamp: Long, val success: Boolean)

class AdaptiveCircuitBreaker(
    private val config: AdaptiveConfig = AdaptiveConfig(),
    private val timeSource: TimeSource = SystemTimeSource,
    private val observer: CircuitBreakerObserver = NoOpObserver()
) : CircuitBreaker {

    private val lock = ReentrantLock()
    private val mutex = Mutex()

    private val currentState = AtomicReference(CircuitState.CLOSED)
    private val records = ConcurrentLinkedDeque<CallRecord>()

    private var ewmaFailureRate: Double = 0.0
    private var tripCount: Int = 0
    private var consecutiveSuccessesInHalfOpen: Int = 0
    private var halfOpenPermits: Int = config.halfOpenInitialPermits
    private var halfOpenCallsInFlight: Int = 0

    private val totalRequests = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    private val totalSuccesses = AtomicLong(0)
    private var lastFailureTime: Long? = null
    private var lastTransitionTime: Long = timeSource.currentTimeMillis()

    override val state: CircuitState
        get() = lock.withLock { evaluateState() }

    val effectiveTimeoutMs: Long
        get() = lock.withLock { computeTimeout() }

    val effectiveFailureThreshold: Double
        get() = lock.withLock { computeEffectiveThreshold() }

    val smoothedFailureRate: Double
        get() = lock.withLock { ewmaFailureRate }

    override fun <T> execute(block: () -> T): T {
        lock.withLock { acquirePermission() }
        totalRequests.incrementAndGet()

        val start = timeSource.currentTimeMillis()
        return try {
            val result = block()
            val duration = timeSource.currentTimeMillis() - start
            lock.withLock { onSuccess(start) }
            observer.onCallSuccess(duration)
            result
        } catch (e: Throwable) {
            val duration = timeSource.currentTimeMillis() - start
            lock.withLock { onFailure(start) }
            observer.onCallFailure(duration, e)
            throw e
        }
    }

    override suspend fun <T> executeSuspend(block: suspend () -> T): T {
        mutex.withLock { acquirePermission() }
        totalRequests.incrementAndGet()

        val start = timeSource.currentTimeMillis()
        return try {
            val result = block()
            val duration = timeSource.currentTimeMillis() - start
            mutex.withLock { onSuccess(start) }
            observer.onCallSuccess(duration)
            result
        } catch (e: Throwable) {
            val duration = timeSource.currentTimeMillis() - start
            mutex.withLock { onFailure(start) }
            observer.onCallFailure(duration, e)
            throw e
        }
    }

    override fun reset() = lock.withLock {
        transitionTo(CircuitState.CLOSED)
        records.clear()
        ewmaFailureRate = 0.0
        tripCount = 0
        resetHalfOpenState()
    }

    override fun getMetrics(): CircuitBreakerMetrics = lock.withLock {
        pruneOldRecords()
        CircuitBreakerMetrics(
            state = evaluateState(),
            failureCount = records.count { !it.success },
            successCount = records.count { it.success },
            totalRequests = totalRequests.get(),
            totalFailures = totalFailures.get(),
            totalSuccesses = totalSuccesses.get(),
            lastFailureTime = lastFailureTime,
            lastStateTransitionTime = lastTransitionTime
        )
    }

    private fun evaluateState(): CircuitState {
        val current = currentState.get()
        if (current == CircuitState.OPEN) {
            val elapsed = timeSource.currentTimeMillis() - lastTransitionTime
            if (elapsed >= computeTimeout()) {
                transitionTo(CircuitState.HALF_OPEN)
                return CircuitState.HALF_OPEN
            }
        }
        return current
    }

    private fun acquirePermission() {
        val effective = evaluateState()
        when (effective) {
            CircuitState.CLOSED -> {}
            CircuitState.OPEN -> {
                observer.onCallRejected()
                throw CircuitBreakerOpenException()
            }
            CircuitState.HALF_OPEN -> {
                if (halfOpenCallsInFlight >= halfOpenPermits) {
                    observer.onCallRejected()
                    throw CircuitBreakerOpenException("Circuit breaker is HALF_OPEN and at max trial calls")
                }
                halfOpenCallsInFlight++
            }
        }
    }

    private fun onSuccess(timestamp: Long) {
        totalSuccesses.incrementAndGet()
        updateEwma(success = true)

        when (currentState.get()) {
            CircuitState.CLOSED -> {
                records.addLast(CallRecord(timestamp, success = true))
                pruneOldRecords()
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
                consecutiveSuccessesInHalfOpen++

                if (consecutiveSuccessesInHalfOpen >= config.successBatchForRampUp) {
                    if (halfOpenPermits >= config.halfOpenMaxPermits) {
                        tripCount = max(0, tripCount - 1)
                        transitionTo(CircuitState.CLOSED)
                        records.clear()
                    } else {
                        halfOpenPermits = min(halfOpenPermits * 2, config.halfOpenMaxPermits)
                        consecutiveSuccessesInHalfOpen = 0
                    }
                }
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun onFailure(timestamp: Long) {
        totalFailures.incrementAndGet()
        lastFailureTime = timestamp
        updateEwma(success = false)

        when (currentState.get()) {
            CircuitState.CLOSED -> {
                records.addLast(CallRecord(timestamp, success = false))
                pruneOldRecords()
                evaluateFailureRate()
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
                tripCount++
                transitionTo(CircuitState.OPEN)
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun evaluateFailureRate() {
        val total = records.size
        if (total < config.minimumCallsInWindow) return

        val failures = records.count { !it.success }
        val rate = failures.toDouble() / total
        if (rate >= computeEffectiveThreshold()) {
            tripCount++
            transitionTo(CircuitState.OPEN)
        }
    }

    private fun updateEwma(success: Boolean) {
        val sample = if (success) 0.0 else 1.0
        ewmaFailureRate = config.ewmaAlpha * sample + (1 - config.ewmaAlpha) * ewmaFailureRate
    }

    private fun computeEffectiveThreshold(): Double {
        val tightening = config.backoffDecayFactor.pow(tripCount.toDouble())
        return (config.baseFailureRateThreshold * tightening).coerceIn(0.05, config.baseFailureRateThreshold)
    }

    private fun computeTimeout(): Long {
        val timeout = config.baseTimeoutMs * config.backoffMultiplier.pow(tripCount.toDouble())
        return min(timeout.toLong(), config.maxTimeoutMs)
    }

    private fun resetHalfOpenState() {
        consecutiveSuccessesInHalfOpen = 0
        halfOpenPermits = config.halfOpenInitialPermits
        halfOpenCallsInFlight = 0
    }

    private fun pruneOldRecords() {
        val cutoff = timeSource.currentTimeMillis() - config.windowSizeMs
        while (records.peekFirst()?.let { it.timestamp < cutoff } == true) {
            records.pollFirst()
        }
    }

    private fun transitionTo(newState: CircuitState) {
        val old = currentState.getAndSet(newState)
        if (old != newState) {
            lastTransitionTime = timeSource.currentTimeMillis()
            resetHalfOpenState()
            observer.onStateChange(old, newState)
        }
    }
}
