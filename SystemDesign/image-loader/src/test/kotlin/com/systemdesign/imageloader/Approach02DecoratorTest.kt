package com.systemdesign.imageloader

import com.systemdesign.imageloader.approach_02_decorator_pattern.*
import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.*

class Approach02DecoratorTest {

    private val testImageBytes = "test-image-data".toByteArray()
    private val testUrl = "https://example.com/image.png"

    @Test
    fun `basic network loader works`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = NetworkImageLoader(fetcher)
        
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertArrayEquals(testImageBytes, success.data.bytes)
        assertEquals(ImageSource.NETWORK, success.data.source)
    }

    @Test
    fun `memory cache decorator caches results`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val cache = DecoratorLruMemoryCache(1024 * 1024)
        val loader = MemoryCacheDecorator(NetworkImageLoader(fetcher), cache)
        
        val request = ImageRequest(url = testUrl)
        
        loader.load(request)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.MEMORY_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `disk cache decorator caches results`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val cache = DecoratorInMemoryDiskCache()
        val loader = DiskCacheDecorator(NetworkImageLoader(fetcher), cache)
        
        val request = ImageRequest(url = testUrl)
        
        loader.load(request)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(ImageSource.DISK_CACHE, success.data.source)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `transform decorator applies transformations`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = TransformDecorator(NetworkImageLoader(fetcher))
        
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
    fun `logging decorator logs events`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val logger = InMemoryLogger()
        val loader = LoggingDecorator(NetworkImageLoader(fetcher), logger)
        
        val request = ImageRequest(url = testUrl)
        loader.load(request)

        assertEquals(2, logger.entries.size)
        assertEquals("start", logger.entries[0].event)
        assertEquals("success", logger.entries[1].event)
        assertEquals(ImageSource.NETWORK, logger.entries[1].source)
    }

    @Test
    fun `deduplication decorator prevents duplicate requests`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes), delayMs = 100)
        val loader = DeduplicationDecorator(NetworkImageLoader(fetcher))
        
        val request = ImageRequest(url = testUrl)
        
        val results = (1..5).map {
            async { loader.load(request) }
        }.awaitAll()

        results.forEach { result ->
            assertTrue(result is ImageResult.Success)
        }
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `builder creates composed loader`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val logger = InMemoryLogger()
        
        val loader = ImageLoaderBuilder(fetcher)
            .memoryCache(DecoratorLruMemoryCache(1024 * 1024))
            .diskCache(DecoratorInMemoryDiskCache())
            .logger(logger)
            .build()
        
        val request = ImageRequest(url = testUrl)
        
        loader.load(request)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Success)
        assertEquals(1, fetcher.fetchCount)
        assertTrue(logger.entries.isNotEmpty())
    }

    @Test
    fun `respects cache policy NONE through decorators`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val loader = MemoryCacheDecorator(
            DiskCacheDecorator(
                NetworkImageLoader(fetcher),
                DecoratorInMemoryDiskCache()
            ),
            DecoratorLruMemoryCache(1024 * 1024)
        )
        
        val request = ImageRequest(url = testUrl, cachePolicy = CachePolicy.NONE)
        
        loader.load(request)
        loader.load(request)

        assertEquals(2, fetcher.fetchCount)
    }

    @Test
    fun `full decorator chain works correctly`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val memoryCache = DecoratorLruMemoryCache(1024 * 1024)
        val diskCache = DecoratorInMemoryDiskCache()
        
        val loader = DeduplicationDecorator(
            MemoryCacheDecorator(
                DiskCacheDecorator(
                    TransformDecorator(
                        NetworkImageLoader(fetcher)
                    ),
                    diskCache
                ),
                memoryCache
            )
        )
        
        val request = ImageRequest(
            url = testUrl,
            transformations = listOf(ResizeTransformation(100, 100))
        )
        
        val result1 = loader.load(request)
        assertTrue(result1 is ImageResult.Success)
        assertEquals(ImageSource.NETWORK, (result1 as ImageResult.Success).data.source)
        
        val result2 = loader.load(request)
        assertTrue(result2 is ImageResult.Success)
        assertEquals(ImageSource.MEMORY_CACHE, (result2 as ImageResult.Success).data.source)
        
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `logging decorator handles errors`() = runTest {
        val fetcher = DecoratorMockFetcher(emptyMap())
        val logger = InMemoryLogger()
        val loader = LoggingDecorator(NetworkImageLoader(fetcher), logger)
        
        val request = ImageRequest(url = testUrl)
        val result = loader.load(request)

        assertTrue(result is ImageResult.Error)
        assertEquals(2, logger.entries.size)
        assertEquals("error", logger.entries[1].event)
        assertNotNull(logger.entries[1].error)
    }

    @Test
    fun `cancel propagates through decorators`() = runTest {
        val fetcher = DecoratorMockFetcher(mapOf(testUrl to testImageBytes))
        val logger = InMemoryLogger()
        
        val loader = LoggingDecorator(
            DeduplicationDecorator(
                NetworkImageLoader(fetcher)
            ),
            logger
        )
        
        val request = ImageRequest(url = testUrl)
        loader.cancel(request)

        assertTrue(logger.entries.any { it.event == "cancel" })
    }
}
