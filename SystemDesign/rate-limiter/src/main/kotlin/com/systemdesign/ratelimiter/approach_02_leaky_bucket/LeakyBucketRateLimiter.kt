/**
 * # Approach 02: Leaky Bucket Rate Limiter
 *
 * ## Pattern Used
 * Leaky Bucket algorithm where requests fill a bucket and leak at a constant rate.
 * Produces smooth, consistent output regardless of input burstiness.
 *
 * ## How It Works
 * 1. Bucket has a fixed capacity (queue size)
 * 2. Incoming requests are added to the bucket (queued)
 * 3. Requests "leak" (are processed) at a constant rate
 * 4. If bucket is full, new requests are rejected
 * 5. Unlike token bucket, output rate is always constant
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Produces perfectly smooth traffic (no bursts)
 *   - Predictable processing rate
 *   - Simple conceptually
 *   - Good for systems that can't handle bursts
 *
 * - **Cons:**
 *   - Doesn't allow any bursting (inflexible)
 *   - May introduce latency during bursts (requests queue)
 *   - Not suitable when burst capacity is needed
 *   - Queue can grow in memory for as-a-queue implementations
 *
 * ## When to Prefer This Approach
 * - When downstream systems need constant, predictable load
 * - For network traffic shaping
 * - When smoothing out bursty traffic is desired
 * - Systems with strict throughput requirements
 *
 * ## Comparison with Other Approaches
 * - **vs Token Bucket (Approach 01):** Leaky bucket smooths traffic, token bucket allows bursts
 * - **vs Sliding Window (Approach 03):** Leaky bucket is simpler but less flexible
 * - **vs Fixed Window (Approach 04):** Leaky bucket has no boundary issues
 *
 * ## Implementation Note
 * This implements the "as a meter" variant which rejects excess requests
 * rather than the "as a queue" variant which queues them.
 */
package com.systemdesign.ratelimiter.approach_02_leaky_bucket

import com.systemdesign.ratelimiter.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Thread-safe Leaky Bucket rate limiter (as-a-meter variant).
 *
 * @param capacity the maximum bucket capacity
 * @param leakRatePerSecond the rate at which the bucket leaks (permits per second)
 * @param timeSource time source for testability
 */
class LeakyBucketRateLimiter(
    private val capacity: Int,
    private val leakRatePerSecond: Double,
    private val timeSource: TimeSource = SystemTimeSource
) : RateLimiter {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(leakRatePerSecond > 0) { "leakRatePerSecond must be positive" }
    }

    private val lock = ReentrantLock()
    private var waterLevel: Double = 0.0
    private var lastLeakTime: Long = timeSource.nanoTime()
    
    private val nanosPerLeak: Double = 1_000_000_000.0 / leakRatePerSecond

    override fun tryAcquire(): Boolean = tryAcquire(1)

    override fun tryAcquire(permits: Int): Boolean = lock.withLock {
        require(permits > 0) { "permits must be positive" }
        
        leak()
        
        val newLevel = waterLevel + permits
        if (newLevel <= capacity) {
            waterLevel = newLevel
            true
        } else {
            false
        }
    }

    override fun getState(): RateLimiterState = lock.withLock {
        leak()
        RateLimiterState(
            availablePermits = (capacity - waterLevel),
            maxPermits = capacity.toDouble()
        )
    }

    private fun leak() {
        val now = timeSource.nanoTime()
        val elapsed = now - lastLeakTime
        
        if (elapsed > 0) {
            val leaked = elapsed / nanosPerLeak
            waterLevel = max(0.0, waterLevel - leaked)
            lastLeakTime = now
        }
    }

    fun getCurrentLevel(): Double = lock.withLock {
        leak()
        waterLevel
    }
}

/**
 * Leaky Bucket implementation with a queue (as-a-queue variant).
 * Requests are queued and processed at a constant rate.
 *
 * @param queueCapacity maximum number of requests that can be queued
 * @param processRatePerSecond rate at which requests are processed
 */
class QueuedLeakyBucketRateLimiter<T>(
    private val queueCapacity: Int,
    private val processRatePerSecond: Double,
    private val timeSource: TimeSource = SystemTimeSource
) {
    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(processRatePerSecond > 0) { "processRatePerSecond must be positive" }
    }

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<QueuedRequest<T>>()
    private var lastProcessTime: Long = timeSource.nanoTime()
    
    private val nanosPerRequest: Long = (1_000_000_000.0 / processRatePerSecond).toLong()

    /**
     * Attempts to enqueue a request.
     * @return true if enqueued, false if queue is full
     */
    fun offer(request: T): Boolean = lock.withLock {
        if (queue.size >= queueCapacity) {
            return false
        }
        
        val scheduledTime = calculateNextProcessTime()
        queue.addLast(QueuedRequest(request, scheduledTime))
        true
    }

    /**
     * Retrieves the next request ready for processing.
     * @return the request if ready, null if queue is empty or next request not ready
     */
    fun poll(): T? = lock.withLock {
        val next = queue.firstOrNull() ?: return null
        val now = timeSource.nanoTime()
        
        if (now >= next.scheduledTime) {
            queue.removeFirst()
            lastProcessTime = now
            next.request
        } else {
            null
        }
    }

    /**
     * Gets the time until the next request is ready (in milliseconds).
     * @return time until ready, 0 if ready now, -1 if queue is empty
     */
    fun getTimeUntilNextReady(): Long = lock.withLock {
        val next = queue.firstOrNull() ?: return -1
        val now = timeSource.nanoTime()
        val waitNanos = next.scheduledTime - now
        if (waitNanos <= 0) 0 else waitNanos / 1_000_000
    }

    fun size(): Int = lock.withLock { queue.size }

    fun isEmpty(): Boolean = lock.withLock { queue.isEmpty() }

    private fun calculateNextProcessTime(): Long {
        val now = timeSource.nanoTime()
        val lastScheduled = queue.lastOrNull()?.scheduledTime ?: lastProcessTime
        return max(now, lastScheduled + nanosPerRequest)
    }

    private data class QueuedRequest<T>(
        val request: T,
        val scheduledTime: Long
    )
}

/**
 * Coroutine-friendly Leaky Bucket rate limiter.
 */
class SuspendingLeakyBucketRateLimiter(
    private val capacity: Int,
    private val leakRatePerSecond: Double,
    private val timeSource: TimeSource = SystemTimeSource
) : SuspendingRateLimiter {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(leakRatePerSecond > 0) { "leakRatePerSecond must be positive" }
    }

    private val mutex = Mutex()
    private var waterLevel: Double = 0.0
    private var lastLeakTime: Long = timeSource.nanoTime()
    
    private val nanosPerLeak: Double = 1_000_000_000.0 / leakRatePerSecond

    override suspend fun acquire() {
        while (true) {
            if (tryAcquire()) return
            kotlinx.coroutines.delay(10)
        }
    }

    override suspend fun tryAcquire(): Boolean = mutex.withLock {
        leak()
        
        val newLevel = waterLevel + 1
        if (newLevel <= capacity) {
            waterLevel = newLevel
            true
        } else {
            false
        }
    }

    private fun leak() {
        val now = timeSource.nanoTime()
        val elapsed = now - lastLeakTime
        
        if (elapsed > 0) {
            val leaked = elapsed / nanosPerLeak
            waterLevel = max(0.0, waterLevel - leaked)
            lastLeakTime = now
        }
    }
}
