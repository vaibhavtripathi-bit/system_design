/**
 * Common interface and data classes for all Rate Limiter implementations.
 */
package com.systemdesign.ratelimiter.common

/**
 * Rate limiter interface defining the contract for all implementations.
 * 
 * Rate limiters control the rate of requests to protect systems from overload.
 * Different algorithms offer different trade-offs in terms of:
 * - Burst handling
 * - Memory usage
 * - Fairness
 * - Precision
 */
interface RateLimiter {
    /**
     * Attempts to acquire a permit.
     * @return true if the request is allowed, false if rate limited
     */
    fun tryAcquire(): Boolean
    
    /**
     * Attempts to acquire multiple permits.
     * @param permits the number of permits to acquire
     * @return true if all permits are acquired, false if rate limited
     */
    fun tryAcquire(permits: Int): Boolean
    
    /**
     * Returns the current state of the rate limiter.
     */
    fun getState(): RateLimiterState
}

/**
 * Suspending rate limiter for coroutine-based applications.
 */
interface SuspendingRateLimiter {
    /**
     * Acquires a permit, suspending if necessary until available.
     */
    suspend fun acquire()
    
    /**
     * Attempts to acquire a permit without blocking.
     * @return true if acquired, false if rate limited
     */
    suspend fun tryAcquire(): Boolean
}

/**
 * State information for rate limiter monitoring.
 */
data class RateLimiterState(
    val availablePermits: Double,
    val maxPermits: Double,
    val windowStartTime: Long = 0,
    val requestsInWindow: Long = 0
)

/**
 * Configuration for rate limiters.
 */
data class RateLimiterConfig(
    val permitsPerSecond: Double,
    val maxBurstSize: Int = 1,
    val warmupPeriodMs: Long = 0
) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive" }
        require(maxBurstSize >= 1) { "maxBurstSize must be at least 1" }
        require(warmupPeriodMs >= 0) { "warmupPeriodMs cannot be negative" }
    }
}

/**
 * Time source interface for testability.
 */
interface TimeSource {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
}

/**
 * Default time source using system clock.
 */
object SystemTimeSource : TimeSource {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * Fake time source for testing.
 */
class FakeTimeSource(private var currentTime: Long = 0L) : TimeSource {
    override fun currentTimeMillis(): Long = currentTime
    override fun nanoTime(): Long = currentTime * 1_000_000
    
    fun advanceTime(millis: Long) {
        currentTime += millis
    }
    
    fun setTime(millis: Long) {
        currentTime = millis
    }
}
