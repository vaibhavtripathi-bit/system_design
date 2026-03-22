/**
 * # Approach 01: Strategy Pattern Image Loader
 *
 * ## Pattern Used
 * Strategy pattern with pluggable components for fetching, caching, and transforming.
 * Each component can be swapped independently, enabling high configurability.
 *
 * ## How It Works
 * 1. ImageLoader is configured with injectable strategies:
 *    - Fetcher strategy: How to fetch images (HTTP, file, content provider)
 *    - Cache strategy: Memory cache implementation (LRU, LFU, etc.)
 *    - Disk cache strategy: Disk storage implementation
 *    - Transform strategy: How to apply transformations
 * 2. Loading follows: Memory → Disk → Network
 * 3. Request deduplication prevents duplicate network calls
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Highly configurable - each component swappable
 *   - Easy to test with mock strategies
 *   - Clear separation of concerns
 *   - Open for extension, closed for modification
 *
 * - **Cons:**
 *   - Many small classes/interfaces
 *   - Configuration can become complex
 *   - Runtime strategy selection has slight overhead
 *
 * ## When to Prefer This Approach
 * - When configurability is a primary requirement
 * - When different caching/fetching strategies are needed per use case
 * - For library development where users need customization
 * - When testing individual components in isolation
 *
 * ## Comparison with Other Approaches
 * - **vs Decorator (Approach 02):** Strategy swaps whole components; Decorator composes behaviors
 * - **vs Reactive (Approach 03):** Strategy is imperative; Reactive uses Flow-based composition
 */
package com.systemdesign.imageloader.approach_01_strategy_pattern

import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Strategy-based Image Loader implementation.
 */
class StrategyImageLoader(
    private val fetchers: List<ImageFetcher>,
    private val memoryCache: MemoryCache,
    private val diskCache: DiskCache,
    private val transformExecutor: TransformExecutor = DefaultTransformExecutor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ImageLoader {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<ImageResult>>()
    private val requestMutex = Mutex()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    override suspend fun load(request: ImageRequest): ImageResult {
        if (cancelledRequests.remove(request.cacheKey)) {
            return ImageResult.Error(CancellationException("Request was cancelled"))
        }

        memoryCache.get(request.cacheKey)?.let { cached ->
            return ImageResult.Success(cached.copy(source = ImageSource.MEMORY_CACHE))
        }

        return loadWithDeduplication(request)
    }

    private suspend fun loadWithDeduplication(request: ImageRequest): ImageResult {
        val key = request.cacheKey

        val existingDeferred = inFlightRequests[key]
        if (existingDeferred != null && existingDeferred.isActive) {
            return existingDeferred.await()
        }

        val deferred = scope.async {
            try {
                loadFromCacheOrNetwork(request)
            } finally {
                inFlightRequests.remove(key)
            }
        }

        requestMutex.withLock {
            val existing = inFlightRequests[key]
            if (existing != null && existing.isActive) {
                deferred.cancel()
                return existing.await()
            }
            inFlightRequests[key] = deferred
        }

        return deferred.await()
    }

    private suspend fun loadFromCacheOrNetwork(request: ImageRequest): ImageResult {
        if (request.cachePolicy != CachePolicy.MEMORY_ONLY && 
            request.cachePolicy != CachePolicy.NONE) {
            diskCache.get(request.cacheKey)?.let { bytes ->
                val data = ImageData(bytes, request.width, request.height, ImageSource.DISK_CACHE)
                val transformed = applyTransformations(data, request.transformations)
                cacheResult(request, transformed)
                return ImageResult.Success(transformed)
            }
        }

        return fetchFromNetwork(request)
    }

    private suspend fun fetchFromNetwork(request: ImageRequest): ImageResult {
        val fetcher = fetchers.find { it.canHandle(request.url) }
            ?: return ImageResult.Error(IllegalArgumentException("No fetcher for URL: ${request.url}"))

        return try {
            if (cancelledRequests.contains(request.cacheKey)) {
                return ImageResult.Error(CancellationException("Request was cancelled"))
            }

            val bytes = fetcher.fetch(request.url)
            val data = ImageData(bytes, request.width, request.height, ImageSource.NETWORK)
            val transformed = applyTransformations(data, request.transformations)
            
            cacheResult(request, transformed)
            ImageResult.Success(transformed)
        } catch (e: CancellationException) {
            ImageResult.Error(e)
        } catch (e: Exception) {
            ImageResult.Error(e)
        }
    }

    private fun applyTransformations(data: ImageData, transformations: List<Transformation>): ImageData {
        return transformExecutor.execute(data, transformations)
    }

    private suspend fun cacheResult(request: ImageRequest, data: ImageData) {
        when (request.cachePolicy) {
            CachePolicy.ALL -> {
                memoryCache.put(request.cacheKey, data)
                diskCache.put(request.cacheKey, data.bytes)
            }
            CachePolicy.MEMORY_ONLY -> {
                memoryCache.put(request.cacheKey, data)
            }
            CachePolicy.DISK_ONLY -> {
                diskCache.put(request.cacheKey, data.bytes)
            }
            CachePolicy.NONE -> { }
        }
    }

    override fun cancel(request: ImageRequest) {
        cancelledRequests.add(request.cacheKey)
        inFlightRequests[request.cacheKey]?.cancel()
    }

    override fun clearMemoryCache() {
        memoryCache.clear()
    }

    override fun clearDiskCache() {
        runBlocking { diskCache.clear() }
    }

    fun shutdown() {
        scope.cancel()
    }
}

/**
 * Transform executor strategy interface.
 */
interface TransformExecutor {
    fun execute(data: ImageData, transformations: List<Transformation>): ImageData
}

/**
 * Default sequential transform executor.
 */
class DefaultTransformExecutor : TransformExecutor {
    override fun execute(data: ImageData, transformations: List<Transformation>): ImageData {
        return transformations.fold(data) { current, transform ->
            transform.transform(current)
        }
    }
}

/**
 * LRU Memory Cache implementation using LinkedHashMap.
 */
class LruMemoryCache(
    private val maxSizeBytes: Long
) : MemoryCache {
    
    private val lock = Any()
    private var currentSize = 0L
    
    private val cache = object : LinkedHashMap<String, ImageData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageData>?): Boolean = false
    }

    override fun get(key: String): ImageData? = synchronized(lock) {
        cache[key]
    }

    override fun put(key: String, data: ImageData) = synchronized(lock) {
        val size = data.bytes.size.toLong()
        if (size > maxSizeBytes) return
        
        cache[key]?.let { old ->
            currentSize -= old.bytes.size
        }
        
        currentSize += size
        cache[key] = data
        
        trimToSize()
    }

    override fun remove(key: String) = synchronized(lock) {
        cache.remove(key)?.let { removed ->
            currentSize -= removed.bytes.size
        }
        Unit
    }

    override fun clear() = synchronized(lock) {
        cache.clear()
        currentSize = 0
    }

    override fun size(): Int = synchronized(lock) { cache.size }

    override fun maxSize(): Int = (maxSizeBytes / 1024).toInt()

    private fun trimToSize() {
        while (currentSize > maxSizeBytes && cache.isNotEmpty()) {
            val eldest = cache.entries.first()
            cache.remove(eldest.key)
            currentSize -= eldest.value.bytes.size
        }
    }
}

/**
 * In-memory disk cache simulation for testing.
 */
class InMemoryDiskCache : DiskCache {
    private val storage = ConcurrentHashMap<String, ByteArray>()

    override suspend fun get(key: String): ByteArray? = storage[key]

    override suspend fun put(key: String, data: ByteArray) {
        storage[key] = data
    }

    override suspend fun remove(key: String) {
        storage.remove(key)
    }

    override suspend fun clear() {
        storage.clear()
    }
}

/**
 * Mock fetcher for testing.
 */
class MockFetcher(
    private val responses: Map<String, ByteArray> = emptyMap(),
    private val delayMs: Long = 0
) : ImageFetcher {
    
    var fetchCount = 0
        private set
    
    override suspend fun fetch(url: String): ByteArray {
        fetchCount++
        if (delayMs > 0) delay(delayMs)
        return responses[url] ?: throw IllegalArgumentException("No response for $url")
    }

    override fun canHandle(url: String): Boolean = responses.containsKey(url)
}
