/**
 * # Approach 03: Sliding Window Log Rate Limiter
 *
 * ## Pattern Used
 * Sliding Window Log algorithm that tracks timestamps of all requests in the window.
 * Provides the most accurate rate limiting at the cost of higher memory usage.
 *
 * ## How It Works
 * 1. Store timestamp of each request
 * 2. On new request, remove timestamps outside the window
 * 3. Count remaining timestamps
 * 4. Allow if count < limit, reject otherwise
 * 5. Window slides continuously with time
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Most accurate rate limiting (no boundary issues)
 *   - Handles edge cases perfectly
 *   - Intuitive behavior for users
 *   - No burst issues at window boundaries
 *
 * - **Cons:**
 *   - O(n) memory per client where n = limit
 *   - Higher CPU cost (log management)
 *   - Not suitable for very high limits
 *   - Timestamp storage overhead
 *
 * ## When to Prefer This Approach
 * - When accuracy is critical
 * - For low-volume, high-value rate limiting (e.g., API write operations)
 * - When boundary bursts must be prevented
 * - Compliance/regulatory requirements for precise limiting
 *
 * ## Comparison with Other Approaches
 * - **vs Token Bucket (Approach 01):** More accurate but higher memory; token bucket is more efficient
 * - **vs Leaky Bucket (Approach 02):** Both smooth, but sliding window is more flexible
 * - **vs Fixed Window (Approach 04):** Eliminates boundary burst problem of fixed windows
 *
 * ## Variant: Sliding Window Counter
 * A memory-efficient approximation that uses counters for current and previous windows
 * with weighted calculation. Trades some accuracy for O(1) memory.
 */
package com.systemdesign.ratelimiter.approach_03_sliding_window

import com.systemdesign.ratelimiter.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe Sliding Window Log rate limiter.
 *
 * @param maxRequests maximum requests allowed in the window
 * @param windowSizeMs window size in milliseconds
 * @param timeSource time source for testability
 */
class SlidingWindowLogRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val lock = ReentrantLock()
    private val timestamps = ArrayDeque<Long>()
    
    private var totalRequests = 0L
    private var rejectedRequests = 0L

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean = lock.withLock {
        require(permits > 0) { "permits must be positive" }
        
        val now = timeSource.currentTimeMillis()
        evictOldEntries(now)
        
        if (timestamps.size + permits <= maxRequests) {
            repeat(permits) {
                timestamps.addLast(now)
            }
            totalRequests += permits
            true
        } else {
            rejectedRequests += permits
            false
        }
    }

    override fun getState(): RateLimiterState = lock.withLock {
        val now = timeSource.currentTimeMillis()
        evictOldEntries(now)
        
        RateLimiterState(
            availablePermits = (maxRequests - timestamps.size).toDouble(),
            maxPermits = maxRequests.toDouble(),
            windowStartTime = now - windowSizeMs,
            requestsInWindow = timestamps.size.toLong()
        )
    }

    private fun evictOldEntries(now: Long) {
        val windowStart = now - windowSizeMs
        while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
            timestamps.removeFirst()
        }
    }

    fun getStats(): SlidingWindowStats = lock.withLock {
        SlidingWindowStats(
            totalRequests = totalRequests,
            rejectedRequests = rejectedRequests,
            currentWindowSize = timestamps.size
        )
    }
}

/**
 * Memory-efficient Sliding Window Counter approximation.
 * Uses weighted average of current and previous window counts.
 *
 * Memory: O(1) regardless of limit
 * Accuracy: Approximate (within bounds)
 */
class SlidingWindowCounterRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val lock = ReentrantLock()
    private var currentWindowStart: Long = -1
    private var currentCount: Int = 0
    private var previousCount: Int = 0

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean = lock.withLock {
        require(permits > 0) { "permits must be positive" }
        
        val now = timeSource.currentTimeMillis()
        updateWindows(now)
        
        val weight = 1.0 - ((now - currentWindowStart).toDouble() / windowSizeMs)
        val weightedCount = (previousCount * weight + currentCount).toInt()
        
        if (weightedCount + permits <= maxRequests) {
            currentCount += permits
            true
        } else {
            false
        }
    }

    override fun getState(): RateLimiterState = lock.withLock {
        val now = timeSource.currentTimeMillis()
        updateWindows(now)
        
        val weight = 1.0 - ((now - currentWindowStart).toDouble() / windowSizeMs)
        val weightedCount = (previousCount * weight + currentCount).toInt()
        
        RateLimiterState(
            availablePermits = (maxRequests - weightedCount).toDouble(),
            maxPermits = maxRequests.toDouble(),
            windowStartTime = currentWindowStart,
            requestsInWindow = weightedCount.toLong()
        )
    }

    private fun updateWindows(now: Long) {
        val windowStart = (now / windowSizeMs) * windowSizeMs
        
        if (currentWindowStart < 0) {
            currentWindowStart = windowStart
            return
        }
        
        val windowsPassed = ((windowStart - currentWindowStart) / windowSizeMs).toInt()
        
        when {
            windowsPassed >= 2 -> {
                previousCount = 0
                currentCount = 0
                currentWindowStart = windowStart
            }
            windowsPassed == 1 -> {
                previousCount = currentCount
                currentCount = 0
                currentWindowStart = windowStart
            }
        }
    }
}

/**
 * Coroutine-friendly Sliding Window Log rate limiter.
 */
class SuspendingSlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : SuspendingRateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()

    override suspend fun acquire() {
        while (true) {
            if (tryAcquire()) return
            kotlinx.coroutines.delay(10)
        }
    }

    override suspend fun tryAcquire(): Boolean = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        evictOldEntries(now)
        
        if (timestamps.size < maxRequests) {
            timestamps.addLast(now)
            true
        } else {
            false
        }
    }

    private fun evictOldEntries(now: Long) {
        val windowStart = now - windowSizeMs
        while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
            timestamps.removeFirst()
        }
    }
}

data class SlidingWindowStats(
    val totalRequests: Long,
    val rejectedRequests: Long,
    val currentWindowSize: Int
)
