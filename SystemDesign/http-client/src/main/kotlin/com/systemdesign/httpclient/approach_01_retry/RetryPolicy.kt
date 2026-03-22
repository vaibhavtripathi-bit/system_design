/**
 * # Approach 01: Retry with Exponential Backoff
 *
 * ## Pattern Used
 * Simple retry mechanism with configurable backoff strategy.
 *
 * ## Trade-offs
 * - **Pros:** Simple, effective for transient failures
 * - **Cons:** Can overload failing services, no circuit breaking
 *
 * ## When to Prefer
 * - Transient network issues expected
 * - Simple resilience requirements
 */
package com.systemdesign.httpclient.approach_01_retry

import com.systemdesign.httpclient.common.*
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 100,
    val maxDelayMs: Long = 10000,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.1,
    val retryableStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    val retryableExceptions: Set<Class<out Throwable>> = setOf(
        java.io.IOException::class.java,
        java.net.SocketTimeoutException::class.java
    )
)

class RetryPolicy(
    private val config: RetryConfig = RetryConfig()
) : ResiliencePolicy {

    override suspend fun <T> execute(block: suspend () -> T): T {
        var lastException: Throwable? = null
        
        repeat(config.maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                if (!shouldRetry(e) || attempt >= config.maxRetries) {
                    throw e
                }
                val delayMs = calculateDelay(attempt)
                delay(delayMs)
            }
        }
        
        throw lastException ?: RuntimeException("Retry failed")
    }

    private fun shouldRetry(error: Throwable): Boolean {
        return config.retryableExceptions.any { it.isInstance(error) }
    }

    private fun calculateDelay(attempt: Int): Long {
        val baseDelay = (config.initialDelayMs * config.multiplier.pow(attempt.toDouble())).toLong()
        val cappedDelay = min(baseDelay, config.maxDelayMs)
        val jitter = (cappedDelay * config.jitterFactor * Random.nextDouble()).toLong()
        return cappedDelay + jitter
    }
}

class RetryingHttpClient(
    private val engine: HttpEngine,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    suspend fun execute(request: HttpRequest): HttpResult {
        return try {
            val response = retryPolicy.execute { engine.execute(request) }
            HttpResult.Success(response)
        } catch (e: Exception) {
            HttpResult.Failure(e)
        }
    }
}

class RetryableHttpException(
    val statusCode: Int,
    message: String
) : Exception(message)
