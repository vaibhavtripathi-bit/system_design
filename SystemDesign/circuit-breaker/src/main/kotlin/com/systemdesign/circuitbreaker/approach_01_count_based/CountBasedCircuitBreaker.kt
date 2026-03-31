/**
 * # Approach 01: Count-Based Circuit Breaker
 *
 * ## Pattern Used
 * Classic circuit breaker that tracks consecutive failure counts.
 * Transitions CLOSED → OPEN after N consecutive failures.
 * Transitions to HALF_OPEN after a timeout period.
 * Transitions HALF_OPEN → CLOSED after M consecutive successes, or back to OPEN on any failure.
 *
 * ## How It Works
 * 1. In CLOSED state, all calls pass through; consecutive failures are counted
 * 2. When consecutive failures reach the threshold, circuit trips to OPEN
 * 3. In OPEN state, all calls are immediately rejected with CircuitBreakerOpenException
 * 4. After the timeout elapses, circuit transitions to HALF_OPEN
 * 5. In HALF_OPEN, a limited number of trial calls are permitted
 * 6. If trial calls succeed consecutively, circuit closes; any failure re-opens it
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Simple mental model — easy to reason about and debug
 *   - Low overhead — only an integer counter, no sliding windows
 *   - Deterministic transitions — N failures always trips the breaker
 *
 * - **Cons:**
 *   - A single success resets the counter, masking a high failure rate
 *   - No notion of failure *rate* — 5 failures in 1 second and 5 failures in 1 hour are treated the same
 *   - Abrupt transitions — no gradual traffic ramp-up when recovering
 *
 * ## When to Prefer This Approach
 * - When simplicity and predictability are more important than precision
 * - For protecting calls to a single downstream dependency
 * - When failure bursts are the primary concern (not sustained low-rate failures)
 *
 * ## Comparison with Other Approaches
 * - **vs Time-Based (Approach 02):** Time-based uses failure rate over a window, more nuanced
 * - **vs Adaptive (Approach 03):** Adaptive dynamically tunes thresholds; count-based is static
 */
package com.systemdesign.circuitbreaker.approach_01_count_based

import com.systemdesign.circuitbreaker.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CountBasedCircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val timeSource: TimeSource = SystemTimeSource,
    private val observer: CircuitBreakerObserver = NoOpObserver()
) : CircuitBreaker {

    private val lock = ReentrantLock()
    private val mutex = Mutex()

    private var currentState = AtomicReference(CircuitState.CLOSED)
    private var consecutiveFailures = 0
    private var consecutiveSuccesses = 0
    private var halfOpenCallsInFlight = 0

    private var totalRequests = AtomicLong(0)
    private var totalFailures = AtomicLong(0)
    private var totalSuccesses = AtomicLong(0)
    private var lastFailureTime: Long? = null
    private var lastTransitionTime: Long = timeSource.currentTimeMillis()

    override val state: CircuitState
        get() = lock.withLock { evaluateState() }

    override fun <T> execute(block: () -> T): T {
        val callState = lock.withLock { acquirePermission() }
        totalRequests.incrementAndGet()

        val start = timeSource.currentTimeMillis()
        return try {
            val result = block()
            val duration = timeSource.currentTimeMillis() - start
            lock.withLock { onSuccess() }
            observer.onCallSuccess(duration)
            result
        } catch (e: Throwable) {
            val duration = timeSource.currentTimeMillis() - start
            lock.withLock { onFailure() }
            observer.onCallFailure(duration, e)
            throw e
        }
    }

    override suspend fun <T> executeSuspend(block: suspend () -> T): T {
        val callState = mutex.withLock { acquirePermission() }
        totalRequests.incrementAndGet()

        val start = timeSource.currentTimeMillis()
        return try {
            val result = block()
            val duration = timeSource.currentTimeMillis() - start
            mutex.withLock { onSuccess() }
            observer.onCallSuccess(duration)
            result
        } catch (e: Throwable) {
            val duration = timeSource.currentTimeMillis() - start
            mutex.withLock { onFailure() }
            observer.onCallFailure(duration, e)
            throw e
        }
    }

    override fun reset() = lock.withLock {
        transitionTo(CircuitState.CLOSED)
        consecutiveFailures = 0
        consecutiveSuccesses = 0
        halfOpenCallsInFlight = 0
    }

    override fun getMetrics(): CircuitBreakerMetrics = lock.withLock {
        CircuitBreakerMetrics(
            state = evaluateState(),
            failureCount = consecutiveFailures,
            successCount = consecutiveSuccesses,
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
            if (elapsed >= config.timeoutMs) {
                transitionTo(CircuitState.HALF_OPEN)
                return CircuitState.HALF_OPEN
            }
        }
        return current
    }

    private fun acquirePermission(): CircuitState {
        val effective = evaluateState()
        return when (effective) {
            CircuitState.CLOSED -> effective
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
                effective
            }
        }
    }

    private fun onSuccess() {
        totalSuccesses.incrementAndGet()
        when (currentState.get()) {
            CircuitState.CLOSED -> {
                consecutiveFailures = 0
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
                consecutiveSuccesses++
                if (consecutiveSuccesses >= config.successThreshold) {
                    transitionTo(CircuitState.CLOSED)
                }
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun onFailure() {
        totalFailures.incrementAndGet()
        lastFailureTime = timeSource.currentTimeMillis()
        when (currentState.get()) {
            CircuitState.CLOSED -> {
                consecutiveFailures++
                consecutiveSuccesses = 0
                if (consecutiveFailures >= config.failureThreshold) {
                    transitionTo(CircuitState.OPEN)
                }
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCallsInFlight = (halfOpenCallsInFlight - 1).coerceAtLeast(0)
                transitionTo(CircuitState.OPEN)
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun transitionTo(newState: CircuitState) {
        val old = currentState.getAndSet(newState)
        if (old != newState) {
            lastTransitionTime = timeSource.currentTimeMillis()
            consecutiveFailures = 0
            consecutiveSuccesses = 0
            halfOpenCallsInFlight = 0
            observer.onStateChange(old, newState)
        }
    }
}
