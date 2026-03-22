/**
 * # Approach 03: Sharded Key-Value Store
 *
 * ## Pattern Used
 * Consistent hashing for key distribution across shards.
 *
 * ## Trade-offs
 * - **Pros:** Better concurrency, horizontal scaling, load distribution
 * - **Cons:** More complex, cross-shard operations expensive
 *
 * ## When to Prefer
 * - High concurrency
 * - Large datasets
 * - Need for horizontal scaling
 */
package com.systemdesign.kvstore.approach_03_sharded

import com.systemdesign.kvstore.approach_01_memory.KVStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class Shard<K, V> {
    private val data = ConcurrentHashMap<K, V>()
    private val lock = ReentrantReadWriteLock()

    fun get(key: K): V? = lock.read { data[key] }

    fun put(key: K, value: V) = lock.write { data[key] = value }

    fun remove(key: K): V? = lock.write { data.remove(key) }

    fun contains(key: K): Boolean = lock.read { data.containsKey(key) }

    fun size(): Int = lock.read { data.size }

    fun clear() = lock.write { data.clear() }

    fun keys(): Set<K> = lock.read { data.keys.toSet() }
}

class ShardedKVStore<K, V>(
    private val numShards: Int = 16
) : KVStore<K, V> {

    private val shards = Array(numShards) { Shard<K, V>() }

    private fun getShardIndex(key: K): Int {
        val hash = key.hashCode()
        return ((hash % numShards) + numShards) % numShards
    }

    private fun getShard(key: K): Shard<K, V> = shards[getShardIndex(key)]

    override fun get(key: K): V? = getShard(key).get(key)

    override fun put(key: K, value: V, ttlMs: Long?) {
        getShard(key).put(key, value)
    }

    override fun remove(key: K): V? = getShard(key).remove(key)

    override fun contains(key: K): Boolean = getShard(key).contains(key)

    override fun size(): Int = shards.sumOf { it.size() }

    override fun clear() = shards.forEach { it.clear() }

    fun getShardStats(): List<Int> = shards.map { it.size() }
}

class ConsistentHashingStore<K, V>(
    private val numVirtualNodes: Int = 150
) : KVStore<K, V> {

    private val ring = sortedMapOf<Int, Shard<K, V>>()
    private val shards = mutableListOf<Shard<K, V>>()

    init {
        repeat(4) { addShard() }
    }

    fun addShard(): Int {
        val shard = Shard<K, V>()
        shards.add(shard)
        val shardIndex = shards.size - 1
        
        repeat(numVirtualNodes) { i ->
            val hash = hash("shard-$shardIndex-vnode-$i")
            ring[hash] = shard
        }
        
        return shardIndex
    }

    private fun hash(key: Any): Int {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toString().toByteArray())
        return ((digest[0].toInt() and 0xFF) shl 24) or
               ((digest[1].toInt() and 0xFF) shl 16) or
               ((digest[2].toInt() and 0xFF) shl 8) or
               (digest[3].toInt() and 0xFF)
    }

    private fun getShard(key: K): Shard<K, V> {
        if (ring.isEmpty()) throw IllegalStateException("No shards available")
        val hash = hash(key as Any)
        val tailMap = ring.tailMap(hash)
        return if (tailMap.isEmpty()) ring.values.first() else tailMap.values.first()
    }

    override fun get(key: K): V? = getShard(key).get(key)

    override fun put(key: K, value: V, ttlMs: Long?) = getShard(key).put(key, value)

    override fun remove(key: K): V? = getShard(key).remove(key)

    override fun contains(key: K): Boolean = getShard(key).contains(key)

    override fun size(): Int = shards.sumOf { it.size() }

    override fun clear() = shards.forEach { it.clear() }
}
