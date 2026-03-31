/**
 * # Approach 02: Time-Window Based Circuit Breaker
 *
 * ## Pattern Used
 * Sliding time-window circuit breaker that calculates failure *rate* over a
 * configurable window. The circuit opens when the failure rate exceeds a
 * threshold within the window, rather than relying on consecutive failure counts.
 *
 * ## How It Works
 * 1. Every call outcome (success / failure) is recorded with a timestamp
 * 2. Only outcomes within the sliding window are considered
 * 3. When the failure rate (failures / total) exceeds the threshold AND a
 *    minimum number of calls have been made, the circuit trips to OPEN
 * 4. After the timeout, the circuit moves to HALF_OPEN for trial calls
 * 5. Trial successes close the circuit; a failure re-opens it
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Failure *rate* is more meaningful than raw count
 *   - Resilient to intermittent single failures — they don't reset a counter
 *   - Stale outcomes naturally expire out of the window
 *
 * - **Cons:**
 *   - Higher memory usage — must store per-call timestamps
 *   - Slightly more complex to reason about than count-based
 *   - Requires a minimum call volume to avoid false positives
 *
 * ## When to Prefer This Approach
 * - When the downstream service has a variable but non-zero baseline error rate
 * - When you care about failure *rate* rather than absolute failure count
 * - When call volume is high enough that a sliding window is meaningful
 *
 * ## Comparison with Other Approaches
 * - **vs Count-Based (Approach 01):** More nuanced; a single success doesn't mask a bad trend
 * - **vs Adaptive (Approach 03):** Simpler — fixed thresholds, no dynamic tuning
 */
package com.systemdesign.circuitbreaker.approach_02_time_based

import com.systemdesign.circuitbreaker.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TimeBasedConfig(
    val failureRateThreshold: Double = 0.5,
    val windowSizeMs: Long = 60_000,
    val minimumCallsInWindow: Int = 10,
    val successThreshold: Int = 2,
    val timeoutMs: Long = 30_000,
    val halfOpenMaxCalls: Int = 1
) {
    init {
        require(failureRateThreshold in 0.0..1.0) { "failureRateThreshold must be in [0.0, 1.0]" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
        require(minimumCallsInWindow > 0) { "minimumCallsInWindow must be positive" }
        require(successThreshold > 0) { "successThreshold must be positive" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(halfOpenMaxCalls > 0) { "halfOpenMaxCalls must be positive" }
    }
}

private data class CallRecord(val timestamp: Long, val success: Boolean)

class TimeBasedCircuitBreaker(
    private val config: TimeBasedConfig = TimeBasedConfig(),
    private val timeSource: TimeSource = SystemTimeSource,
    private val observer: CircuitBreakerObserver = NoOpObserver()
) : CircuitBreaker {

    private val lock = ReentrantLock()
    private val mutex = Mutex()

    private val currentState = AtomicReference(CircuitState.CLOSED)
    private val records = ConcurrentLinkedDeque<CallRecord>()

    private var consecutiveSuccesses = 0
    private var halfOpenCallsInFlight = 0

    private val totalRequests = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    private val totalSuccesses = AtomicLong(0)
    private var lastFailureTime: Long? = null
    private var lastTransitionTime: Long = timeSource.currentTimeMillis()

    override val state: CircuitState
        get() = lock.withLock { evaluateState() }

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
        consecutiveSuccesses = 0
        halfOpenCallsInFlight = 0
    }

    override fun getMetrics(): CircuitBreakerMetrics = lock.withLock {
        pruneOldRecords()
        val failures = records.count { !it.success }
        val successes = records.count { it.success }
        CircuitBreakerMetrics(
            state = evaluateState(),
            failureCount = failures,
            successCount = successes,
            totalRequests = totalRequests.get(),
            totalFailures = totalFailures.get(),
            totalSuccesses = totalSuccesses.get(),
            lastFailureTime = lastFailureTime,
            lastStateTransitionTime = lastTransitionTime
        )
    }

    fun currentFailureRate(): Double = lock.withLock {
        pruneOldRecords()
        val total = records.size
        if (total == 0) return@withLock 0.0
        records.count { !it.success }.toDouble() / total
    }

    private fun evaluateState(): CircuitState {
        val current = currentState.get()
        if (current == CircuitState.OPEN) {
            val elapsed = timeSource.currentTimeMillis() - lastTransitionTime
            if (elapsed >= config.timeoutMs) {
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
                if (halfOpenCallsInFlight >= config.halfOpenMaxCalls) {
                    observer.onCallRejected()
                    throw CircuitBreakerOpenException("Circuit breaker is HALF_OPEN and at max trial calls")
                }
                halfOpenCallsInFlight++
            }
        }
    }

    private fun onSuccess(timestamp: Long) {
        totalSuccesses.incrementAndGet()
        when (currentState.get()) {
            CircuitState.CLOSED -> {
                records.addLast(CallRecord(timestamp, success = true))
                pruneOldRecords()
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
                consecutiveSuccesses++
                if (consecutiveSuccesses >= config.successThreshold) {
                    transitionTo(CircuitState.CLOSED)
                    records.clear()
                }
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun onFailure(timestamp: Long) {
        totalFailures.incrementAndGet()
        lastFailureTime = timestamp
        when (currentState.get()) {
            CircuitState.CLOSED -> {
                records.addLast(CallRecord(timestamp, success = false))
                pruneOldRecords()
                evaluateFailureRate()
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
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
        if (rate >= config.failureRateThreshold) {
            transitionTo(CircuitState.OPEN)
        }
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
            consecutiveSuccesses = 0
            halfOpenCallsInFlight = 0
            observer.onStateChange(old, newState)
        }
    }
}
