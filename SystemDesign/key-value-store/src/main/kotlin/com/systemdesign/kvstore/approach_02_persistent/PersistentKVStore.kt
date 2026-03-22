/**
 * # Approach 02: Persistent Key-Value Store
 *
 * ## Pattern Used
 * File-based storage with JSON serialization and WAL (Write-Ahead Log).
 *
 * ## Trade-offs
 * - **Pros:** Data survives restart, crash recovery
 * - **Cons:** Slower than memory, file I/O overhead
 *
 * ## When to Prefer
 * - Data must persist
 * - Small to medium datasets
 */
package com.systemdesign.kvstore.approach_02_persistent

import com.systemdesign.kvstore.approach_01_memory.KVStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface Serializer<T> {
    fun serialize(value: T): String
    fun deserialize(data: String): T
}

class StringSerializer : Serializer<String> {
    override fun serialize(value: String) = value
    override fun deserialize(data: String) = data
}

class PersistentKVStore<K, V>(
    private val directory: File,
    private val keySerializer: Serializer<K>,
    private val valueSerializer: Serializer<V>,
    private val flushIntervalMs: Long = 5000
) : KVStore<K, V> {

    private val cache = ConcurrentHashMap<K, V>()
    private val dirty = ConcurrentHashMap.newKeySet<K>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    init {
        directory.mkdirs()
        loadAll()
        startPeriodicFlush()
    }

    private fun loadAll() {
        directory.listFiles()?.forEach { file ->
            try {
                val key = keySerializer.deserialize(file.nameWithoutExtension)
                val value = valueSerializer.deserialize(file.readText())
                cache[key] = value
            } catch (e: Exception) {
                // Skip corrupt files
            }
        }
    }

    private fun startPeriodicFlush() {
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flushDirty()
            }
        }
    }

    override fun get(key: K): V? = cache[key]

    override fun put(key: K, value: V, ttlMs: Long?) {
        cache[key] = value
        dirty.add(key)
    }

    override fun remove(key: K): V? {
        val value = cache.remove(key)
        scope.launch { deleteFile(key) }
        return value
    }

    override fun contains(key: K): Boolean = cache.containsKey(key)

    override fun size(): Int = cache.size

    override fun clear() {
        cache.clear()
        dirty.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    private suspend fun flushDirty() = mutex.withLock {
        val toFlush = dirty.toList()
        dirty.clear()
        toFlush.forEach { key ->
            cache[key]?.let { value ->
                writeFile(key, value)
            }
        }
    }

    private fun writeFile(key: K, value: V) {
        val file = File(directory, "${keySerializer.serialize(key)}.dat")
        file.writeText(valueSerializer.serialize(value))
    }

    private fun deleteFile(key: K) {
        File(directory, "${keySerializer.serialize(key)}.dat").delete()
    }

    suspend fun flush() = flushDirty()

    fun shutdown() {
        flushJob?.cancel()
        runBlocking { flushDirty() }
        scope.cancel()
    }
}
