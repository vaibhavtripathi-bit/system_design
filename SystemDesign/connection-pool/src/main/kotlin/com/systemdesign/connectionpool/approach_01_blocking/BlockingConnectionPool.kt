/**
 * # Approach 01: Blocking Connection Pool
 *
 * ## Pattern
 * Traditional blocking pool backed by a bounded queue. Threads calling [acquire] block
 * when the pool is exhausted until a connection is returned via [release] or the
 * configured timeout elapses. Synchronization is handled with [ReentrantLock] and
 * [Condition] variables, giving precise control over waiting threads.
 *
 * ## Trade-offs
 * - **Pros:** Simple mental model, predictable behavior, works with any thread-based framework
 * - **Cons:** Blocks OS threads (expensive under high concurrency), no cooperative cancellation
 *
 * ## When to Prefer
 * JDBC-style applications, legacy codebases, or anywhere coroutines are not available.
 */
package com.systemdesign.connectionpool.approach_01_blocking

import com.systemdesign.connectionpool.common.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PooledEntry<T : Poolable>(
    val resource: T,
    @Volatile var state: ConnectionState = ConnectionState.IDLE,
    val addedAt: Long = System.currentTimeMillis(),
    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
)

class BlockingConnectionPool<T : Poolable>(
    private val config: PoolConfig,
    private val factory: () -> T,
    private val observer: PoolObserver = object : PoolObserver {}
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val available = lock.newCondition()

    private val idleQueue = LinkedBlockingDeque<PooledEntry<T>>()
    private val allEntries = ConcurrentHashMap<String, PooledEntry<T>>()
    private val totalCount = AtomicInteger(0)
    private val waitingCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    init {
        repeat(config.minSize) { addEntry() }
    }

    fun acquire(): T {
        check(!closed.get()) { "Pool is closed" }

        val deadline = System.currentTimeMillis() + config.acquireTimeoutMs
        waitingCount.incrementAndGet()
        try {
            lock.withLock {
                while (true) {
                    val entry = tryPollValid()
                    if (entry != null) {
                        entry.state = ConnectionState.IN_USE
                        entry.lastUsedAt = System.currentTimeMillis()
                        observer.onAcquire(entry.resource.id)
                        return entry.resource
                    }

                    if (totalCount.get() < config.maxSize) {
                        val resource = factory()
                        val fresh = PooledEntry(resource, state = ConnectionState.IN_USE)
                        fresh.lastUsedAt = System.currentTimeMillis()
                        allEntries[resource.id] = fresh
                        totalCount.incrementAndGet()
                        observer.onAcquire(fresh.resource.id)
                        return fresh.resource
                    }

                    val remainingMs = deadline - System.currentTimeMillis()
                    if (remainingMs <= 0) {
                        observer.onTimeout(config.acquireTimeoutMs)
                        throw IllegalStateException(
                            "Timed out waiting for a connection after ${config.acquireTimeoutMs}ms"
                        )
                    }
                    available.await(remainingMs, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            waitingCount.decrementAndGet()
        }
    }

    fun release(resource: T) {
        val entry = allEntries[resource.id]
            ?: throw IllegalArgumentException("Unknown resource: ${resource.id}")

        lock.withLock {
            entry.state = ConnectionState.IDLE
            entry.lastUsedAt = System.currentTimeMillis()
            idleQueue.addFirst(entry)
            observer.onRelease(resource.id)
            available.signal()
        }
    }

    fun evictIdle() {
        val now = System.currentTimeMillis()
        val toEvict = mutableListOf<PooledEntry<T>>()

        lock.withLock {
            val iter = idleQueue.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val idleTooLong = (now - entry.lastUsedAt) > config.idleTimeoutMs
                val exceededLifetime = (now - entry.resource.createdAt) > config.maxLifetimeMs
                if ((idleTooLong || exceededLifetime) && totalCount.get() > config.minSize) {
                    iter.remove()
                    entry.state = ConnectionState.EVICTED
                    toEvict.add(entry)
                }
            }
        }

        toEvict.forEach { entry ->
            allEntries.remove(entry.resource.id)
            totalCount.decrementAndGet()
            observer.onEvict(entry.resource.id)
            runCatching { entry.resource.close() }
        }
    }

    fun validateIdle() {
        val snapshot = lock.withLock { idleQueue.toList() }
        for (entry in snapshot) {
            entry.state = ConnectionState.VALIDATING
            if (!entry.resource.isValid()) {
                observer.onValidationFailure(entry.resource.id, RuntimeException("Validation failed"))
                lock.withLock { idleQueue.remove(entry) }
                allEntries.remove(entry.resource.id)
                totalCount.decrementAndGet()
                entry.state = ConnectionState.CLOSED
                runCatching { entry.resource.close() }
            } else {
                entry.state = ConnectionState.IDLE
            }
        }
        refillToMin()
    }

    fun stats(): PoolStats {
        val total = totalCount.get()
        val idle = idleQueue.size
        return PoolStats(
            totalConnections = total,
            activeConnections = total - idle,
            idleConnections = idle,
            waitingRequests = waitingCount.get()
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lock.withLock {
            idleQueue.forEach { entry ->
                entry.state = ConnectionState.CLOSED
                runCatching { entry.resource.close() }
            }
            idleQueue.clear()
            allEntries.values.filter { it.state != ConnectionState.CLOSED }.forEach { entry ->
                entry.state = ConnectionState.CLOSED
                runCatching { entry.resource.close() }
            }
            allEntries.clear()
            totalCount.set(0)
            available.signalAll()
        }
    }

    private fun addEntry(): PooledEntry<T> {
        val resource = factory()
        val entry = PooledEntry(resource)
        allEntries[resource.id] = entry
        idleQueue.addLast(entry)
        totalCount.incrementAndGet()
        return entry
    }

    private fun tryPollValid(): PooledEntry<T>? {
        while (true) {
            val entry = idleQueue.pollFirst() ?: return null
            val now = System.currentTimeMillis()
            if ((now - entry.resource.createdAt) > config.maxLifetimeMs) {
                destroyEntry(entry)
                continue
            }
            if (entry.resource.isValid()) return entry
            destroyEntry(entry)
        }
    }

    private fun destroyEntry(entry: PooledEntry<T>) {
        allEntries.remove(entry.resource.id)
        totalCount.decrementAndGet()
        entry.state = ConnectionState.CLOSED
        runCatching { entry.resource.close() }
    }

    private fun refillToMin() {
        lock.withLock {
            while (totalCount.get() < config.minSize) {
                addEntry()
            }
        }
    }
}
