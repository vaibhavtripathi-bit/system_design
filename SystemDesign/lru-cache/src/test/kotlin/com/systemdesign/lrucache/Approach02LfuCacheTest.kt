package com.systemdesign.lrucache

import com.systemdesign.lrucache.approach_02_lfu_frequency.LfuCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach02LfuCacheTest {

    private lateinit var cache: LfuCache<String, Int>

    @BeforeEach
    fun setup() {
        cache = LfuCache(3)
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
    fun `evicts least frequently used when capacity exceeded`() {
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        
        cache.get("a")
        cache.get("a")
        cache.get("b")
        
        cache.put("d", 4)
        
        assertNull(cache.get("c"))
        assertEquals(1, cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `evicts LRU among same frequency items`() {
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        
        cache.put("d", 4)
        
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
    }

    @Test
    fun `access increments frequency`() {
        cache.put("a", 1)
        assertEquals(1, cache.getFrequency("a"))
        
        cache.get("a")
        assertEquals(2, cache.getFrequency("a"))
        
        cache.get("a")
        assertEquals(3, cache.getFrequency("a"))
    }

    @Test
    fun `put on existing key increments frequency`() {
        cache.put("a", 1)
        cache.put("a", 2)
        
        assertEquals(2, cache.getFrequency("a"))
    }

    @Test
    fun `remove works correctly`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        assertEquals(1, cache.remove("a"))
        assertNull(cache.get("a"))
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
        
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot["a"])
        assertEquals(2, snapshot["b"])
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
    }

    @Test
    fun `frequency-based eviction scenario`() {
        cache.put("hot", 1)
        repeat(10) { cache.get("hot") }
        
        cache.put("warm", 2)
        repeat(5) { cache.get("warm") }
        
        cache.put("cold1", 3)
        cache.put("cold2", 4)
        
        assertNull(cache.get("cold1"))
        assertEquals(1, cache.get("hot"))
        assertEquals(2, cache.get("warm"))
        assertEquals(4, cache.get("cold2"))
    }

    @Test
    fun `constructor rejects non-positive maxSize`() {
        assertThrows<IllegalArgumentException> { LfuCache<String, Int>(0) }
        assertThrows<IllegalArgumentException> { LfuCache<String, Int>(-1) }
    }

    @Test
    fun `thread safety under concurrent access`() {
        val threadSafeCache = LfuCache<Int, Int>(100)
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

    @Test
    fun `handles single capacity cache`() {
        val singleCache = LfuCache<String, Int>(1)
        
        singleCache.put("a", 1)
        assertEquals(1, singleCache.get("a"))
        
        singleCache.put("b", 2)
        assertNull(singleCache.get("a"))
        assertEquals(2, singleCache.get("b"))
    }
}
