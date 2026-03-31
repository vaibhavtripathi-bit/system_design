package com.systemdesign.downloadmanager

import com.systemdesign.downloadmanager.approach_01_simple.*
import com.systemdesign.downloadmanager.approach_02_concurrent.*
import com.systemdesign.downloadmanager.approach_03_resumable.*
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

    // Resumable Download Manager Tests
    class MockResumableDownloader(
        private val content: ByteArray = "resumable test data".toByteArray()
    ) : ResumableDownloader {
        override suspend fun getSize(url: String): Long = content.size.toLong()

        override suspend fun downloadRange(url: String, start: Long, end: Long): ByteArray {
            delay(10)
            return content.sliceArray(start.toInt()..end.toInt())
        }
    }

    @Test
    fun `resumable - start and complete download`() = runBlocking {
        val downloader = MockResumableDownloader()
        val manager = ResumableDownloadManager(downloader, Dispatchers.Unconfined)

        val job = manager.start("1", "http://test.com/file")
        job.join()

        val download = manager.getDownload("1")
        assertNotNull(download)
        assertEquals(DownloadState.COMPLETED, download?.state)
        manager.shutdown()
    }

    @Test
    fun `resumable - tracks progress`() = runBlocking {
        val downloader = MockResumableDownloader()
        val manager = ResumableDownloadManager(downloader, Dispatchers.Unconfined)

        val job = manager.start("1", "http://test.com/file")
        job.join()

        val download = manager.getDownload("1")!!
        assertEquals(download.totalSize, download.downloadedBytes)
        assertEquals(1f, download.progress)
        manager.shutdown()
    }

    @Test
    fun `resumable - cancel stops download`() = runBlocking {
        val slowDownloader = object : ResumableDownloader {
            override suspend fun getSize(url: String) = 1000L
            override suspend fun downloadRange(url: String, start: Long, end: Long): ByteArray {
                delay(5000)
                return ByteArray(0)
            }
        }

        val manager = ResumableDownloadManager(slowDownloader)
        val job = manager.start("1", "http://test.com/file")

        delay(50)
        manager.cancel("1")
        job.join()

        assertEquals(DownloadState.CANCELLED, manager.getDownload("1")?.state)
        manager.shutdown()
    }

    @Test
    fun `resumable - getData returns content after completion`() = runBlocking {
        val content = "hello world".toByteArray()
        val downloader = MockResumableDownloader(content)
        val manager = ResumableDownloadManager(downloader, Dispatchers.Unconfined)

        val job = manager.start("1", "http://test.com/file")
        job.join()

        val data = manager.getData("1")
        assertNotNull(data)
        assertArrayEquals(content, data)
        manager.shutdown()
    }

    @Test
    fun `resumable - getData returns null before completion`() = runBlocking {
        val slowDownloader = object : ResumableDownloader {
            override suspend fun getSize(url: String) = 1000L
            override suspend fun downloadRange(url: String, start: Long, end: Long): ByteArray {
                delay(5000)
                return ByteArray(0)
            }
        }

        val manager = ResumableDownloadManager(slowDownloader)
        val job = manager.start("1", "http://test.com/file")

        delay(50)
        assertNull(manager.getData("1"))

        manager.cancel("1")
        job.join()
        manager.shutdown()
    }
}
