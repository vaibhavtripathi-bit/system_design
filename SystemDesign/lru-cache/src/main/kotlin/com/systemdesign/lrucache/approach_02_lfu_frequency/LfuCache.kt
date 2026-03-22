/**
 * # Approach 02: LFU Cache with Frequency Tracking
 *
 * ## Pattern Used
 * LFU (Least Frequently Used) eviction with O(1) operations using frequency buckets.
 * Uses a combination of HashMap and doubly-linked list per frequency level.
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Better hit rate for workloads with frequency-skewed access patterns
 *   - Keeps hot items in cache longer than LRU
 *   - O(1) time complexity for get/put operations
 *
 * - **Cons:**
 *   - More complex implementation than LRU
 *   - Cache pollution: old items with high historical frequency block new items
 *   - Doesn't adapt well to changing access patterns (frequency accumulates)
 *   - Higher memory overhead (frequency tracking + linked lists)
 *
 * ## When to Prefer This Approach
 * - When access patterns have clear hot spots (e.g., popular content)
 * - When items that are accessed frequently should stay cached longer
 * - When the hot set is relatively stable over time
 *
 * ## Comparison with Other Approaches
 * - **vs LRU (Approach 01):** LFU better handles hot items but is more complex
 *   and can suffer from cache pollution; LRU adapts better to pattern changes
 * - **vs TinyLFU (Approach 03):** TinyLFU uses a windowed frequency with admission
 *   policy to avoid pollution while still benefiting from frequency tracking
 */
package com.systemdesign.lrucache.approach_02_lfu_frequency

import com.systemdesign.lrucache.approach_01_lru_linkedhashmap.Cache
import com.systemdesign.lrucache.approach_01_lru_linkedhashmap.CacheStats
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe LFU Cache implementation with O(1) operations.
 * Uses frequency buckets stored in a linked list for efficient minimum tracking.
 */
class LfuCache<K : Any, V : Any>(
    private val maxSize: Int
) : Cache<K, V> {

    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    private val lock = ReentrantReadWriteLock()
    
    private val keyToNode = HashMap<K, Node<K, V>>()
    private val freqToList = HashMap<Int, FrequencyList<K, V>>()
    private var minFrequency = 0
    
    private var hitCount = 0L
    private var missCount = 0L
    private var putCount = 0L
    private var evictionCount = 0L

    override fun get(key: K): V? = lock.write {
        val node = keyToNode[key]
        if (node == null) {
            missCount++
            return null
        }
        hitCount++
        updateFrequency(node)
        node.value
    }

    override fun put(key: K, value: V): V? = lock.write {
        putCount++
        
        val existingNode = keyToNode[key]
        if (existingNode != null) {
            val oldValue = existingNode.value
            existingNode.value = value
            updateFrequency(existingNode)
            return oldValue
        }
        
        if (keyToNode.size >= maxSize) {
            evictLFU()
        }
        
        val newNode = Node(key, value, frequency = 1)
        keyToNode[key] = newNode
        
        freqToList.getOrPut(1) { FrequencyList() }.addFirst(newNode)
        minFrequency = 1
        
        null
    }

    override fun remove(key: K): V? = lock.write {
        val node = keyToNode.remove(key) ?: return null
        removeFromFrequencyList(node)
        node.value
    }

    override fun clear() = lock.write {
        keyToNode.clear()
        freqToList.clear()
        minFrequency = 0
    }

    override fun size(): Int = lock.read { keyToNode.size }

    override fun snapshot(): Map<K, V> = lock.read {
        keyToNode.mapValues { it.value.value }
    }

    private fun updateFrequency(node: Node<K, V>) {
        val oldFreq = node.frequency
        removeFromFrequencyList(node)
        
        if (freqToList[oldFreq]?.isEmpty() == true) {
            freqToList.remove(oldFreq)
            if (minFrequency == oldFreq) {
                minFrequency = oldFreq + 1
            }
        }
        
        node.frequency = oldFreq + 1
        freqToList.getOrPut(node.frequency) { FrequencyList() }.addFirst(node)
    }

    private fun removeFromFrequencyList(node: Node<K, V>) {
        freqToList[node.frequency]?.remove(node)
    }

    private fun evictLFU() {
        val minList = freqToList[minFrequency] ?: return
        val victim = minList.removeLast() ?: return
        keyToNode.remove(victim.key)
        evictionCount++
        
        if (minList.isEmpty()) {
            freqToList.remove(minFrequency)
        }
    }

    fun stats(): CacheStats = lock.read {
        CacheStats(
            hitCount = hitCount,
            missCount = missCount,
            putCount = putCount,
            evictionCount = evictionCount,
            size = keyToNode.size,
            maxSize = maxSize
        )
    }

    fun getFrequency(key: K): Int? = lock.read {
        keyToNode[key]?.frequency
    }
}

/**
 * Node in the LFU cache, part of a doubly-linked list.
 */
internal class Node<K, V>(
    val key: K,
    var value: V,
    var frequency: Int = 1,
    var prev: Node<K, V>? = null,
    var next: Node<K, V>? = null
)

/**
 * Doubly-linked list for a frequency bucket.
 * Head is MRU, tail is LRU within the same frequency.
 */
internal class FrequencyList<K, V> {
    private val head = Node<K, V>(null as K, null as V)
    private val tail = Node<K, V>(null as K, null as V)
    
    init {
        head.next = tail
        tail.prev = head
    }
    
    fun addFirst(node: Node<K, V>) {
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }
    
    fun remove(node: Node<K, V>) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
        node.prev = null
        node.next = null
    }
    
    fun removeLast(): Node<K, V>? {
        val last = tail.prev
        if (last === head) return null
        remove(last!!)
        return last
    }
    
    fun isEmpty(): Boolean = head.next === tail
}
