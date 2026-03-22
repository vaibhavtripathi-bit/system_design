package com.systemdesign.imageloader

import com.systemdesign.imageloader.approach_01_strategy_pattern.*
import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.*

class Approach01StrategyTest {

    private lateinit var memoryCache: LruMemoryCache
    private lateinit var diskCache: InMemoryDiskCache
    private lateinit var fetcher: MockFetcher
    private lateinit var loader: StrategyImageLoader

    private val testImageBytes = "test-image-data".toByteArray()
    private val testUrl = "https://example.com/image.png"

    @BeforeEach
    fun setup() {
        memoryCache = LruMemoryCache(1024 * 1024)
        diskCache = InMemoryDiskCache()
        fetcher = MockFetcher(mapOf(testUrl to testImageBytes))
        loader = StrategyImageLoader(
            fetchers = listOf(fetcher),
            memoryCache = memoryCache,
            diskCache = diskCache
        )
    }

    @Test
    fun `loads image from network on first request`() = runTest {
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertArrayEquals(testImageBytes, success.data.bytes)
        assertEquals(ImageSource.NETWORK, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `returns from memory cache on second request`() = runTest {
        val request = ImageRequest(url = testUrl)
        
        loader.load(request)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.MEMORY_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `returns from disk cache when not in memory`() = runTest {
        val request = ImageRequest(url = testUrl)
        
        loader.load(request)
        loader.clearMemoryCache()
        
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.DISK_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `respects cache policy NONE`() = runTest {
        val request = ImageRequest(url = testUrl, cachePolicy = CachePolicy.NONE)
        
        loader.load(request)
        loader.load(request)

        assertEquals(2, fetcher.fetchCount)
    }

    @Test
    fun `respects cache policy MEMORY_ONLY`() = runTest {
        val request = ImageRequest(url = testUrl, cachePolicy = CachePolicy.MEMORY_ONLY)
        
        loader.load(request)
        loader.clearMemoryCache()
        
        val result = loader.load(request)
        
        assertTrue(result is ImageResult.Success)
        assertEquals(2, fetcher.fetchCount)
    }

    @Test
    fun `applies transformations`() = runTest {
        val request = ImageRequest(
            url = testUrl,
            transformations = listOf(ResizeTransformation(100, 100))
        )
        
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(100, success.data.width)
        assertEquals(100, success.data.height)
    }

    @Test
    fun `generates different cache keys for different transformations`() = runTest {
        val request1 = ImageRequest(url = testUrl)
        val request2 = ImageRequest(
            url = testUrl,
            transformations = listOf(ResizeTransformation(100, 100))
        )
        
        assertNotEquals(request1.cacheKey, request2.cacheKey)
    }

    @Test
    fun `returns error for unknown URL`() = runTest {
        val request = ImageRequest(url = "https://unknown.com/image.png")
        val result = loader.load(request)

        assertTrue(result is ImageResult.Error)
    }

    @Test
    fun `deduplicates concurrent requests`() = runTest {
        val slowFetcher = MockFetcher(mapOf(testUrl to testImageBytes), delayMs = 100)
        val slowLoader = StrategyImageLoader(
            fetchers = listOf(slowFetcher),
            memoryCache = memoryCache,
            diskCache = diskCache
        )
        
        val request = ImageRequest(url = testUrl)
        
        val results = (1..5).map {
            async { slowLoader.load(request) }
        }.awaitAll()

        results.forEach { result ->
            assertTrue(result is ImageResult.Success)
        }
        assertEquals(1, slowFetcher.fetchCount)
        
        slowLoader.shutdown()
    }

    @Test
    fun `memory cache evicts when full`() {
        val smallCache = LruMemoryCache(100)
        val data1 = ImageData("12345678901234567890".repeat(3).toByteArray(), 10, 10, ImageSource.NETWORK)
        val data2 = ImageData("abcdefghij".repeat(6).toByteArray(), 10, 10, ImageSource.NETWORK)
        
        smallCache.put("key1", data1)
        smallCache.put("key2", data2)
        
        assertNull(smallCache.get("key1"))
        assertNotNull(smallCache.get("key2"))
    }

    @Test
    fun `cancel prevents result`() = runTest {
        val slowFetcher = MockFetcher(mapOf(testUrl to testImageBytes), delayMs = 500)
        val slowLoader = StrategyImageLoader(
            fetchers = listOf(slowFetcher),
            memoryCache = memoryCache,
            diskCache = diskCache
        )
        
        val request = ImageRequest(url = testUrl)
        slowLoader.cancel(request)
        
        val result = slowLoader.load(request)
        assertTrue(result is ImageResult.Error)
        
        slowLoader.shutdown()
    }
}
