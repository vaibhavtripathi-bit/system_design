package com.systemdesign.imageloader

import com.systemdesign.imageloader.approach_03_reactive_pipeline.*
import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.*

class Approach03ReactiveTest {

    private val testImageBytes = "test-image-data".toByteArray()
    private val testUrl = "https://example.com/image.png"

    @Test
    fun `loads image from network`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request).first()

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertArrayEquals(testImageBytes, success.data.bytes)
        assertEquals(ImageSource.NETWORK, success.data.source)
    }

    @Test
    fun `caches in memory`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val memoryCache = ReactiveMemoryCache(1024 * 1024)
        val loader = FlowImageLoader(FlowImageLoaderConfig(
            fetcher = fetcher,
            memoryCache = memoryCache
        ))
        
        val request = ImageRequest(url = testUrl)
        
        loader.load(request).first()
        val result = loader.load(request).first()

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.MEMORY_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `caches on disk`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val diskCache = ReactiveInMemoryDiskCache()
        val loader = FlowImageLoader(FlowImageLoaderConfig(
            fetcher = fetcher,
            diskCache = diskCache
        ))
        
        val request = ImageRequest(url = testUrl)
        
        loader.load(request).first()
        val result = loader.load(request).first()

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.DISK_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `applies transformations`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(
            url = testUrl,
            transformations = listOf(ResizeTransformation(100, 100))
        )
        
        val result = loader.load(request).first()

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(100, success.data.width)
        assertEquals(100, success.data.height)
    }

    @Test
    fun `deduplicates concurrent requests`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes), delayMs = 100)
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        
        val results = (1..5).map {
            async { loader.load(request).first() }
        }.awaitAll()

        results.forEach { result ->
            assertTrue(result is ImageResult.Success)
        }
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `loadFirst extension works`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val result = loader.loadFirst(request)

        assertTrue(result is ImageResult.Success)
    }

    @Test
    fun `loadWithTimeout returns error on timeout`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes), delayMs = 500)
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val result = loader.loadWithTimeout(request, 100)

        assertTrue(result is ImageResult.Error)
        assertTrue((result as ImageResult.Error).exception.message?.contains("Timeout") == true)
    }

    @Test
    fun `respects cache policy NONE`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val memoryCache = ReactiveMemoryCache(1024 * 1024)
        val loader = FlowImageLoader(FlowImageLoaderConfig(
            fetcher = fetcher,
            memoryCache = memoryCache
        ))
        
        val request = ImageRequest(url = testUrl, cachePolicy = CachePolicy.NONE)
        
        loader.load(request).first()
        loader.load(request).first()

        assertEquals(2, fetcher.fetchCount)
    }

    @Test
    fun `multiple sequential loads work`() = runTest {
        val responses = mapOf(
            "https://example.com/1.png" to "image1".toByteArray(),
            "https://example.com/2.png" to "image2".toByteArray(),
            "https://example.com/3.png" to "image3".toByteArray()
        )
        val fetcher = ReactiveMockFetcher(responses)
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val results = responses.keys.map { url ->
            loader.load(ImageRequest(url = url)).first()
        }

        assertEquals(3, results.size)
        results.forEach { result ->
            assertTrue(result is ImageResult.Success)
        }
        assertEquals(3, fetcher.fetchCount)
    }

    @Test
    fun `filterSuccess operator works`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val data = loader.load(request)
            .let { flow -> ImageFlowOperators.run { flow.filterSuccess() } }
            .first()

        assertArrayEquals(testImageBytes, data.bytes)
    }

    @Test
    fun `mapData operator transforms data`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request)
            .let { flow -> 
                ImageFlowOperators.run { 
                    flow.mapData { it.copy(width = 200, height = 200) } 
                } 
            }
            .first()

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(200, success.data.width)
        assertEquals(200, success.data.height)
    }

    @Test
    fun `handles network errors`() = runTest {
        val fetcher = ReactiveMockFetcher(emptyMap())
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request).first()

        assertTrue(result is ImageResult.Error)
    }

    @Test
    fun `cancel prevents result`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes), delayMs = 500)
        val loader = FlowImageLoader(FlowImageLoaderConfig(fetcher = fetcher))
        
        val request = ImageRequest(url = testUrl)
        loader.cancel(request)
        
        val result = loader.load(request).first()
        assertTrue(result is ImageResult.Error)
    }

    @Test
    fun `clearCaches clears both caches`() = runTest {
        val fetcher = ReactiveMockFetcher(mapOf(testUrl to testImageBytes))
        val memoryCache = ReactiveMemoryCache(1024 * 1024)
        val diskCache = ReactiveInMemoryDiskCache()
        val loader = FlowImageLoader(FlowImageLoaderConfig(
            fetcher = fetcher,
            memoryCache = memoryCache,
            diskCache = diskCache
        ))
        
        val request = ImageRequest(url = testUrl)
        loader.load(request).first()
        
        assertEquals(1, memoryCache.size())
        
        loader.clearCaches()
        
        assertEquals(0, memoryCache.size())
    }
}
