/**
 * # Approach 01: Simple Download Manager
 *
 * ## Pattern Used
 * Sequential downloads with basic progress tracking.
 *
 * ## Trade-offs
 * - **Pros:** Simple, easy to understand
 * - **Cons:** No parallel downloads, no resume
 */
package com.systemdesign.downloadmanager.approach_01_simple

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class DownloadRequest(
    val id: String,
    val url: String,
    val destination: String
)

data class DownloadProgress(
    val id: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: DownloadState
) {
    val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

enum class DownloadState {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

interface Downloader {
    suspend fun download(url: String, onProgress: (Long, Long) -> Unit): ByteArray
}

class SimpleDownloadManager(
    private val downloader: Downloader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val downloads = ConcurrentHashMap<String, DownloadProgress>()
    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow

    suspend fun download(request: DownloadRequest): ByteArray {
        updateProgress(request.id, 0, 0, DownloadState.DOWNLOADING)
        
        return try {
            val data = downloader.download(request.url) { downloaded, total ->
                updateProgress(request.id, downloaded, total, DownloadState.DOWNLOADING)
            }
            updateProgress(request.id, data.size.toLong(), data.size.toLong(), DownloadState.COMPLETED)
            data
        } catch (e: CancellationException) {
            updateProgress(request.id, 0, 0, DownloadState.CANCELLED)
            throw e
        } catch (e: Exception) {
            updateProgress(request.id, 0, 0, DownloadState.FAILED)
            throw e
        }
    }

    private fun updateProgress(id: String, bytes: Long, total: Long, state: DownloadState) {
        val progress = DownloadProgress(id, bytes, total, state)
        downloads[id] = progress
        _progressFlow.tryEmit(progress)
    }

    fun getProgress(id: String): DownloadProgress? = downloads[id]

    fun shutdown() {
        scope.cancel()
    }
}
