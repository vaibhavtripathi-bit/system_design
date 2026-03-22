/**
 * # Approach 01: LRU Cache using LinkedHashMap
 *
 * ## Pattern Used
 * Classic LRU (Least Recently Used) eviction using LinkedHashMap in access-order mode.
 * This is the standard, well-understood approach that provides O(1) get/put operations.
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Simple implementation with well-understood behavior
 *   - O(1) time complexity for both get and put
 *   - Leverages battle-tested LinkedHashMap from stdlib
 *   - Easy to reason about eviction order
 *
 * - **Cons:**
 *   - Not optimal for workloads with frequency-skewed access patterns
 *   - A single access promotes item to MRU position (scan resistance is poor)
 *   - Memory overhead per entry (doubly-linked list pointers)
 *
 * ## When to Prefer This Approach
 * - When access patterns are relatively uniform (no hot spots)
 * - When simplicity and maintainability are prioritized
 * - When the working set naturally fits within cache capacity
 * - Good baseline implementation before optimizing
 *
 * ## Comparison with Other Approaches
 * - **vs LFU (Approach 02):** LRU is simpler but doesn't account for frequency;
 *   LFU handles repeated accesses better but has cache pollution issues
 * - **vs TinyLFU (Approach 03):** TinyLFU combines benefits of both with admission
 *   policy, but adds complexity; LRU is preferred when that complexity isn't justified
 */
package com.systemdesign.lrucache.approach_01_lru_linkedhashmap

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe LRU Cache implementation using LinkedHashMap.
 *
 * @param K the type of keys
 * @param V the type of values
 * @param maxSize the maximum number of entries before eviction occurs
 * @param sizeCalculator optional function to calculate size of each entry (default: each entry = 1)
 */
class LruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeCalculator: (K, V) -> Int = { _, _ -> 1 }
) : Cache<K, V> {

    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    private val lock = ReentrantReadWriteLock()
    
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = false
    }
    
    private var currentSize = 0
    private var hitCount = 0L
    private var missCount = 0L
    private var putCount = 0L
    private var evictionCount = 0L

    override fun get(key: K): V? = lock.read {
        map[key]?.also { hitCount++ } ?: run { missCount++; null }
    }

    override fun put(key: K, value: V): V? = lock.write {
        putCount++
        val entrySize = sizeCalculator(key, value)
        require(entrySize <= maxSize) { 
            "Entry size ($entrySize) exceeds maxSize ($maxSize)" 
        }
        
        val previous = map.put(key, value)
        
        if (previous != null) {
            currentSize -= sizeCalculator(key, previous)
        }
        currentSize += entrySize
        
        trimToSize()
        previous
    }

    override fun remove(key: K): V? = lock.write {
        map.remove(key)?.also { value ->
            currentSize -= sizeCalculator(key, value)
        }
    }

    override fun clear() = lock.write {
        map.clear()
        currentSize = 0
    }

    override fun size(): Int = lock.read { map.size }

    override fun snapshot(): Map<K, V> = lock.read { 
        LinkedHashMap(map) 
    }

    private fun trimToSize() {
        while (currentSize > maxSize && map.isNotEmpty()) {
            val eldest = map.entries.first()
            map.remove(eldest.key)
            currentSize -= sizeCalculator(eldest.key, eldest.value)
            evictionCount++
        }
    }

    fun stats(): CacheStats = lock.read {
        CacheStats(
            hitCount = hitCount,
            missCount = missCount,
            putCount = putCount,
            evictionCount = evictionCount,
            size = map.size,
            maxSize = maxSize
        )
    }
}

/**
 * Cache interface defining the contract for all cache implementations.
 */
interface Cache<K : Any, V : Any> {
    fun get(key: K): V?
    fun put(key: K, value: V): V?
    fun remove(key: K): V?
    fun clear()
    fun size(): Int
    fun snapshot(): Map<K, V>
}

/**
 * Statistics for cache performance monitoring.
 */
data class CacheStats(
    val hitCount: Long,
    val missCount: Long,
    val putCount: Long,
    val evictionCount: Long,
    val size: Int,
    val maxSize: Int
) {
    val hitRate: Double
        get() = if (hitCount + missCount == 0L) 0.0 
                else hitCount.toDouble() / (hitCount + missCount)
}
