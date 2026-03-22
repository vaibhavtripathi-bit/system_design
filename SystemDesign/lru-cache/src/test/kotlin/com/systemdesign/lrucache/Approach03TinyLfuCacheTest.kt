package com.systemdesign.lrucache

import com.systemdesign.lrucache.approach_03_tinylfu_admission.TinyLfuCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach03TinyLfuCacheTest {

    private lateinit var cache: TinyLfuCache<String, Int>

    @BeforeEach
    fun setup() {
        cache = TinyLfuCache(100, windowRatio = 0.1)
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
    fun `respects capacity limit`() {
        repeat(200) { i ->
            cache.put("key$i", i)
        }
        
        assertTrue(cache.size() <= 100)
    }

    @Test
    fun `remove works correctly`() {
        cache.put("a", 1)
        cache.put("b", 2)
        
        assertEquals(1, cache.remove("a"))
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
    }

    @Test
    fun `clear empties the cache`() {
        repeat(50) { i ->
            cache.put("key$i", i)
        }
        
        cache.clear()
        
        assertEquals(0, cache.size())
        assertNull(cache.get("key0"))
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
    fun `frequent items have higher admission rate`() {
        repeat(50) { i ->
            cache.put("hot$i", i)
            repeat(10) { cache.get("hot$i") }
        }
        
        repeat(100) { i ->
            cache.put("cold$i", i)
        }
        
        var hotRetained = 0
        repeat(50) { i ->
            if (cache.get("hot$i") != null) hotRetained++
        }
        
        assertTrue(hotRetained > 30, "Expected most hot items to be retained, got $hotRetained")
    }

    @Test
    fun `stats track operations`() {
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
    fun `stats show segment distribution`() {
        repeat(50) { i ->
            cache.put("key$i", i)
        }
        
        val stats = cache.stats()
        assertEquals(50, stats.windowSize + stats.probationSize + stats.protectedSize)
    }

    @Test
    fun `constructor rejects invalid parameters`() {
        assertThrows<IllegalArgumentException> { TinyLfuCache<String, Int>(0) }
        assertThrows<IllegalArgumentException> { TinyLfuCache<String, Int>(-1) }
        assertThrows<IllegalArgumentException> { TinyLfuCache<String, Int>(100, -0.1) }
        assertThrows<IllegalArgumentException> { TinyLfuCache<String, Int>(100, 0.6) }
    }

    @Test
    fun `handles small cache size`() {
        val smallCache = TinyLfuCache<String, Int>(5)
        
        repeat(10) { i ->
            smallCache.put("key$i", i)
        }
        
        assertTrue(smallCache.size() <= 5)
    }

    @Test
    fun `thread safety under concurrent access`() {
        val threadSafeCache = TinyLfuCache<Int, Int>(100)
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1000)
        val errors = AtomicInteger(0)
        
        repeat(1000) { i ->
            executor.submit {
                try {
                    threadSafeCache.put(i % 150, i)
                    threadSafeCache.get(i % 150)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
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
    fun `admission policy filters one-hit wonders`() {
        repeat(90) { i ->
            cache.put("frequent$i", i)
            repeat(5) { cache.get("frequent$i") }
        }
        
        repeat(50) { i ->
            cache.put("oneshot$i", i)
        }
        
        val stats = cache.stats()
        assertTrue(stats.rejectionCount > 0, "Expected some rejections for one-hit wonders")
    }

    @Test
    fun `promotion from probation to protected`() {
        repeat(90) { i ->
            cache.put("key$i", i)
        }
        
        repeat(10) { i ->
            repeat(5) { cache.get("key$i") }
        }
        
        val stats = cache.stats()
        assertTrue(stats.protectedSize > 0, "Expected items to be promoted to protected segment")
    }

    @Test
    fun `hit rate improves with repeated access patterns`() {
        val workingSet = (0 until 50).map { "key$it" }
        
        repeat(5) {
            workingSet.forEach { key ->
                cache.put(key, key.hashCode())
            }
            workingSet.forEach { key ->
                cache.get(key)
            }
        }
        
        (100 until 200).forEach { i ->
            cache.put("noise$i", i)
        }
        
        var hits = 0
        workingSet.forEach { key ->
            if (cache.get(key) != null) hits++
        }
        
        assertTrue(hits > 30, "Expected good retention of working set, got $hits/50")
    }
}
