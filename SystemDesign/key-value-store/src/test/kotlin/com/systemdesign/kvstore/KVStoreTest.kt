package com.systemdesign.kvstore

import com.systemdesign.kvstore.approach_01_memory.*
import com.systemdesign.kvstore.approach_02_persistent.*
import com.systemdesign.kvstore.approach_03_sharded.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KVStoreTest {

    // In-Memory KV Store Tests
    @Test
    fun `memory - put and get`() {
        val store = InMemoryKVStore<String, String>()
        
        store.put("key1", "value1")
        
        assertEquals("value1", store.get("key1"))
        store.shutdown()
    }

    @Test
    fun `memory - returns null for missing key`() {
        val store = InMemoryKVStore<String, String>()
        
        assertNull(store.get("missing"))
        store.shutdown()
    }

    @Test
    fun `memory - remove returns value`() {
        val store = InMemoryKVStore<String, String>()
        store.put("key1", "value1")
        
        val removed = store.remove("key1")
        
        assertEquals("value1", removed)
        assertNull(store.get("key1"))
        store.shutdown()
    }

    @Test
    fun `memory - contains works`() {
        val store = InMemoryKVStore<String, String>()
        store.put("key1", "value1")
        
        assertTrue(store.contains("key1"))
        assertFalse(store.contains("key2"))
        store.shutdown()
    }

    @Test
    fun `memory - size tracks entries`() {
        val store = InMemoryKVStore<String, String>()
        
        assertEquals(0, store.size())
        store.put("key1", "value1")
        assertEquals(1, store.size())
        store.put("key2", "value2")
        assertEquals(2, store.size())
        store.shutdown()
    }

    @Test
    fun `memory - clear removes all`() {
        val store = InMemoryKVStore<String, String>()
        store.put("key1", "value1")
        store.put("key2", "value2")
        
        store.clear()
        
        assertEquals(0, store.size())
        store.shutdown()
    }

    @Test
    fun `memory - TTL expires entries`() {
        val store = InMemoryKVStore<String, String>()
        store.put("key1", "value1", ttlMs = 50)
        
        assertEquals("value1", store.get("key1"))
        Thread.sleep(100)
        assertNull(store.get("key1"))
        store.shutdown()
    }

    // Sharded KV Store Tests
    @Test
    fun `sharded - put and get`() {
        val store = ShardedKVStore<String, String>()
        
        store.put("key1", "value1")
        
        assertEquals("value1", store.get("key1"))
    }

    @Test
    fun `sharded - distributes across shards`() {
        val store = ShardedKVStore<String, String>(numShards = 4)
        
        repeat(100) { store.put("key$it", "value$it") }
        
        val stats = store.getShardStats()
        assertTrue(stats.all { it > 0 })
        assertEquals(100, stats.sum())
    }

    @Test
    fun `sharded - size aggregates all shards`() {
        val store = ShardedKVStore<String, String>()
        
        repeat(50) { store.put("key$it", "value$it") }
        
        assertEquals(50, store.size())
    }

    @Test
    fun `sharded - clear empties all shards`() {
        val store = ShardedKVStore<String, String>()
        repeat(50) { store.put("key$it", "value$it") }
        
        store.clear()
        
        assertEquals(0, store.size())
    }

    // Consistent Hashing Store Tests
    @Test
    fun `consistent - put and get`() {
        val store = ConsistentHashingStore<String, String>()
        
        store.put("key1", "value1")
        
        assertEquals("value1", store.get("key1"))
    }

    @Test
    fun `consistent - handles many keys`() {
        val store = ConsistentHashingStore<String, String>()
        
        repeat(1000) { store.put("key$it", "value$it") }
        
        assertEquals(1000, store.size())
        repeat(1000) { assertEquals("value$it", store.get("key$it")) }
    }

    // Persistent KV Store Tests
    private fun createPersistentStore(dir: java.io.File, flushIntervalMs: Long = 60_000) =
        PersistentKVStore(dir, StringSerializer(), StringSerializer(), flushIntervalMs)

    @Test
    fun `persistent - put and get`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store = createPersistentStore(dir)
            store.put("key1", "value1")
            assertEquals("value1", store.get("key1"))
            store.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent - persists across instances`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store1 = createPersistentStore(dir)
            store1.put("key1", "value1")
            store1.put("key2", "value2")
            store1.flush()
            store1.shutdown()

            val store2 = createPersistentStore(dir)
            assertEquals("value1", store2.get("key1"))
            assertEquals("value2", store2.get("key2"))
            store2.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent - remove deletes from disk`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store = createPersistentStore(dir)
            store.put("key1", "value1")
            store.flush()

            store.remove("key1")
            assertNull(store.get("key1"))
            delay(100)
            store.shutdown()

            val store2 = createPersistentStore(dir)
            assertNull(store2.get("key1"))
            store2.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent - clear removes all`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store = createPersistentStore(dir)
            store.put("key1", "value1")
            store.put("key2", "value2")
            store.flush()

            store.clear()

            assertEquals(0, store.size())
            assertNull(store.get("key1"))
            store.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent - size tracks entries`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store = createPersistentStore(dir)
            assertEquals(0, store.size())
            store.put("key1", "value1")
            assertEquals(1, store.size())
            store.put("key2", "value2")
            assertEquals(2, store.size())
            store.remove("key1")
            assertEquals(1, store.size())
            store.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent - contains works`() = runBlocking {
        val dir = Files.createTempDirectory("kvtest").toFile()
        try {
            val store = createPersistentStore(dir)
            store.put("key1", "value1")

            assertTrue(store.contains("key1"))
            assertFalse(store.contains("key2"))
            store.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }
}
