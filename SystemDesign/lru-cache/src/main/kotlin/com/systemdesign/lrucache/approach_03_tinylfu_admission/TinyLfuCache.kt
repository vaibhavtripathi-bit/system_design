/**
 * # Approach 03: TinyLFU Cache with Admission Policy
 *
 * ## Pattern Used
 * TinyLFU (Window Tiny LFU) combines a small admission window (LRU) with a main cache
 * protected by a frequency sketch. New items must "win" against potential eviction victims
 * based on estimated frequency to be admitted to the main cache.
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Near-optimal hit rates for most workloads
 *   - Combines recency (scan resistance via window) with frequency
 *   - Space-efficient frequency estimation using Count-Min Sketch
 *   - Decays frequency over time, adapting to pattern changes
 *   - Resists cache pollution from one-hit wonders
 *
 * - **Cons:**
 *   - Most complex implementation of the three approaches
 *   - Frequency estimates have false positives (may overcount)
 *   - Requires tuning of window size ratio
 *   - Higher computational overhead per operation
 *
 * ## When to Prefer This Approach
 * - High-performance caches where hit rate is critical
 * - Workloads with mixed access patterns (both recency and frequency matter)
 * - When cache pollution from sequential scans is a concern
 * - Production-grade caches (similar to Caffeine in Java)
 *
 * ## Comparison with Other Approaches
 * - **vs LRU (Approach 01):** TinyLFU provides better hit rates for most workloads
 *   but with more complexity; use LRU when simplicity is prioritized
 * - **vs LFU (Approach 02):** TinyLFU avoids cache pollution and adapts to
 *   changing patterns; LFU is simpler but suffers from historical frequency issues
 *
 * ## Design Notes
 * - Window (1% of capacity): Pure LRU for new entries, provides scan resistance
 * - Main Cache (99% of capacity): Segmented LRU, protected by frequency sketch
 * - Admission: Window victims compete with main cache victims based on frequency
 */
package com.systemdesign.lrucache.approach_03_tinylfu_admission

import com.systemdesign.lrucache.approach_01_lru_linkedhashmap.Cache
import com.systemdesign.lrucache.approach_01_lru_linkedhashmap.CacheStats
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln
import kotlin.math.min

/**
 * TinyLFU Cache implementation with admission policy.
 * Provides near-optimal hit rates by combining recency and frequency.
 */
class TinyLfuCache<K : Any, V : Any>(
    private val maxSize: Int,
    windowRatio: Double = 0.01
) : Cache<K, V> {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(windowRatio in 0.0..0.5) { "windowRatio must be between 0 and 0.5" }
    }

    private val lock = ReentrantReadWriteLock()
    
    private val windowSize = maxOf(1, (maxSize * windowRatio).toInt())
    private val mainSize = maxSize - windowSize
    
    private val window = LruSegment<K, V>(windowSize)
    private val probation = LruSegment<K, V>(mainSize / 5)
    private val protected = LruSegment<K, V>(mainSize - mainSize / 5)
    
    private val frequencySketch = CountMinSketch(maxSize)
    
    private val keyToSegment = HashMap<K, Segment>()
    
    private var hitCount = 0L
    private var missCount = 0L
    private var putCount = 0L
    private var evictionCount = 0L
    private var admissionCount = 0L
    private var rejectionCount = 0L

    enum class Segment { WINDOW, PROBATION, PROTECTED }

    override fun get(key: K): V? = lock.write {
        frequencySketch.increment(key.hashCode())
        
        val segment = keyToSegment[key]
        when (segment) {
            Segment.WINDOW -> {
                hitCount++
                window.get(key)
            }
            Segment.PROBATION -> {
                hitCount++
                val value = probation.remove(key)!!
                promoteToProtected(key, value)
                value
            }
            Segment.PROTECTED -> {
                hitCount++
                protected.get(key)
            }
            null -> {
                missCount++
                null
            }
        }
    }

    override fun put(key: K, value: V): V? = lock.write {
        putCount++
        frequencySketch.increment(key.hashCode())
        
        val existingSegment = keyToSegment[key]
        if (existingSegment != null) {
            return updateExisting(key, value, existingSegment)
        }
        
        insertNew(key, value)
        null
    }

    private fun updateExisting(key: K, value: V, segment: Segment): V? {
        return when (segment) {
            Segment.WINDOW -> window.put(key, value)
            Segment.PROBATION -> {
                val old = probation.remove(key)
                promoteToProtected(key, value)
                old
            }
            Segment.PROTECTED -> protected.put(key, value)
        }
    }

    private fun insertNew(key: K, value: V) {
        if (window.size() >= windowSize) {
            val victim = window.evict()
            if (victim != null) {
                keyToSegment.remove(victim.first)
                tryAdmitToMain(victim.first, victim.second)
            }
        }
        
        window.put(key, value)
        keyToSegment[key] = Segment.WINDOW
    }

    private fun tryAdmitToMain(candidateKey: K, candidateValue: V) {
        if (probation.size() + protected.size() < mainSize) {
            probation.put(candidateKey, candidateValue)
            keyToSegment[candidateKey] = Segment.PROBATION
            admissionCount++
            return
        }
        
        val victim = probation.peekLru() ?: return
        val candidateFreq = frequencySketch.estimate(candidateKey.hashCode())
        val victimFreq = frequencySketch.estimate(victim.first.hashCode())
        
        if (candidateFreq > victimFreq) {
            probation.evict()?.let { evicted ->
                keyToSegment.remove(evicted.first)
                evictionCount++
            }
            probation.put(candidateKey, candidateValue)
            keyToSegment[candidateKey] = Segment.PROBATION
            admissionCount++
        } else {
            evictionCount++
            rejectionCount++
        }
    }

    private fun promoteToProtected(key: K, value: V) {
        if (protected.size() >= protected.capacity) {
            val demoted = protected.evict()
            if (demoted != null) {
                if (probation.size() >= probation.capacity) {
                    probation.evict()?.let { evicted ->
                        keyToSegment.remove(evicted.first)
                        evictionCount++
                    }
                }
                probation.put(demoted.first, demoted.second)
                keyToSegment[demoted.first] = Segment.PROBATION
            }
        }
        protected.put(key, value)
        keyToSegment[key] = Segment.PROTECTED
    }

    override fun remove(key: K): V? = lock.write {
        val segment = keyToSegment.remove(key) ?: return null
        when (segment) {
            Segment.WINDOW -> window.remove(key)
            Segment.PROBATION -> probation.remove(key)
            Segment.PROTECTED -> protected.remove(key)
        }
    }

    override fun clear() = lock.write {
        window.clear()
        probation.clear()
        protected.clear()
        keyToSegment.clear()
        frequencySketch.reset()
    }

    override fun size(): Int = lock.read {
        window.size() + probation.size() + protected.size()
    }

    override fun snapshot(): Map<K, V> = lock.read {
        val result = mutableMapOf<K, V>()
        result.putAll(window.snapshot())
        result.putAll(probation.snapshot())
        result.putAll(protected.snapshot())
        result
    }

    fun stats(): TinyLfuStats = lock.read {
        TinyLfuStats(
            hitCount = hitCount,
            missCount = missCount,
            putCount = putCount,
            evictionCount = evictionCount,
            admissionCount = admissionCount,
            rejectionCount = rejectionCount,
            windowSize = window.size(),
            probationSize = probation.size(),
            protectedSize = protected.size(),
            maxSize = maxSize
        )
    }
}

/**
 * Simple LRU segment using LinkedHashMap.
 */
internal class LruSegment<K : Any, V : Any>(val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = false
    }
    
    fun get(key: K): V? = map[key]
    
    fun put(key: K, value: V): V? = map.put(key, value)
    
    fun remove(key: K): V? = map.remove(key)
    
    fun evict(): Pair<K, V>? {
        val eldest = map.entries.firstOrNull() ?: return null
        map.remove(eldest.key)
        return eldest.key to eldest.value
    }
    
    fun peekLru(): Pair<K, V>? {
        val eldest = map.entries.firstOrNull() ?: return null
        return eldest.key to eldest.value
    }
    
    fun size(): Int = map.size
    
    fun clear() = map.clear()
    
    fun snapshot(): Map<K, V> = LinkedHashMap(map)
}

/**
 * Count-Min Sketch for space-efficient frequency estimation.
 * Uses multiple hash functions to estimate item frequency with bounded error.
 */
internal class CountMinSketch(expectedSize: Int) {
    private val depth = 4
    private val width = nextPowerOfTwo(expectedSize)
    private val table = Array(depth) { IntArray(width) }
    private val seeds = intArrayOf(0xc3a5c85c.toInt(), 0x7f4a7c15, 0x7a0e2bd3.toInt(), 0x6f1f2b9c.toInt())
    
    private var size = 0
    private val resetThreshold = expectedSize * 10
    
    fun increment(hash: Int) {
        for (i in 0 until depth) {
            val index = indexOf(hash, i)
            table[i][index] = min(table[i][index] + 1, 15)
        }
        if (++size >= resetThreshold) {
            reset()
        }
    }
    
    fun estimate(hash: Int): Int {
        var min = Int.MAX_VALUE
        for (i in 0 until depth) {
            min = min(min, table[i][indexOf(hash, i)])
        }
        return min
    }
    
    fun reset() {
        for (i in 0 until depth) {
            for (j in table[i].indices) {
                table[i][j] = table[i][j] shr 1
            }
        }
        size = size shr 1
    }
    
    private fun indexOf(hash: Int, depth: Int): Int {
        val h = hash xor seeds[depth]
        return (h and (width - 1))
    }
    
    private fun nextPowerOfTwo(n: Int): Int {
        var x = n - 1
        x = x or (x shr 1)
        x = x or (x shr 2)
        x = x or (x shr 4)
        x = x or (x shr 8)
        x = x or (x shr 16)
        return x + 1
    }
}

/**
 * Extended statistics for TinyLFU cache.
 */
data class TinyLfuStats(
    val hitCount: Long,
    val missCount: Long,
    val putCount: Long,
    val evictionCount: Long,
    val admissionCount: Long,
    val rejectionCount: Long,
    val windowSize: Int,
    val probationSize: Int,
    val protectedSize: Int,
    val maxSize: Int
) {
    val hitRate: Double
        get() = if (hitCount + missCount == 0L) 0.0 
                else hitCount.toDouble() / (hitCount + missCount)
    
    val admissionRate: Double
        get() = if (admissionCount + rejectionCount == 0L) 0.0
                else admissionCount.toDouble() / (admissionCount + rejectionCount)
}
