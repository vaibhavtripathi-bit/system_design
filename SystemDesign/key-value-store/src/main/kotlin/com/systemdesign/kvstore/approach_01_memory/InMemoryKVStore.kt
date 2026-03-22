/**
 * # Approach 01: In-Memory Key-Value Store
 *
 * ## Pattern Used
 * ConcurrentHashMap-based store with TTL support.
 *
 * ## Trade-offs
 * - **Pros:** Fast, simple, no I/O
 * - **Cons:** Data lost on restart, limited by heap size
 *
 * ## When to Prefer
 * - Caching
 * - Session storage
 * - Testing
 */
package com.systemdesign.kvstore.approach_01_memory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface KVStore<K, V> {
    fun get(key: K): V?
    fun put(key: K, value: V, ttlMs: Long? = null)
    fun remove(key: K): V?
    fun contains(key: K): Boolean
    fun size(): Int
    fun clear()
}

data class Entry<V>(
    val value: V,
    val expiresAt: Long?
)

class InMemoryKVStore<K, V>(
    private val cleanupIntervalMs: Long = 60000
) : KVStore<K, V> {

    private val store = ConcurrentHashMap<K, Entry<V>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        scheduler.scheduleAtFixedRate(
            { cleanup() },
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    override fun get(key: K): V? {
        val entry = store[key] ?: return null
        if (entry.expiresAt != null && System.currentTimeMillis() > entry.expiresAt) {
            store.remove(key)
            return null
        }
        return entry.value
    }

    override fun put(key: K, value: V, ttlMs: Long?) {
        val expiresAt = ttlMs?.let { System.currentTimeMillis() + it }
        store[key] = Entry(value, expiresAt)
    }

    override fun remove(key: K): V? {
        return store.remove(key)?.value
    }

    override fun contains(key: K): Boolean = get(key) != null

    override fun size(): Int = store.size

    override fun clear() = store.clear()

    private fun cleanup() {
        val now = System.currentTimeMillis()
        store.entries.removeIf { (_, entry) ->
            entry.expiresAt != null && now > entry.expiresAt
        }
    }

    fun shutdown() {
        scheduler.shutdown()
    }
}
