package com.systemdesign.lrucache

import com.systemdesign.lrucache.approach_01_lru_linkedhashmap.LruCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach01LruCacheTest {

    private lateinit var cache: LruCache<String, Int>

    @BeforeEach
    fun setup() {
        cache = LruCache(3)
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `put and get work correctly`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        assertEquals(1, cache.get("a"))
        assertEquals(2, cache.get("b"))
    }

    @Test
    fun `put returns previous value`() {
        assertNull(cache.put("a", 1))
        assertEquals(1, cache.put("a", 2))
        assertEquals(2, cache.get("a"))
    }

    @Test
    fun `evicts least recently used when capacity exceeded`() {
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.put("d", 4)
        
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `access updates recency`() {
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        
        cache.get("a")
        cache.put("d", 4)
        
        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `remove works correctly`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        assertEquals(1, cache.remove("a"))
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `clear empties the cache`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        cache.clear()
        
        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun `snapshot returns copy of entries`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        val snapshot = cache.snapshot()
        cache.put("c", 3)
        
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.containsKey("a"))
        assertFalse(snapshot.containsKey("c"))
    }

    @Test
    fun `stats track hits and misses`() {
        cache.put("a", 1)
        cache.get("a")
        cache.get("a")
        cache.get("missing")
        
        val stats = cache.stats()
        assertEquals(2, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(1, stats.putCount)
        assertEquals(2.0 / 3.0, stats.hitRate, 0.01)
    }

    @Test
    fun `stats track evictions`() {
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.put("d", 4)
        cache.put("e", 5)
        
        val stats = cache.stats()
        assertEquals(2, stats.evictionCount)
    }

    @Test
    fun `constructor rejects non-positive maxSize`() {
        assertThrows<IllegalArgumentException> { LruCache<String, Int>(0) }
        assertThrows<IllegalArgumentException> { LruCache<String, Int>(-1) }
    }

    @Test
    fun `custom size calculator works`() {
        val sizedCache = LruCache<String, String>(10) { _, v -> v.length }
        
        sizedCache.put("a", "hello")
        sizedCache.put("b", "world")
        assertEquals("hello", sizedCache.get("a"))
        assertEquals("world", sizedCache.get("b"))
        
        sizedCache.put("c", "test")
        assertNull(sizedCache.get("a"))
    }

    @Test
    fun `rejects entry larger than maxSize`() {
        val sizedCache = LruCache<String, String>(5) { _, v -> v.length }
        
        assertThrows<IllegalArgumentException> {
            sizedCache.put("a", "toolong")
        }
    }

    @Test
    fun `thread safety under concurrent access`() {
        val threadSafeCache = LruCache<Int, Int>(100)
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1000)
        val errors = AtomicInteger(0)
        
        repeat(1000) { i ->
            executor.submit {
                try {
                    threadSafeCache.put(i % 50, i)
                    threadSafeCache.get(i % 50)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        
        latch.await()
        executor.shutdown()
        
        assertEquals(0, errors.get())
        assertTrue(threadSafeCache.size() <= 100)
    }
}
