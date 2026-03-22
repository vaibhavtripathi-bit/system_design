/**
 * # Approach 01: Token Bucket Rate Limiter
 *
 * ## Pattern Used
 * Token Bucket algorithm where tokens are added at a fixed rate and requests consume tokens.
 * Allows controlled bursting while maintaining long-term rate compliance.
 *
 * ## How It Works
 * 1. Bucket has a maximum capacity (burst size)
 * 2. Tokens are added at a fixed rate (refill rate)
 * 3. Each request consumes one or more tokens
 * 4. If insufficient tokens, request is rejected
 * 5. Tokens accumulate up to max capacity, enabling bursts
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Allows controlled bursting (good for bursty traffic)
 *   - Simple to understand and implement
 *   - Memory efficient (O(1) per rate limiter)
 *   - Smooth rate limiting over time
 *
 * - **Cons:**
 *   - Can allow bursts at window boundaries
 *   - Accumulated tokens might cause unexpected load spikes
 *   - Single point in time measurement (no history)
 *
 * ## When to Prefer This Approach
 * - When you want to allow controlled bursting
 * - For API rate limiting where temporary spikes are acceptable
 * - When memory efficiency is important
 * - Good default choice for most use cases
 *
 * ## Comparison with Other Approaches
 * - **vs Leaky Bucket (Approach 02):** Token bucket allows bursts, leaky bucket smooths traffic
 * - **vs Sliding Window (Approach 03):** Sliding window is more precise but uses more memory
 * - **vs Fixed Window (Approach 04):** Token bucket handles boundaries better
 */
package com.systemdesign.ratelimiter.approach_01_token_bucket

import com.systemdesign.ratelimiter.common.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe Token Bucket rate limiter implementation.
 *
 * @param permitsPerSecond the rate at which permits are refilled
 * @param maxBurstSize the maximum number of permits that can accumulate (bucket capacity)
 * @param timeSource time source for testability
 */
class TokenBucketRateLimiter(
    private val permitsPerSecond: Double,
    private val maxBurstSize: Int = 1,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive" }
        require(maxBurstSize >= 1) { "maxBurstSize must be at least 1" }
    }

    private val lock = ReentrantLock()
    private var availableTokens: Double = maxBurstSize.toDouble()
    private var lastRefillTime: Long = timeSource.nanoTime()
    
    private val nanosPerPermit: Double = 1_000_000_000.0 / permitsPerSecond

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean = lock.withLock {
        require(permits > 0) { "permits must be positive" }
        
        refill()
        
        if (availableTokens >= permits) {
            availableTokens -= permits
            true
        } else {
            false
        }
    }

    override fun getState(): RateLimiterState = lock.withLock {
        refill()
        RateLimiterState(
            availablePermits = availableTokens,
            maxPermits = maxBurstSize.toDouble()
        )
    }

    private fun refill() {
        val now = timeSource.nanoTime()
        val elapsed = now - lastRefillTime
        
        if (elapsed > 0) {
            val newTokens = elapsed / nanosPerPermit
            availableTokens = min(maxBurstSize.toDouble(), availableTokens + newTokens)
            lastRefillTime = now
        }
    }

    /**
     * Waits until a permit becomes available.
     * @return the time waited in nanoseconds
     */
    fun acquire(): Long = lock.withLock {
        refill()
        
        if (availableTokens >= 1) {
            availableTokens -= 1
            return 0
        }
        
        val tokensNeeded = 1 - availableTokens
        val waitNanos = (tokensNeeded * nanosPerPermit).toLong()
        
        try {
            Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        lastRefillTime = timeSource.nanoTime()
        availableTokens = 0.0
        waitNanos
    }
}

/**
 * Coroutine-friendly Token Bucket rate limiter.
 */
class SuspendingTokenBucketRateLimiter(
    private val permitsPerSecond: Double,
    private val maxBurstSize: Int = 1,
    private val timeSource: TimeSource = SystemTimeSource
) : SuspendingRateLimiter {

    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive" }
        require(maxBurstSize >= 1) { "maxBurstSize must be at least 1" }
    }

    private val mutex = Mutex()
    private var availableTokens: Double = maxBurstSize.toDouble()
    private var lastRefillTime: Long = timeSource.nanoTime()
    
    private val nanosPerPermit: Double = 1_000_000_000.0 / permitsPerSecond

    override suspend fun acquire() {
        mutex.withLock {
            refill()
            
            if (availableTokens >= 1) {
                availableTokens -= 1
                return
            }
            
            val tokensNeeded = 1 - availableTokens
            val waitMillis = (tokensNeeded * nanosPerPermit / 1_000_000).toLong()
            
            delay(waitMillis)
            
            lastRefillTime = timeSource.nanoTime()
            availableTokens = 0.0
        }
    }

    override suspend fun tryAcquire(): Boolean = mutex.withLock {
        refill()
        
        if (availableTokens >= 1) {
            availableTokens -= 1
            true
        } else {
            false
        }
    }

    private fun refill() {
        val now = timeSource.nanoTime()
        val elapsed = now - lastRefillTime
        
        if (elapsed > 0) {
            val newTokens = elapsed / nanosPerPermit
            availableTokens = min(maxBurstSize.toDouble(), availableTokens + newTokens)
            lastRefillTime = now
        }
    }
}
