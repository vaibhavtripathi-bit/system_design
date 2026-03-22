/**
 * # Approach 04: Fixed Window Counter Rate Limiter
 *
 * ## Pattern Used
 * Fixed Window Counter algorithm that divides time into fixed windows and tracks
 * request counts per window. Simple and memory-efficient.
 *
 * ## How It Works
 * 1. Time is divided into fixed-size windows (e.g., 1 minute windows)
 * 2. Each window has a counter starting at 0
 * 3. Requests increment the counter
 * 4. If counter >= limit, reject request
 * 5. Counter resets at window boundary
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Very simple to implement
 *   - O(1) memory per client
 *   - O(1) time complexity
 *   - Easy to understand and debug
 *   - Works well with distributed systems (atomic counter)
 *
 * - **Cons:**
 *   - Boundary burst problem: 2x limit possible at window edges
 *   - Example: 100/min limit, 100 requests at 0:59, 100 more at 1:00
 *   - Not smooth - sharp reset at boundaries
 *   - May feel unfair to users at window start
 *
 * ## When to Prefer This Approach
 * - When simplicity is the priority
 * - For distributed rate limiting (easy to implement with Redis INCR)
 * - When boundary bursts are acceptable
 * - High-throughput systems where accuracy trade-off is acceptable
 *
 * ## Comparison with Other Approaches
 * - **vs Token Bucket (Approach 01):** Fixed window is simpler but has boundary issues
 * - **vs Leaky Bucket (Approach 02):** Fixed window allows bursts, leaky bucket doesn't
 * - **vs Sliding Window (Approach 03):** Sliding window eliminates boundary problem but uses more memory
 *
 * ## Mitigation for Boundary Problem
 * Use with slightly lower limits or combine with sliding window counter approach
 */
package com.systemdesign.ratelimiter.approach_04_fixed_window

import com.systemdesign.ratelimiter.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe Fixed Window Counter rate limiter.
 *
 * @param maxRequests maximum requests allowed per window
 * @param windowSizeMs window size in milliseconds
 * @param timeSource time source for testability
 */
class FixedWindowRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val lock = ReentrantLock()
    private var currentWindowStart: Long = 0
    private var currentCount: Int = 0
    
    private var totalRequests = 0L
    private var rejectedRequests = 0L
    private var windowsCount = 0L

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean = lock.withLock {
        require(permits > 0) { "permits must be positive" }
        
        val now = timeSource.currentTimeMillis()
        val windowStart = getWindowStart(now)
        
        if (windowStart != currentWindowStart) {
            currentWindowStart = windowStart
            currentCount = 0
            windowsCount++
        }
        
        if (currentCount + permits <= maxRequests) {
            currentCount += permits
            totalRequests += permits
            true
        } else {
            rejectedRequests += permits
            false
        }
    }

    override fun getState(): RateLimiterState = lock.withLock {
        val now = timeSource.currentTimeMillis()
        val windowStart = getWindowStart(now)
        
        val count = if (windowStart == currentWindowStart) currentCount else 0
        
        RateLimiterState(
            availablePermits = (maxRequests - count).toDouble(),
            maxPermits = maxRequests.toDouble(),
            windowStartTime = windowStart,
            requestsInWindow = count.toLong()
        )
    }

    private fun getWindowStart(timestamp: Long): Long {
        return (timestamp / windowSizeMs) * windowSizeMs
    }

    fun getStats(): FixedWindowStats = lock.withLock {
        FixedWindowStats(
            totalRequests = totalRequests,
            rejectedRequests = rejectedRequests,
            windowsCount = windowsCount,
            currentCount = currentCount
        )
    }

    /**
     * Returns time until the current window resets (in milliseconds).
     */
    fun getTimeUntilReset(): Long = lock.withLock {
        val now = timeSource.currentTimeMillis()
        val windowEnd = currentWindowStart + windowSizeMs
        maxOf(0, windowEnd - now)
    }
}

/**
 * Lock-free Fixed Window Counter using atomic operations.
 * Better for high-contention scenarios.
 */
class AtomicFixedWindowRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val windowStart = AtomicLong(0)
    private val counter = AtomicInteger(0)

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean {
        require(permits > 0) { "permits must be positive" }
        
        val now = timeSource.currentTimeMillis()
        val currentWindowStart = getWindowStart(now)
        
        while (true) {
            val lastWindowStart = windowStart.get()
            
            if (currentWindowStart > lastWindowStart) {
                if (windowStart.compareAndSet(lastWindowStart, currentWindowStart)) {
                    counter.set(0)
                }
                continue
            }
            
            val currentCount = counter.get()
            if (currentCount + permits > maxRequests) {
                return false
            }
            
            if (counter.compareAndSet(currentCount, currentCount + permits)) {
                return true
            }
        }
    }

    override fun getState(): RateLimiterState {
        val now = timeSource.currentTimeMillis()
        val currentWindowStart = getWindowStart(now)
        val lastWindowStart = windowStart.get()
        
        val count = if (currentWindowStart == lastWindowStart) counter.get() else 0
        
        return RateLimiterState(
            availablePermits = (maxRequests - count).toDouble(),
            maxPermits = maxRequests.toDouble(),
            windowStartTime = currentWindowStart,
            requestsInWindow = count.toLong()
        )
    }

    private fun getWindowStart(timestamp: Long): Long {
        return (timestamp / windowSizeMs) * windowSizeMs
    }
}

/**
 * Per-key Fixed Window rate limiter for multi-tenant scenarios.
 * Maintains separate counters per key (e.g., user ID, API key).
 */
class PerKeyFixedWindowRateLimiter<K : Any>(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) {
    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val limiters = ConcurrentHashMap<K, KeyState>()

    fun tryAcquire(key: K, permits: Int = 1): Boolean {
        require(permits > 0) { "permits must be positive" }
        
        val now = timeSource.currentTimeMillis()
        val windowStart = getWindowStart(now)
        
        val state = limiters.compute(key) { _, existing ->
            if (existing == null || existing.windowStart != windowStart) {
                KeyState(windowStart, AtomicInteger(0))
            } else {
                existing
            }
        }!!
        
        while (true) {
            val currentCount = state.counter.get()
            if (currentCount + permits > maxRequests) {
                return false
            }
            if (state.counter.compareAndSet(currentCount, currentCount + permits)) {
                return true
            }
        }
    }

    fun getState(key: K): RateLimiterState {
        val now = timeSource.currentTimeMillis()
        val windowStart = getWindowStart(now)
        val state = limiters[key]
        
        val count = if (state?.windowStart == windowStart) state.counter.get() else 0
        
        return RateLimiterState(
            availablePermits = (maxRequests - count).toDouble(),
            maxPermits = maxRequests.toDouble(),
            windowStartTime = windowStart,
            requestsInWindow = count.toLong()
        )
    }

    fun cleanup() {
        val now = timeSource.currentTimeMillis()
        val currentWindowStart = getWindowStart(now)
        
        limiters.entries.removeIf { (_, state) ->
            state.windowStart < currentWindowStart - windowSizeMs
        }
    }

    private fun getWindowStart(timestamp: Long): Long {
        return (timestamp / windowSizeMs) * windowSizeMs
    }

    private data class KeyState(
        val windowStart: Long,
        val counter: AtomicInteger
    )
}

/**
 * Coroutine-friendly Fixed Window rate limiter.
 */
class SuspendingFixedWindowRateLimiter(
    private val maxRequests: Int,
    private val windowSizeMs: Long,
    private val timeSource: TimeSource = SystemTimeSource
) : SuspendingRateLimiter {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSizeMs > 0) { "windowSizeMs must be positive" }
    }

    private val mutex = Mutex()
    private var currentWindowStart: Long = 0
    private var currentCount: Int = 0

    override suspend fun acquire() {
        while (true) {
            if (tryAcquire()) return
            kotlinx.coroutines.delay(10)
        }
    }

    override suspend fun tryAcquire(): Boolean = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        val windowStart = getWindowStart(now)
        
        if (windowStart != currentWindowStart) {
            currentWindowStart = windowStart
            currentCount = 0
        }
        
        if (currentCount < maxRequests) {
            currentCount++
            true
        } else {
            false
        }
    }

    private fun getWindowStart(timestamp: Long): Long {
        return (timestamp / windowSizeMs) * windowSizeMs
    }
}

data class FixedWindowStats(
    val totalRequests: Long,
    val rejectedRequests: Long,
    val windowsCount: Long,
    val currentCount: Int
)
