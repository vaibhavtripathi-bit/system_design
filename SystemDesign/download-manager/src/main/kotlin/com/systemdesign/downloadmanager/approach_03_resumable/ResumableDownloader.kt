/**
 * # Approach 03: Resumable Download Manager
 *
 * ## Pattern Used
 * Chunk-based downloads with pause/resume support.
 *
 * ## Trade-offs
 * - **Pros:** Resume after interruption, better for large files
 * - **Cons:** Most complex, needs chunk tracking
 */
package com.systemdesign.downloadmanager.approach_03_resumable

import com.systemdesign.downloadmanager.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class ChunkInfo(
    val index: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long
)

data class ResumableDownload(
    val id: String,
    val url: String,
    val totalSize: Long,
    val downloadedBytes: Long,
    val state: DownloadState
) {
    val progress: Float get() = if (totalSize > 0) downloadedBytes.toFloat() / totalSize else 0f
}

interface ResumableDownloader {
    suspend fun getSize(url: String): Long
    suspend fun downloadRange(url: String, start: Long, end: Long): ByteArray
}

class ResumableDownloadManager(
    private val downloader: ResumableDownloader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private val downloads = ConcurrentHashMap<String, ResumableDownload>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val data = ConcurrentHashMap<String, ByteArray>()
    
    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow

    suspend fun start(id: String, url: String): Job {
        val totalSize = downloader.getSize(url)
        downloads[id] = ResumableDownload(id, url, totalSize, 0, DownloadState.DOWNLOADING)
        data[id] = ByteArray(0)
        
        val job = scope.launch {
            try {
                val content = downloader.downloadRange(url, 0, totalSize - 1)
                data[id] = content
                updateDownload(id) { it.copy(downloadedBytes = totalSize, state = DownloadState.COMPLETED) }
            } catch (e: CancellationException) {
                val current = downloads[id]?.state
                if (current != DownloadState.CANCELLED) {
                    updateDownload(id) { it.copy(state = DownloadState.PAUSED) }
                }
                throw e
            } catch (e: Exception) {
                updateDownload(id) { it.copy(state = DownloadState.FAILED) }
            }
        }
        
        jobs[id] = job
        return job
    }

    fun pause(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        updateDownload(id) { it.copy(state = DownloadState.PAUSED) }
        return true
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        updateDownload(id) { it.copy(state = DownloadState.CANCELLED) }
    }

    fun getData(id: String): ByteArray? {
        val download = downloads[id] ?: return null
        if (download.state != DownloadState.COMPLETED) return null
        return data[id]
    }

    private fun updateDownload(id: String, transform: (ResumableDownload) -> ResumableDownload) {
        downloads.computeIfPresent(id) { _, d -> transform(d) }
        downloads[id]?.let { emitProgress(it) }
    }

    private fun emitProgress(download: ResumableDownload) {
        _progressFlow.tryEmit(DownloadProgress(
            download.id,
            download.downloadedBytes,
            download.totalSize,
            download.state
        ))
    }

    fun getDownload(id: String): ResumableDownload? = downloads[id]

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
    }
}
