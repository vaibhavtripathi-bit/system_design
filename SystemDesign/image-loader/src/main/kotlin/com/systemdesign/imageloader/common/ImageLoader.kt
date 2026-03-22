/**
 * Common interfaces and data classes for the Image Loading Library.
 * 
 * This module demonstrates system design for an image loading library similar to
 * Glide, Coil, or Picasso, focusing on:
 * - Multi-level caching (memory + disk)
 * - Request deduplication
 * - Cancellation support
 * - Transformation pipeline
 */
package com.systemdesign.imageloader.common

import kotlinx.coroutines.flow.Flow

/**
 * Represents a request to load an image.
 */
data class ImageRequest(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val transformations: List<Transformation> = emptyList(),
    val priority: Priority = Priority.NORMAL,
    val cachePolicy: CachePolicy = CachePolicy.ALL
) {
    val cacheKey: String
        get() = buildString {
            append(url)
            if (width > 0 || height > 0) append("_${width}x$height")
            transformations.forEach { append("_${it.key}") }
        }
}

/**
 * Represents a loaded image (simplified as byte array for this design).
 */
data class ImageData(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val source: ImageSource
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageData
        return bytes.contentEquals(other.bytes) && width == other.width && 
               height == other.height && source == other.source
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + source.hashCode()
        return result
    }
}

/**
 * Source of the image data.
 */
enum class ImageSource {
    MEMORY_CACHE,
    DISK_CACHE,
    NETWORK
}

/**
 * Request priority.
 */
enum class Priority {
    LOW,
    NORMAL,
    HIGH,
    IMMEDIATE
}

/**
 * Cache policy for requests.
 */
enum class CachePolicy {
    NONE,
    MEMORY_ONLY,
    DISK_ONLY,
    ALL
}

/**
 * Result of an image load operation.
 */
sealed class ImageResult {
    data class Success(val data: ImageData) : ImageResult()
    data class Error(val exception: Throwable) : ImageResult()
    data class Progress(val bytesLoaded: Long, val totalBytes: Long) : ImageResult()
}

/**
 * Image transformation interface.
 */
interface Transformation {
    val key: String
    fun transform(data: ImageData): ImageData
}

/**
 * Core image loader interface.
 */
interface ImageLoader {
    suspend fun load(request: ImageRequest): ImageResult
    fun cancel(request: ImageRequest)
    fun clearMemoryCache()
    fun clearDiskCache()
}

/**
 * Reactive image loader interface.
 */
interface ReactiveImageLoader {
    fun load(request: ImageRequest): Flow<ImageResult>
    fun cancel(request: ImageRequest)
}

/**
 * Image fetcher interface for network/disk operations.
 */
interface ImageFetcher {
    suspend fun fetch(url: String): ByteArray
    fun canHandle(url: String): Boolean
}

/**
 * Memory cache interface.
 */
interface MemoryCache {
    fun get(key: String): ImageData?
    fun put(key: String, data: ImageData)
    fun remove(key: String)
    fun clear()
    fun size(): Int
    fun maxSize(): Int
}

/**
 * Disk cache interface.
 */
interface DiskCache {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, data: ByteArray)
    suspend fun remove(key: String)
    suspend fun clear()
}

/**
 * Common transformations.
 */
class ResizeTransformation(private val targetWidth: Int, private val targetHeight: Int) : Transformation {
    override val key: String = "resize_${targetWidth}x$targetHeight"
    
    override fun transform(data: ImageData): ImageData {
        return data.copy(width = targetWidth, height = targetHeight)
    }
}

class CropTransformation(private val x: Int, private val y: Int, private val w: Int, private val h: Int) : Transformation {
    override val key: String = "crop_${x}_${y}_${w}_$h"
    
    override fun transform(data: ImageData): ImageData {
        return data.copy(width = w, height = h)
    }
}

class GrayscaleTransformation : Transformation {
    override val key: String = "grayscale"
    
    override fun transform(data: ImageData): ImageData {
        return data
    }
}
