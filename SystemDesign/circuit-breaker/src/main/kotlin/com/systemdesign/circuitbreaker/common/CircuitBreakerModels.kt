/**
 * Common interfaces, enums, and data classes for all Circuit Breaker implementations.
 */
package com.systemdesign.circuitbreaker.common

enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

/**
 * @param failureThreshold consecutive failures (count-based) or failure rate (time-based) to trip open
 * @param successThreshold consecutive successes in HALF_OPEN to transition back to CLOSED
 * @param timeoutMs how long the circuit stays OPEN before transitioning to HALF_OPEN
 * @param halfOpenMaxCalls max concurrent trial calls allowed in HALF_OPEN state
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val successThreshold: Int = 2,
    val timeoutMs: Long = 30_000,
    val halfOpenMaxCalls: Int = 1
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(successThreshold > 0) { "successThreshold must be positive" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(halfOpenMaxCalls > 0) { "halfOpenMaxCalls must be positive" }
    }
}

data class CircuitBreakerMetrics(
    val state: CircuitState,
    val failureCount: Int,
    val successCount: Int,
    val totalRequests: Long,
    val totalFailures: Long,
    val totalSuccesses: Long,
    val lastFailureTime: Long?,
    val lastStateTransitionTime: Long
)

interface CircuitBreakerObserver {
    fun onStateChange(from: CircuitState, to: CircuitState)
    fun onCallSuccess(durationMs: Long)
    fun onCallFailure(durationMs: Long, throwable: Throwable)
    fun onCallRejected()
}

open class NoOpObserver : CircuitBreakerObserver {
    override fun onStateChange(from: CircuitState, to: CircuitState) {}
    override fun onCallSuccess(durationMs: Long) {}
    override fun onCallFailure(durationMs: Long, throwable: Throwable) {}
    override fun onCallRejected() {}
}

class CircuitBreakerOpenException(
    message: String = "Circuit breaker is OPEN"
) : RuntimeException(message)

interface CircuitBreaker {
    val state: CircuitState
    fun <T> execute(block: () -> T): T
    suspend fun <T> executeSuspend(block: suspend () -> T): T
    fun reset()
    fun getMetrics(): CircuitBreakerMetrics
}

/**
 * Time source abstraction for testability.
 */
interface TimeSource {
    fun currentTimeMillis(): Long
}

object SystemTimeSource : TimeSource {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

class FakeTimeSource(private var currentTime: Long = 0L) : TimeSource {
    override fun currentTimeMillis(): Long = currentTime

    fun advanceTime(millis: Long) {
        currentTime += millis
    }

    fun setTime(millis: Long) {
        currentTime = millis
    }
}
