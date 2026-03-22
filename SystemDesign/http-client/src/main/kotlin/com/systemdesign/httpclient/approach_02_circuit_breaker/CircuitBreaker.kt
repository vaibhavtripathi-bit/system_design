/**
 * # Approach 02: Circuit Breaker Pattern
 *
 * ## Pattern Used
 * State machine (CLOSED -> OPEN -> HALF_OPEN) to prevent cascading failures.
 *
 * ## Trade-offs
 * - **Pros:** Prevents cascade failures, fast-fail, self-healing
 * - **Cons:** More complex, needs tuning, may reject valid requests during recovery
 *
 * ## When to Prefer
 * - Calling unreliable external services
 * - Microservices architecture
 */
package com.systemdesign.httpclient.approach_02_circuit_breaker

import com.systemdesign.httpclient.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val successThreshold: Int = 3,
    val openDurationMs: Long = 30000,
    val halfOpenMaxCalls: Int = 3
)

class CircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val clock: Clock = SystemClock
) : ResiliencePolicy {

    private val state = AtomicReference(CircuitState.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val halfOpenCalls = AtomicInteger(0)
    private val openedAt = AtomicLong(0)
    private val mutex = Mutex()

    fun getState(): CircuitState = state.get()

    override suspend fun <T> execute(block: suspend () -> T): T {
        return when (state.get()) {
            CircuitState.OPEN -> handleOpenState(block)
            CircuitState.HALF_OPEN -> handleHalfOpenState(block)
            CircuitState.CLOSED -> handleClosedState(block)
        }
    }

    private suspend fun <T> handleOpenState(block: suspend () -> T): T {
        if (shouldTransitionToHalfOpen()) {
            mutex.withLock {
                if (state.get() == CircuitState.OPEN && shouldTransitionToHalfOpen()) {
                    transitionTo(CircuitState.HALF_OPEN)
                }
            }
            return handleHalfOpenState(block)
        }
        throw CircuitOpenException("Circuit is OPEN")
    }

    private fun shouldTransitionToHalfOpen(): Boolean {
        return clock.now() - openedAt.get() >= config.openDurationMs
    }

    private suspend fun <T> handleHalfOpenState(block: suspend () -> T): T {
        if (halfOpenCalls.incrementAndGet() > config.halfOpenMaxCalls) {
            throw CircuitOpenException("Circuit is HALF_OPEN, max calls exceeded")
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    private suspend fun <T> handleClosedState(block: suspend () -> T): T {
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    private suspend fun onSuccess() {
        when (state.get()) {
            CircuitState.HALF_OPEN -> {
                if (successCount.incrementAndGet() >= config.successThreshold) {
                    mutex.withLock {
                        if (state.get() == CircuitState.HALF_OPEN) {
                            transitionTo(CircuitState.CLOSED)
                        }
                    }
                }
            }
            CircuitState.CLOSED -> {
                failureCount.set(0)
            }
            else -> {}
        }
    }

    private suspend fun onFailure() {
        when (state.get()) {
            CircuitState.HALF_OPEN -> {
                mutex.withLock {
                    if (state.get() == CircuitState.HALF_OPEN) {
                        transitionTo(CircuitState.OPEN)
                    }
                }
            }
            CircuitState.CLOSED -> {
                if (failureCount.incrementAndGet() >= config.failureThreshold) {
                    mutex.withLock {
                        if (state.get() == CircuitState.CLOSED && 
                            failureCount.get() >= config.failureThreshold) {
                            transitionTo(CircuitState.OPEN)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun transitionTo(newState: CircuitState) {
        state.set(newState)
        when (newState) {
            CircuitState.OPEN -> {
                openedAt.set(clock.now())
                halfOpenCalls.set(0)
                successCount.set(0)
            }
            CircuitState.HALF_OPEN -> {
                halfOpenCalls.set(0)
                successCount.set(0)
            }
            CircuitState.CLOSED -> {
                failureCount.set(0)
                successCount.set(0)
            }
        }
    }

    fun reset() {
        state.set(CircuitState.CLOSED)
        failureCount.set(0)
        successCount.set(0)
        halfOpenCalls.set(0)
    }
}

class CircuitOpenException(message: String) : Exception(message)

class CircuitBreakingHttpClient(
    private val engine: HttpEngine,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker()
) {
    suspend fun execute(request: HttpRequest): HttpResult {
        return try {
            val response = circuitBreaker.execute { engine.execute(request) }
            HttpResult.Success(response)
        } catch (e: CircuitOpenException) {
            HttpResult.Failure(e)
        } catch (e: Exception) {
            HttpResult.Failure(e)
        }
    }

    fun getCircuitState(): CircuitState = circuitBreaker.getState()
}
