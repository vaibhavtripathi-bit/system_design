/**
 * # Approach 02: Coroutine-Based Connection Pool
 *
 * ## Pattern
 * Non-blocking pool built on Kotlin coroutines. A [Channel] serves as the idle-resource
 * queue and a [Mutex] guards pool-wide mutations. Callers *suspend* instead of blocking
 * an OS thread, making this approach far more scalable under high concurrency.
 * Structured concurrency ensures background maintenance jobs are cancelled cleanly
 * when the pool shuts down.
 *
 * ## Trade-offs
 * - **Pros:** No thread blocking, cooperative cancellation, back-pressure via Channel
 * - **Cons:** Requires a coroutine runtime, harder to reason about for blocking-only teams
 *
 * ## When to Prefer
 * Ktor / Compose / any suspend-first codebase; high-concurrency services where thread
 * count is a bottleneck.
 */
package com.systemdesign.connectionpool.approach_02_coroutine

import com.systemdesign.connectionpool.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CoroutinePoolEntry<T : Poolable>(
    val resource: T,
    @Volatile var state: ConnectionState = ConnectionState.IDLE,
    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
)

class CoroutineConnectionPool<T : Poolable>(
    private val config: PoolConfig,
    private val factory: suspend () -> T,
    private val observer: PoolObserver = object : PoolObserver {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AutoCloseable {

    private val idleChannel = Channel<CoroutinePoolEntry<T>>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val allEntries = ConcurrentHashMap<String, CoroutinePoolEntry<T>>()
    private val totalCount = AtomicInteger(0)
    private val waitingCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    private var maintenanceJob: Job? = null

    suspend fun initialize() {
        mutex.withLock {
            repeat(config.minSize) { createAndEnqueue() }
        }
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(config.validationIntervalMs)
                runCatching { maintain() }
            }
        }
    }

    suspend fun acquire(): T {
        check(!closed.get()) { "Pool is closed" }

        waitingCount.incrementAndGet()
        try {
            val entry = tryAcquireExisting()
            if (entry != null) return markAcquired(entry)

            mutex.withLock {
                if (totalCount.get() < config.maxSize) {
                    val fresh = createEntry()
                    return markAcquired(fresh)
                }
            }

            return withTimeout(config.acquireTimeoutMs) {
                while (true) {
                    val pooled = idleChannel.receive()
                    if (isValidEntry(pooled)) return@withTimeout markAcquired(pooled)
                    destroyEntry(pooled)

                    mutex.withLock {
                        if (totalCount.get() < config.minSize) createAndEnqueue()
                    }
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

    suspend fun maintain() {
        validateAndEvict()
        refillToMin()
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        maintenanceJob?.cancel()
        scope.cancel()
        idleChannel.close()
        allEntries.values.forEach { entry ->
            entry.state = ConnectionState.CLOSED
            runCatching { entry.resource.close() }
        }
        allEntries.clear()
        totalCount.set(0)
    }

    private suspend fun tryAcquireExisting(): CoroutinePoolEntry<T>? {
        val entry = idleChannel.tryReceive().getOrNull() ?: return null
        if (isValidEntry(entry)) return entry
        destroyEntry(entry)
        return null
    }

    private fun markAcquired(entry: CoroutinePoolEntry<T>): T {
        entry.state = ConnectionState.IN_USE
        entry.lastUsedAt = System.currentTimeMillis()
        observer.onAcquire(entry.resource.id)
        return entry.resource
    }

    private fun isValidEntry(entry: CoroutinePoolEntry<T>): Boolean {
        val now = System.currentTimeMillis()
        if ((now - entry.resource.createdAt) > config.maxLifetimeMs) return false
        return entry.resource.isValid()
    }

    private suspend fun createEntry(): CoroutinePoolEntry<T> {
        val resource = factory()
        val entry = CoroutinePoolEntry(resource)
        allEntries[resource.id] = entry
        totalCount.incrementAndGet()
        return entry
    }

    private suspend fun createAndEnqueue() {
        val entry = createEntry()
        idleChannel.send(entry)
    }

    private fun destroyEntry(entry: CoroutinePoolEntry<T>) {
        allEntries.remove(entry.resource.id)
        totalCount.decrementAndGet()
        entry.state = ConnectionState.CLOSED
        runCatching { entry.resource.close() }
    }

    private suspend fun validateAndEvict() {
        val now = System.currentTimeMillis()
        val snapshot = allEntries.values.filter { it.state == ConnectionState.IDLE }.toList()

        for (entry in snapshot) {
            entry.state = ConnectionState.VALIDATING
            val idleTooLong = (now - entry.lastUsedAt) > config.idleTimeoutMs
            val exceededLifetime = (now - entry.resource.createdAt) > config.maxLifetimeMs
            val invalid = !entry.resource.isValid()

            if ((idleTooLong || exceededLifetime || invalid) && totalCount.get() > config.minSize) {
                observer.onEvict(entry.resource.id)
                destroyEntry(entry)
            } else {
                entry.state = ConnectionState.IDLE
            }
        }
    }

    private suspend fun refillToMin() {
        mutex.withLock {
            while (totalCount.get() < config.minSize) {
                createAndEnqueue()
            }
        }
    }
}
