/**
 * # Approach 02: Concurrent Download Manager
 *
 * ## Pattern Used
 * Parallel downloads with configurable concurrency limit.
 *
 * ## Trade-offs
 * - **Pros:** Faster for multiple files, controlled resource usage
 * - **Cons:** More complex, needs queue management
 */
package com.systemdesign.downloadmanager.approach_02_concurrent

import com.systemdesign.downloadmanager.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class ConcurrentDownloadManager(
    private val downloader: Downloader,
    private val maxConcurrent: Int = 3,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val semaphore = Semaphore(maxConcurrent)
    private val downloads = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, DownloadProgress>()
    private val results = ConcurrentHashMap<String, Result<ByteArray>>()
    
    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow

    fun enqueue(request: DownloadRequest): Job {
        updateProgress(request.id, 0, 0, DownloadState.PENDING)
        
        val job = scope.launch {
            semaphore.withPermit {
                try {
                    updateProgress(request.id, 0, 0, DownloadState.DOWNLOADING)
                    val data = downloader.download(request.url) { downloaded, total ->
                        updateProgress(request.id, downloaded, total, DownloadState.DOWNLOADING)
                    }
                    results[request.id] = Result.success(data)
                    updateProgress(request.id, data.size.toLong(), data.size.toLong(), DownloadState.COMPLETED)
                } catch (e: CancellationException) {
                    updateProgress(request.id, 0, 0, DownloadState.CANCELLED)
                    throw e
                } catch (e: Exception) {
                    results[request.id] = Result.failure(e)
                    updateProgress(request.id, 0, 0, DownloadState.FAILED)
                }
            }
        }
        
        downloads[request.id] = job
        return job
    }

    suspend fun downloadAll(requests: List<DownloadRequest>): Map<String, Result<ByteArray>> {
        val jobs = requests.map { enqueue(it) }
        jobs.forEach { it.join() }
        return results.toMap()
    }

    fun cancel(id: String): Boolean {
        val job = downloads[id] ?: return false
        job.cancel()
        updateProgress(id, 0, 0, DownloadState.CANCELLED)
        return true
    }

    fun cancelAll() {
        downloads.keys.forEach { cancel(it) }
    }

    private fun updateProgress(id: String, bytes: Long, total: Long, state: DownloadState) {
        val p = DownloadProgress(id, bytes, total, state)
        progress[id] = p
        _progressFlow.tryEmit(p)
    }

    fun getProgress(id: String): DownloadProgress? = progress[id]

    fun getResult(id: String): Result<ByteArray>? = results[id]

    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}
