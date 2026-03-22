/**
 * # Approach 03: Reactive Pipeline Image Loader
 *
 * ## Pattern Used
 * Reactive programming with Kotlin Flow for composable, backpressure-aware pipelines.
 * Each stage is a Flow operator that can be composed and cancelled.
 *
 * ## How It Works
 * 1. Load requests become Flows that emit progress, success, or error
 * 2. Pipeline stages are Flow operators:
 *    - memoryCacheFlow: Check/populate memory cache
 *    - diskCacheFlow: Check/populate disk cache
 *    - networkFlow: Fetch from network with progress
 *    - transformFlow: Apply transformations
 * 3. Flows support cancellation, backpressure, and composition
 * 4. StateFlow/SharedFlow for request deduplication
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Natural cancellation support via Flow
 *   - Backpressure handling built-in
 *   - Progress updates are first-class
 *   - Composable pipeline operators
 *   - Lifecycle-aware with proper collection
 *
 * - **Cons:**
 *   - Steeper learning curve for reactive concepts
 *   - Debugging Flow chains can be challenging
 *   - Overhead for simple operations
 *   - Cold vs Hot flows require understanding
 *
 * ## When to Prefer This Approach
 * - When progress updates are important
 * - When cancellation must be seamless
 * - For Android apps with lifecycle concerns
 * - When composing multiple async operations
 * - Modern coroutine-first codebases
 *
 * ## Comparison with Other Approaches
 * - **vs Strategy (Approach 01):** Reactive is stream-based; Strategy is call-based
 * - **vs Decorator (Approach 02):** Both compose; Reactive adds backpressure/cancellation
 */
package com.systemdesign.imageloader.approach_03_reactive_pipeline

import com.systemdesign.imageloader.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Reactive Image Loader using Kotlin Flow.
 */
class FlowImageLoader(
    private val config: FlowImageLoaderConfig
) : ReactiveImageLoader {

    private val inFlightRequests = ConcurrentHashMap<String, SharedFlow<ImageResult>>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    override fun load(request: ImageRequest): Flow<ImageResult> = flow {
        if (cancelledRequests.remove(request.cacheKey)) {
            emit(ImageResult.Error(CancellationException("Cancelled")))
            return@flow
        }

        emitAll(loadWithDeduplication(request))
    }.flowOn(config.dispatcher)

    private fun loadWithDeduplication(request: ImageRequest): Flow<ImageResult> {
        val key = request.cacheKey

        inFlightRequests[key]?.let { existing ->
            return existing
        }

        val flow = createLoadFlow(request)
            .onCompletion { inFlightRequests.remove(key) }
            .shareIn(
                scope = config.scope,
                started = SharingStarted.Lazily,
                replay = 1
            )

        val existing = inFlightRequests.putIfAbsent(key, flow)
        return existing ?: flow
    }

    private fun createLoadFlow(request: ImageRequest): Flow<ImageResult> = flow {
        config.memoryCache?.get(request.cacheKey)?.let { cached ->
            emit(ImageResult.Success(cached.copy(source = ImageSource.MEMORY_CACHE)))
            return@flow
        }

        if (request.cachePolicy != CachePolicy.MEMORY_ONLY && 
            request.cachePolicy != CachePolicy.NONE) {
            config.diskCache?.get(request.cacheKey)?.let { bytes ->
                val data = ImageData(bytes, request.width, request.height, ImageSource.DISK_CACHE)
                val transformed = applyTransformations(data, request.transformations)
                cacheToMemory(request, transformed)
                emit(ImageResult.Success(transformed))
                return@flow
            }
        }

        emitAll(fetchFromNetwork(request))
    }

    private fun fetchFromNetwork(request: ImageRequest): Flow<ImageResult> = flow {
        try {
            if (cancelledRequests.contains(request.cacheKey)) {
                emit(ImageResult.Error(CancellationException("Cancelled")))
                return@flow
            }

            val bytes = config.fetcher.fetch(request.url)
            val data = ImageData(bytes, request.width, request.height, ImageSource.NETWORK)
            val transformed = applyTransformations(data, request.transformations)
            
            cacheResult(request, transformed)
            emit(ImageResult.Success(transformed))
        } catch (e: CancellationException) {
            emit(ImageResult.Error(e))
        } catch (e: Exception) {
            emit(ImageResult.Error(e))
        }
    }

    private fun applyTransformations(data: ImageData, transformations: List<Transformation>): ImageData {
        return transformations.fold(data) { current, transform ->
            transform.transform(current)
        }
    }

    private suspend fun cacheToMemory(request: ImageRequest, data: ImageData) {
        if (request.cachePolicy != CachePolicy.NONE && 
            request.cachePolicy != CachePolicy.DISK_ONLY) {
            config.memoryCache?.put(request.cacheKey, data)
        }
    }

    private suspend fun cacheResult(request: ImageRequest, data: ImageData) {
        when (request.cachePolicy) {
            CachePolicy.ALL -> {
                config.memoryCache?.put(request.cacheKey, data)
                config.diskCache?.put(request.cacheKey, data.bytes)
            }
            CachePolicy.MEMORY_ONLY -> {
                config.memoryCache?.put(request.cacheKey, data)
            }
            CachePolicy.DISK_ONLY -> {
                config.diskCache?.put(request.cacheKey, data.bytes)
            }
            CachePolicy.NONE -> { }
        }
    }

    override fun cancel(request: ImageRequest) {
        cancelledRequests.add(request.cacheKey)
    }

    fun clearCaches() {
        config.memoryCache?.clear()
        runBlocking { config.diskCache?.clear() }
    }
}

/**
 * Configuration for Flow-based image loader.
 */
data class FlowImageLoaderConfig(
    val fetcher: ImageFetcher,
    val memoryCache: MemoryCache? = null,
    val diskCache: DiskCache? = null,
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())
)

/**
 * Extension function to load image and collect first result.
 */
suspend fun ReactiveImageLoader.loadFirst(request: ImageRequest): ImageResult {
    return load(request).first()
}

/**
 * Extension function to load image with timeout.
 */
suspend fun ReactiveImageLoader.loadWithTimeout(request: ImageRequest, timeoutMs: Long): ImageResult {
    return withTimeoutOrNull(timeoutMs) {
        load(request).first()
    } ?: ImageResult.Error(Exception("Timeout after ${timeoutMs}ms"))
}

/**
 * Flow operators for image loading pipeline.
 */
object ImageFlowOperators {
    
    /**
     * Retries the flow on error with exponential backoff.
     */
    fun Flow<ImageResult>.retryWithBackoff(
        maxRetries: Int = 3,
        initialDelayMs: Long = 100
    ): Flow<ImageResult> = flow {
        var retries = 0
        var delay = initialDelayMs
        
        collect { result ->
            when (result) {
                is ImageResult.Error -> {
                    if (retries < maxRetries) {
                        retries++
                        kotlinx.coroutines.delay(delay)
                        delay *= 2
                    } else {
                        emit(result)
                    }
                }
                else -> {
                    retries = 0
                    delay = initialDelayMs
                    emit(result)
                }
            }
        }
    }

    /**
     * Filters only success results.
     */
    fun Flow<ImageResult>.filterSuccess(): Flow<ImageData> = mapNotNull { result ->
        (result as? ImageResult.Success)?.data
    }

    /**
     * Maps success data through a transformation.
     */
    fun Flow<ImageResult>.mapData(transform: (ImageData) -> ImageData): Flow<ImageResult> = map { result ->
        when (result) {
            is ImageResult.Success -> ImageResult.Success(transform(result.data))
            else -> result
        }
    }
}

/**
 * Batch image loader using Flow.
 */
class BatchImageLoader(
    private val loader: ReactiveImageLoader,
    private val concurrency: Int = 4
) {
    fun loadBatch(requests: List<ImageRequest>): Flow<Pair<ImageRequest, ImageResult>> = 
        requests.asFlow()
            .flatMapMerge(concurrency) { request ->
                loader.load(request).map { result -> request to result }
            }
}

/**
 * Priority queue-based image loader.
 */
class PriorityImageLoader(
    private val config: FlowImageLoaderConfig,
    private val maxConcurrent: Int = 4
) {
    private val highPriorityChannel = MutableSharedFlow<ImageRequest>(extraBufferCapacity = 100)
    private val normalPriorityChannel = MutableSharedFlow<ImageRequest>(extraBufferCapacity = 100)
    private val lowPriorityChannel = MutableSharedFlow<ImageRequest>(extraBufferCapacity = 100)
    
    private val results = ConcurrentHashMap<String, CompletableDeferred<ImageResult>>()
    private val loader = FlowImageLoader(config)

    init {
        config.scope.launch {
            merge(
                highPriorityChannel,
                normalPriorityChannel,
                lowPriorityChannel
            ).collect { request ->
                launch {
                    val result = loader.load(request).first()
                    results[request.cacheKey]?.complete(result)
                }
            }
        }
    }

    suspend fun load(request: ImageRequest): ImageResult {
        val deferred = CompletableDeferred<ImageResult>()
        results[request.cacheKey] = deferred

        when (request.priority) {
            Priority.IMMEDIATE, Priority.HIGH -> highPriorityChannel.emit(request)
            Priority.NORMAL -> normalPriorityChannel.emit(request)
            Priority.LOW -> lowPriorityChannel.emit(request)
        }

        return deferred.await()
    }
}

/**
 * Memory cache for reactive approach.
 */
class ReactiveMemoryCache(private val maxSizeBytes: Long) : MemoryCache {
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
 * In-memory disk cache for reactive approach.
 */
class ReactiveInMemoryDiskCache : DiskCache {
    private val storage = ConcurrentHashMap<String, ByteArray>()
    override suspend fun get(key: String): ByteArray? = storage[key]
    override suspend fun put(key: String, data: ByteArray) { storage[key] = data }
    override suspend fun remove(key: String) { storage.remove(key) }
    override suspend fun clear() { storage.clear() }
}

/**
 * Mock fetcher for reactive approach.
 */
class ReactiveMockFetcher(
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
