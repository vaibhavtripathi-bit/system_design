package com.systemdesign.downloadmanager

import com.systemdesign.downloadmanager.approach_01_simple.*
import com.systemdesign.downloadmanager.approach_02_concurrent.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadManagerTest {

    class MockDownloader(private val data: ByteArray = "test data".toByteArray()) : Downloader {
        val downloadCount = AtomicInteger(0)
        
        override suspend fun download(url: String, onProgress: (Long, Long) -> Unit): ByteArray {
            downloadCount.incrementAndGet()
            val total = data.size.toLong()
            data.indices.forEach { i ->
                delay(1)
                onProgress(i.toLong() + 1, total)
            }
            return data
        }
    }

    // Simple Download Manager Tests
    @Test
    fun `simple - downloads file`() = runBlocking {
        val downloader = MockDownloader()
        val manager = SimpleDownloadManager(downloader)
        
        val data = manager.download(DownloadRequest("1", "http://test.com/file", "/tmp/file"))
        
        assertArrayEquals("test data".toByteArray(), data)
        assertEquals(1, downloader.downloadCount.get())
        manager.shutdown()
    }

    @Test
    fun `simple - tracks progress`() = runBlocking {
        val downloader = MockDownloader()
        val manager = SimpleDownloadManager(downloader)
        
        manager.download(DownloadRequest("1", "http://test.com/file", "/tmp/file"))
        
        val progress = manager.getProgress("1")
        assertNotNull(progress)
        assertEquals(DownloadState.COMPLETED, progress?.state)
        manager.shutdown()
    }

    // Concurrent Download Manager Tests
    @Test
    fun `concurrent - downloads multiple files`() = runBlocking {
        val downloader = MockDownloader()
        val manager = ConcurrentDownloadManager(downloader, maxConcurrent = 3)
        
        val requests = (1..5).map { DownloadRequest("$it", "http://test.com/file$it", "/tmp/file$it") }
        val results = manager.downloadAll(requests)
        
        assertEquals(5, results.size)
        assertTrue(results.values.all { it.isSuccess })
        assertEquals(5, downloader.downloadCount.get())
        manager.shutdown()
    }

    @Test
    fun `concurrent - limits concurrency`() = runBlocking {
        val activeCount = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        
        val slowDownloader = object : Downloader {
            override suspend fun download(url: String, onProgress: (Long, Long) -> Unit): ByteArray {
                val current = activeCount.incrementAndGet()
                maxActive.updateAndGet { max -> maxOf(max, current) }
                delay(50)
                activeCount.decrementAndGet()
                return "data".toByteArray()
            }
        }
        
        val manager = ConcurrentDownloadManager(slowDownloader, maxConcurrent = 2)
        val requests = (1..5).map { DownloadRequest("$it", "http://test.com/file$it", "/tmp/file$it") }
        
        manager.downloadAll(requests)
        
        assertTrue(maxActive.get() <= 2, "Max concurrent was ${maxActive.get()}, expected <= 2")
        manager.shutdown()
    }

    @Test
    fun `concurrent - can cancel download`() = runBlocking {
        val slowDownloader = object : Downloader {
            override suspend fun download(url: String, onProgress: (Long, Long) -> Unit): ByteArray {
                delay(1000)
                return "data".toByteArray()
            }
        }
        
        val manager = ConcurrentDownloadManager(slowDownloader)
        val job = manager.enqueue(DownloadRequest("1", "http://test.com/file", "/tmp/file"))
        
        delay(50)
        val cancelled = manager.cancel("1")
        
        assertTrue(cancelled)
        job.join()
        assertEquals(DownloadState.CANCELLED, manager.getProgress("1")?.state)
        manager.shutdown()
    }

    @Test
    fun `concurrent - tracks individual progress`() = runBlocking {
        val downloader = MockDownloader()
        val manager = ConcurrentDownloadManager(downloader)
        
        val requests = (1..3).map { DownloadRequest("$it", "http://test.com/file$it", "/tmp/file$it") }
        manager.downloadAll(requests)
        
        requests.forEach { request ->
            val progress = manager.getProgress(request.id)
            assertNotNull(progress)
            assertEquals(DownloadState.COMPLETED, progress?.state)
        }
        manager.shutdown()
    }
}
