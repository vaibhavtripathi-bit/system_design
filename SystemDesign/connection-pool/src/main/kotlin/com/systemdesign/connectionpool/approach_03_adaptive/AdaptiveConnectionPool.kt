/**
 * # Approach 03: Adaptive (Self-Tuning) Connection Pool
 *
 * ## Pattern
 * A coroutine-based pool that continuously monitors usage patterns and adjusts its
 * size dynamically. A background [tuningJob] samples utilization at fixed intervals
 * and scales the pool up under sustained load or shrinks it during idle periods.
 * A separate [healthCheckJob] validates connections and evicts stale or expired entries.
 *
 * ## Trade-offs
 * - **Pros:** Optimal resource usage under variable load, automatic health checking,
 *   no manual tuning required
 * - **Cons:** More complex internals, slight latency from periodic resizing decisions,
 *   harder to predict exact pool size at any moment
 *
 * ## When to Prefer
 * Cloud services with bursty traffic, microservices behind auto-scaling, or any system
 * where connection demand varies significantly over time.
 */
package com.systemdesign.connectionpool.approach_03_adaptive

import com.systemdesign.connectionpool.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class AdaptiveConfig(
    val base: PoolConfig = PoolConfig(),
    val scaleUpThreshold: Double = 0.8,
    val scaleDownThreshold: Double = 0.3,
    val scaleStep: Int = 2,
    val tuningIntervalMs: Long = 5_000,
    val healthCheckIntervalMs: Long = 15_000
)

class AdaptivePoolEntry<T : Poolable>(
    val resource: T,
    @Volatile var state: ConnectionState = ConnectionState.IDLE,
    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
)

class AdaptiveConnectionPool<T : Poolable>(
    private val adaptiveConfig: AdaptiveConfig = AdaptiveConfig(),
    private val factory: suspend () -> T,
    private val observer: PoolObserver = object : PoolObserver {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AutoCloseable {

    private val config get() = adaptiveConfig.base

    private val idleChannel = Channel<AdaptivePoolEntry<T>>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val allEntries = ConcurrentHashMap<String, AdaptivePoolEntry<T>>()
    private val totalCount = AtomicInteger(0)
    private val waitingCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var currentTarget: Int = config.minSize

    private var tuningJob: Job? = null
    private var healthCheckJob: Job? = null

    suspend fun initialize() {
        mutex.withLock {
            repeat(config.minSize) { createAndEnqueue() }
            currentTarget = config.minSize
        }
        tuningJob = scope.launch {
            while (isActive) {
                delay(adaptiveConfig.tuningIntervalMs)
                runCatching { tune() }
            }
        }
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(adaptiveConfig.healthCheckIntervalMs)
                runCatching { healthCheck() }
            }
        }
    }

    suspend fun acquire(): T {
        check(!closed.get()) { "Pool is closed" }

        waitingCount.incrementAndGet()
        try {
            val fast = tryAcquireExisting()
            if (fast != null) return markAcquired(fast)

            mutex.withLock {
                if (totalCount.get() < config.maxSize) {
                    return markAcquired(createEntry())
                }
            }

            return withTimeout(config.acquireTimeoutMs) {
                while (true) {
                    val entry = idleChannel.receive()
                    if (isHealthy(entry)) return@withTimeout markAcquired(entry)
                    destroyEntry(entry)
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            observer.onTimeout(config.acquireTimeoutMs)
            throw IllegalStateException(
                "Timed out waiting for a connection after ${config.acquireTimeoutMs}ms"
            )
        } finally {
            waitingCount.decrementAndGet()
        }
    }

    suspend fun release(resource: T) {
        val entry = allEntries[resource.id]
            ?: throw IllegalArgumentException("Unknown resource: ${resource.id}")

        entry.state = ConnectionState.IDLE
        entry.lastUsedAt = System.currentTimeMillis()
        observer.onRelease(resource.id)
        idleChannel.send(entry)
    }

    fun stats(): PoolStats {
        val total = totalCount.get()
        val idle = allEntries.values.count { it.state == ConnectionState.IDLE }
        return PoolStats(
            totalConnections = total,
            activeConnections = total - idle,
            idleConnections = idle,
            waitingRequests = waitingCount.get()
        )
    }

    fun currentTargetSize(): Int = currentTarget

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tuningJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
        idleChannel.close()
        allEntries.values.forEach { entry ->
            entry.state = ConnectionState.CLOSED
            runCatching { entry.resource.close() }
        }
        allEntries.clear()
        totalCount.set(0)
    }

    internal suspend fun tune() {
        val total = totalCount.get().coerceAtLeast(1)
        val active = allEntries.values.count { it.state == ConnectionState.IN_USE }
        val utilization = active.toDouble() / total

        val oldTarget = currentTarget
        currentTarget = when {
            utilization >= adaptiveConfig.scaleUpThreshold -> {
                min(oldTarget + adaptiveConfig.scaleStep, config.maxSize)
            }
            utilization <= adaptiveConfig.scaleDownThreshold -> {
                max(oldTarget - adaptiveConfig.scaleStep, config.minSize)
            }
            else -> oldTarget
        }

        if (currentTarget != oldTarget) {
            observer.onPoolResized(oldTarget, currentTarget)
        }

        mutex.withLock {
            while (totalCount.get() < currentTarget) {
                createAndEnqueue()
            }
        }

        if (totalCount.get() > currentTarget) {
            shrinkTo(currentTarget)
        }
    }

    internal suspend fun healthCheck() {
        val now = System.currentTimeMillis()
        val snapshot = allEntries.values.filter { it.state == ConnectionState.IDLE }.toList()

        for (entry in snapshot) {
            entry.state = ConnectionState.VALIDATING
            val expired = (now - entry.resource.createdAt) > config.maxLifetimeMs
            val invalid = !entry.resource.isValid()

            if (expired || invalid) {
                if (invalid) {
                    observer.onValidationFailure(
                        entry.resource.id, RuntimeException("Health check failed")
                    )
                }
                observer.onEvict(entry.resource.id)
                destroyEntry(entry)
            } else {
                entry.state = ConnectionState.IDLE
            }
        }

        mutex.withLock {
            while (totalCount.get() < config.minSize) {
                createAndEnqueue()
            }
        }
    }

    private fun tryAcquireExisting(): AdaptivePoolEntry<T>? {
        val entry = idleChannel.tryReceive().getOrNull() ?: return null
        if (isHealthy(entry)) return entry
        destroyEntry(entry)
        return null
    }

    private fun markAcquired(entry: AdaptivePoolEntry<T>): T {
        entry.state = ConnectionState.IN_USE
        entry.lastUsedAt = System.currentTimeMillis()
        observer.onAcquire(entry.resource.id)
        return entry.resource
    }

    private fun isHealthy(entry: AdaptivePoolEntry<T>): Boolean {
        val now = System.currentTimeMillis()
        if ((now - entry.resource.createdAt) > config.maxLifetimeMs) return false
        return entry.resource.isValid()
    }

    private suspend fun createEntry(): AdaptivePoolEntry<T> {
        val resource = factory()
        val entry = AdaptivePoolEntry(resource)
        allEntries[resource.id] = entry
        totalCount.incrementAndGet()
        return entry
    }

    private suspend fun createAndEnqueue() {
        val entry = createEntry()
        idleChannel.send(entry)
    }

    private fun destroyEntry(entry: AdaptivePoolEntry<T>) {
        allEntries.remove(entry.resource.id)
        totalCount.decrementAndGet()
        entry.state = ConnectionState.CLOSED
        runCatching { entry.resource.close() }
    }

    private suspend fun shrinkTo(target: Int) {
        while (totalCount.get() > target) {
            val entry = idleChannel.tryReceive().getOrNull() ?: break
            observer.onEvict(entry.resource.id)
            destroyEntry(entry)
        }
    }
}
