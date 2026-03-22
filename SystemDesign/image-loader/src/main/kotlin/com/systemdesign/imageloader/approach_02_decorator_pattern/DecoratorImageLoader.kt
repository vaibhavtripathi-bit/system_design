/**
 * # Approach 02: Decorator Pattern Image Loader
 *
 * ## Pattern Used
 * Decorator pattern with composable wrappers for caching, transformation, and logging.
 * Each decorator wraps another loader, adding behavior transparently.
 *
 * ## How It Works
 * 1. Base loader fetches from network
 * 2. Decorators wrap the base (or other decorators):
 *    - MemoryCacheDecorator: Adds memory caching
 *    - DiskCacheDecorator: Adds disk caching
 *    - TransformDecorator: Adds transformation support
 *    - LoggingDecorator: Adds logging/monitoring
 * 3. Decorators can be composed in any order
 * 4. Chain: Memory → Disk → Transform → Network
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Flexible composition of behaviors
 *   - Easy to add new decorators without modifying existing code
 *   - Each decorator has single responsibility
 *   - Runtime composition possible
 *
 * - **Cons:**
 *   - Deep nesting can be hard to debug
 *   - Order of decorators matters
 *   - Each decorator adds method call overhead
 *   - Configuration is less explicit than Strategy
 *
 * ## When to Prefer This Approach
 * - When behaviors need to be composed dynamically
 * - When extending functionality without subclassing
 * - When different combinations of features are needed
 * - For middleware-style processing pipelines
 *
 * ## Comparison with Other Approaches
 * - **vs Strategy (Approach 01):** Decorator composes behaviors; Strategy swaps implementations
 * - **vs Reactive (Approach 03):** Decorator is imperative wrapping; Reactive uses streams
 */
package com.systemdesign.imageloader.approach_02_decorator_pattern

import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Base interface for decoratable image loader.
 */
interface DecoratableImageLoader {
    suspend fun load(request: ImageRequest): ImageResult
    fun cancel(request: ImageRequest)
}

/**
 * Base network fetcher - the core loader that actually fetches images.
 */
class NetworkImageLoader(
    private val fetcher: ImageFetcher
) : DecoratableImageLoader {

    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    override suspend fun load(request: ImageRequest): ImageResult {
        if (cancelledRequests.remove(request.cacheKey)) {
            return ImageResult.Error(CancellationException("Cancelled"))
        }

        return try {
            val bytes = fetcher.fetch(request.url)
            val data = ImageData(bytes, request.width, request.height, ImageSource.NETWORK)
            ImageResult.Success(data)
        } catch (e: Exception) {
            ImageResult.Error(e)
        }
    }

    override fun cancel(request: ImageRequest) {
        cancelledRequests.add(request.cacheKey)
    }
}

/**
 * Memory cache decorator - adds memory caching layer.
 */
class MemoryCacheDecorator(
    private val delegate: DecoratableImageLoader,
    private val cache: MemoryCache
) : DecoratableImageLoader {

    override suspend fun load(request: ImageRequest): ImageResult {
        if (request.cachePolicy == CachePolicy.NONE || 
            request.cachePolicy == CachePolicy.DISK_ONLY) {
            return delegate.load(request)
        }

        cache.get(request.cacheKey)?.let { cached ->
            return ImageResult.Success(cached.copy(source = ImageSource.MEMORY_CACHE))
        }

        val result = delegate.load(request)
        
        if (result is ImageResult.Success) {
            cache.put(request.cacheKey, result.data)
        }
        
        return result
    }

    override fun cancel(request: ImageRequest) {
        delegate.cancel(request)
    }

    fun clearCache() {
        cache.clear()
    }
}

/**
 * Disk cache decorator - adds disk caching layer.
 */
class DiskCacheDecorator(
    private val delegate: DecoratableImageLoader,
    private val cache: DiskCache
) : DecoratableImageLoader {

    override suspend fun load(request: ImageRequest): ImageResult {
        if (request.cachePolicy == CachePolicy.NONE || 
            request.cachePolicy == CachePolicy.MEMORY_ONLY) {
            return delegate.load(request)
        }

        cache.get(request.cacheKey)?.let { bytes ->
            val data = ImageData(bytes, request.width, request.height, ImageSource.DISK_CACHE)
            return ImageResult.Success(data)
        }

        val result = delegate.load(request)
        
        if (result is ImageResult.Success) {
            cache.put(request.cacheKey, result.data.bytes)
        }
        
        return result
    }

    override fun cancel(request: ImageRequest) {
        delegate.cancel(request)
    }

    suspend fun clearCache() {
        cache.clear()
    }
}

/**
 * Transform decorator - applies transformations to loaded images.
 */
class TransformDecorator(
    private val delegate: DecoratableImageLoader
) : DecoratableImageLoader {

    override suspend fun load(request: ImageRequest): ImageResult {
        val result = delegate.load(request)
        
        if (result is ImageResult.Success && request.transformations.isNotEmpty()) {
            val transformed = request.transformations.fold(result.data) { data, transform ->
                transform.transform(data)
            }
            return ImageResult.Success(transformed)
        }
        
        return result
    }

    override fun cancel(request: ImageRequest) {
        delegate.cancel(request)
    }
}

/**
 * Deduplication decorator - prevents duplicate in-flight requests.
 */
class DeduplicationDecorator(
    private val delegate: DecoratableImageLoader,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DecoratableImageLoader {

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<ImageResult>>()
    private val mutex = Mutex()

    override suspend fun load(request: ImageRequest): ImageResult {
        val key = request.cacheKey

        inFlightRequests[key]?.let { existing ->
            if (existing.isActive) {
                return existing.await()
            }
        }

        val deferred = scope.async {
            try {
                delegate.load(request)
            } finally {
                inFlightRequests.remove(key)
            }
        }

        mutex.withLock {
            val existing = inFlightRequests[key]
            if (existing != null && existing.isActive) {
                deferred.cancel()
                return existing.await()
            }
            inFlightRequests[key] = deferred
        }

        return deferred.await()
    }

    override fun cancel(request: ImageRequest) {
        inFlightRequests[request.cacheKey]?.cancel()
        delegate.cancel(request)
    }
}

/**
 * Logging decorator - adds logging for debugging/monitoring.
 */
class LoggingDecorator(
    private val delegate: DecoratableImageLoader,
    private val logger: ImageLoaderLogger = ConsoleLogger()
) : DecoratableImageLoader {

    override suspend fun load(request: ImageRequest): ImageResult {
        val startTime = System.currentTimeMillis()
        logger.onLoadStart(request)

        val result = delegate.load(request)
        
        val duration = System.currentTimeMillis() - startTime
        when (result) {
            is ImageResult.Success -> logger.onLoadSuccess(request, result.data.source, duration)
            is ImageResult.Error -> logger.onLoadError(request, result.exception, duration)
            is ImageResult.Progress -> { }
        }
        
        return result
    }

    override fun cancel(request: ImageRequest) {
        logger.onCancel(request)
        delegate.cancel(request)
    }
}

/**
 * Logger interface for the logging decorator.
 */
interface ImageLoaderLogger {
    fun onLoadStart(request: ImageRequest)
    fun onLoadSuccess(request: ImageRequest, source: ImageSource, durationMs: Long)
    fun onLoadError(request: ImageRequest, error: Throwable, durationMs: Long)
    fun onCancel(request: ImageRequest)
}

/**
 * Console logger implementation.
 */
class ConsoleLogger : ImageLoaderLogger {
    override fun onLoadStart(request: ImageRequest) {
        println("[ImageLoader] Loading: ${request.url}")
    }

    override fun onLoadSuccess(request: ImageRequest, source: ImageSource, durationMs: Long) {
        println("[ImageLoader] Success: ${request.url} from $source in ${durationMs}ms")
    }

    override fun onLoadError(request: ImageRequest, error: Throwable, durationMs: Long) {
        println("[ImageLoader] Error: ${request.url} - ${error.message} in ${durationMs}ms")
    }

    override fun onCancel(request: ImageRequest) {
        println("[ImageLoader] Cancelled: ${request.url}")
    }
}

/**
 * In-memory logger for testing.
 */
class InMemoryLogger : ImageLoaderLogger {
    data class LogEntry(
        val event: String,
        val url: String,
        val source: ImageSource? = null,
        val error: Throwable? = null,
        val durationMs: Long? = null
    )

    val entries = mutableListOf<LogEntry>()

    override fun onLoadStart(request: ImageRequest) {
        entries.add(LogEntry("start", request.url))
    }

    override fun onLoadSuccess(request: ImageRequest, source: ImageSource, durationMs: Long) {
        entries.add(LogEntry("success", request.url, source, null, durationMs))
    }

    override fun onLoadError(request: ImageRequest, error: Throwable, durationMs: Long) {
        entries.add(LogEntry("error", request.url, null, error, durationMs))
    }

    override fun onCancel(request: ImageRequest) {
        entries.add(LogEntry("cancel", request.url))
    }
}

/**
 * Builder for composing decorated image loaders.
 */
class ImageLoaderBuilder(
    private val fetcher: ImageFetcher
) {
    private var memoryCache: MemoryCache? = null
    private var diskCache: DiskCache? = null
    private var enableTransforms: Boolean = true
    private var enableDeduplication: Boolean = true
    private var logger: ImageLoaderLogger? = null

    fun memoryCache(cache: MemoryCache) = apply { this.memoryCache = cache }
    fun diskCache(cache: DiskCache) = apply { this.diskCache = cache }
    fun disableTransforms() = apply { this.enableTransforms = false }
    fun disableDeduplication() = apply { this.enableDeduplication = false }
    fun logger(logger: ImageLoaderLogger) = apply { this.logger = logger }

    fun build(): DecoratableImageLoader {
        var loader: DecoratableImageLoader = NetworkImageLoader(fetcher)
        
        if (enableTransforms) {
            loader = TransformDecorator(loader)
        }
        
        diskCache?.let { cache ->
            loader = DiskCacheDecorator(loader, cache)
        }
        
        memoryCache?.let { cache ->
            loader = MemoryCacheDecorator(loader, cache)
        }
        
        if (enableDeduplication) {
            loader = DeduplicationDecorator(loader)
        }
        
        logger?.let { log ->
            loader = LoggingDecorator(loader, log)
        }
        
        return loader
    }
}

/**
 * LRU Memory Cache for decorator approach.
 */
class DecoratorLruMemoryCache(private val maxSizeBytes: Long) : MemoryCache {
    private val lock = Any()
    private var currentSize = 0L
    private val cache = object : LinkedHashMap<String, ImageData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageData>?) = false
    }

    override fun get(key: String): ImageData? = synchronized(lock) { cache[key] }

    override fun put(key: String, data: ImageData) = synchronized(lock) {
        val size = data.bytes.size.toLong()
        if (size > maxSizeBytes) return
        cache[key]?.let { old -> currentSize -= old.bytes.size }
        currentSize += size
        cache[key] = data
        while (currentSize > maxSizeBytes && cache.isNotEmpty()) {
            val eldest = cache.entries.first()
            cache.remove(eldest.key)
            currentSize -= eldest.value.bytes.size
        }
    }

    override fun remove(key: String) = synchronized(lock) {
        cache.remove(key)?.let { currentSize -= it.bytes.size }
        Unit
    }

    override fun clear() = synchronized(lock) {
        cache.clear()
        currentSize = 0
    }

    override fun size(): Int = synchronized(lock) { cache.size }
    override fun maxSize(): Int = (maxSizeBytes / 1024).toInt()
}

/**
 * In-memory disk cache for decorator approach.
 */
class DecoratorInMemoryDiskCache : DiskCache {
    private val storage = ConcurrentHashMap<String, ByteArray>()
    override suspend fun get(key: String): ByteArray? = storage[key]
    override suspend fun put(key: String, data: ByteArray) { storage[key] = data }
    override suspend fun remove(key: String) { storage.remove(key) }
    override suspend fun clear() { storage.clear() }
}

/**
 * Mock fetcher for decorator approach.
 */
class DecoratorMockFetcher(
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
