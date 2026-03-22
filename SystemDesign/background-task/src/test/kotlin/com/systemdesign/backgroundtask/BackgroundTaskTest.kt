package com.systemdesign.backgroundtask

import com.systemdesign.backgroundtask.approach_01_simple.*
import com.systemdesign.backgroundtask.approach_02_persistent.*
import com.systemdesign.backgroundtask.approach_03_workmanager.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackgroundTaskTest {

    // Simple Background Task Tests
    @Test
    fun `simple - executes task`() = runBlocking {
        val runner = SimpleBackgroundTaskRunner()
        var executed = false
        
        val taskId = runner.execute("test") { executed = true }
        runner.await(taskId)
        
        assertTrue(executed)
        assertEquals(TaskStatus.COMPLETED, runner.getStatus(taskId)?.status)
        runner.shutdown()
    }

    @Test
    fun `simple - tracks progress`() = runBlocking {
        val runner = SimpleBackgroundTaskRunner()
        
        val taskId = runner.execute("test") { updateProgress ->
            updateProgress(0.5f)
            delay(10)
            updateProgress(1.0f)
        }
        
        runner.await(taskId)
        
        assertEquals(1f, runner.getStatus(taskId)?.progress)
        runner.shutdown()
    }

    @Test
    fun `simple - handles failure`() = runBlocking {
        val runner = SimpleBackgroundTaskRunner()
        
        val taskId = runner.execute<Unit>("test") { throw RuntimeException("Test error") }
        runner.await(taskId)
        
        assertEquals(TaskStatus.FAILED, runner.getStatus(taskId)?.status)
        assertNotNull(runner.getStatus(taskId)?.error)
        runner.shutdown()
    }

    @Test
    fun `simple - cancels task`() = runBlocking {
        val runner = SimpleBackgroundTaskRunner()
        
        val taskId = runner.execute("test") { 
            delay(5000)
        }
        
        delay(50)
        runner.cancel(taskId)
        delay(50)
        
        assertEquals(TaskStatus.CANCELLED, runner.getStatus(taskId)?.status)
        runner.shutdown()
    }

    // Persistent Background Task Tests
    @Test
    fun `persistent - executes and persists`() = runBlocking {
        val storage = InMemoryTaskStorage()
        val runner = PersistentBackgroundTaskRunner(storage)
        var executed = false
        
        runner.registerExecutor(object : TaskExecutor {
            override val type = "test"
            override suspend fun execute(data: Map<String, Any>, updateProgress: (Float) -> Unit) {
                executed = true
            }
        })
        
        val taskId = runner.schedule("test", emptyMap())
        delay(100)
        
        assertTrue(executed)
        assertEquals(TaskStatus.COMPLETED, storage.load(taskId)?.status)
        runner.shutdown()
    }

    @Test
    fun `persistent - retries on failure`() = runBlocking {
        val storage = InMemoryTaskStorage()
        val runner = PersistentBackgroundTaskRunner(storage, retryDelayMs = 10)
        var attempts = 0
        
        runner.registerExecutor(object : TaskExecutor {
            override val type = "test"
            override suspend fun execute(data: Map<String, Any>, updateProgress: (Float) -> Unit) {
                attempts++
                if (attempts < 3) throw RuntimeException("Fail")
            }
        })
        
        val taskId = runner.schedule("test", emptyMap(), maxRetries = 3)
        delay(500)
        
        assertEquals(3, attempts)
        assertEquals(TaskStatus.COMPLETED, storage.load(taskId)?.status)
        runner.shutdown()
    }

    // WorkManager-Style Tests
    @Test
    fun `workmanager - enqueues and executes`() = runBlocking {
        val manager = WorkManager()
        var executed = false
        
        manager.registerWorker(object : Worker {
            override val type = "test"
            override suspend fun doWork(data: Map<String, Any>): WorkResult {
                executed = true
                return WorkResult.Success
            }
        })
        
        val workId = manager.enqueue(WorkRequest("work1", "test"))
        delay(100)
        
        assertTrue(executed)
        assertEquals(TaskStatus.COMPLETED, manager.getWorkStatus(workId))
        manager.shutdown()
    }

    @Test
    fun `workmanager - respects initial delay`() = runBlocking {
        val manager = WorkManager()
        var executed = false
        
        manager.registerWorker(object : Worker {
            override val type = "test"
            override suspend fun doWork(data: Map<String, Any>): WorkResult {
                executed = true
                return WorkResult.Success
            }
        })
        
        manager.enqueue(WorkRequest("work1", "test", initialDelayMs = 100))
        delay(50)
        
        assertFalse(executed)
        
        delay(100)
        
        assertTrue(executed)
        manager.shutdown()
    }

    @Test
    fun `workmanager - chains work`() = runBlocking {
        val manager = WorkManager()
        val order = mutableListOf<String>()
        
        manager.registerWorker(object : Worker {
            override val type = "step"
            override suspend fun doWork(data: Map<String, Any>): WorkResult {
                order.add(data["id"] as String)
                return WorkResult.Success
            }
        })
        
        manager.enqueueChain(listOf(
            WorkRequest("work1", "step", mapOf("id" to "1")),
            WorkRequest("work2", "step", mapOf("id" to "2")),
            WorkRequest("work3", "step", mapOf("id" to "3"))
        ))
        
        delay(200)
        
        assertEquals(listOf("1", "2", "3"), order)
        manager.shutdown()
    }

    @Test
    fun `workmanager - retries with backoff`() = runBlocking {
        val manager = WorkManager()
        var attempts = 0
        
        manager.registerWorker(object : Worker {
            override val type = "test"
            override suspend fun doWork(data: Map<String, Any>): WorkResult {
                attempts++
                return if (attempts < 3) WorkResult.Retry else WorkResult.Success
            }
        })
        
        val workId = manager.enqueue(WorkRequest("work1", "test", backoffDelayMs = 10))
        delay(500)
        
        assertEquals(3, attempts)
        assertEquals(TaskStatus.COMPLETED, manager.getWorkStatus(workId))
        manager.shutdown()
    }
}
