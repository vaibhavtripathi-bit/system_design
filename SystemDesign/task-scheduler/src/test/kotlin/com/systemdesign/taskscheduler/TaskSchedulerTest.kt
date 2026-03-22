package com.systemdesign.taskscheduler

import com.systemdesign.taskscheduler.common.*
import com.systemdesign.taskscheduler.approach_01_priority_queue.PriorityQueueScheduler
import com.systemdesign.taskscheduler.approach_02_dag.DagScheduler
import com.systemdesign.taskscheduler.approach_03_actor.ActorScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

class TaskSchedulerTest {

    private val simpleExecutor = object : TaskExecutor {
        override suspend fun execute(task: Task): Any = "result-${task.id}"
    }

    private fun failingExecutor(failCount: Int) = object : TaskExecutor {
        val attempts = AtomicInteger(0)
        override suspend fun execute(task: Task): Any {
            if (attempts.incrementAndGet() <= failCount) {
                throw RuntimeException("Simulated failure")
            }
            return "success"
        }
    }

    // Priority Queue Tests
    @Test
    fun `priority queue - schedules and executes task`() = runBlocking {
        val scheduler = PriorityQueueScheduler(concurrency = 2)
        val taskId = scheduler.schedule(Task(name = "test"), simpleExecutor)
        
        withTimeout(2000) {
            while (scheduler.getStatus(taskId)?.state != TaskState.COMPLETED) {
                delay(50)
            }
        }
        
        assertEquals(TaskState.COMPLETED, scheduler.getStatus(taskId)?.state)
        scheduler.shutdown()
    }

    @Test
    fun `priority queue - retries on failure`() = runBlocking {
        val scheduler = PriorityQueueScheduler(concurrency = 1)
        val executor = failingExecutor(2)
        val task = Task(name = "retry-test", retryPolicy = RetryPolicy(maxRetries = 3, initialDelayMs = 10))
        
        scheduler.schedule(task, executor)
        
        withTimeout(3000) {
            while (executor.attempts.get() < 3) {
                delay(50)
            }
        }
        
        assertTrue(executor.attempts.get() >= 3)
        scheduler.shutdown()
    }

    @Test
    fun `priority queue - cancels pending task`() = runBlocking {
        val scheduler = PriorityQueueScheduler(concurrency = 1)
        val slowExecutor = object : TaskExecutor {
            override suspend fun execute(task: Task): Any {
                delay(5000)
                return "done"
            }
        }
        
        val task1 = Task(name = "slow")
        val task2 = Task(name = "to-cancel")
        
        scheduler.schedule(task1, slowExecutor)
        delay(50)
        val id2 = scheduler.schedule(task2, slowExecutor)
        
        val cancelled = scheduler.cancel(id2)
        assertTrue(cancelled)
        assertEquals(TaskState.CANCELLED, scheduler.getStatus(id2)?.state)
        scheduler.shutdown()
    }

    // DAG Tests
    @Test
    fun `dag - executes independent tasks`() = runBlocking {
        val scheduler = DagScheduler(concurrency = 4)
        val completed = AtomicInteger(0)
        
        val countingExecutor = object : TaskExecutor {
            override suspend fun execute(task: Task): Any {
                completed.incrementAndGet()
                return task.id
            }
        }
        
        repeat(4) { i ->
            scheduler.schedule(Task(name = "task-$i"), countingExecutor)
        }
        
        withTimeout(2000) {
            while (completed.get() < 4) {
                delay(50)
            }
        }
        
        assertEquals(4, completed.get())
        scheduler.shutdown()
    }

    @Test
    fun `dag - respects dependencies`() = runBlocking {
        val scheduler = DagScheduler(concurrency = 4)
        val executionOrder = CopyOnWriteArrayList<String>()
        
        val orderTrackingExecutor = object : TaskExecutor {
            override suspend fun execute(task: Task): Any {
                executionOrder.add(task.id)
                delay(50)
                return task.id
            }
        }
        
        val task1 = Task(id = "task1", name = "first")
        val task2 = Task(id = "task2", name = "second", dependencies = setOf("task1"))
        val task3 = Task(id = "task3", name = "third", dependencies = setOf("task2"))
        
        scheduler.schedule(task1, orderTrackingExecutor)
        scheduler.schedule(task2, orderTrackingExecutor)
        scheduler.schedule(task3, orderTrackingExecutor)
        
        withTimeout(3000) {
            while (executionOrder.size < 3) {
                delay(50)
            }
        }
        
        val idx1 = executionOrder.indexOf("task1")
        val idx2 = executionOrder.indexOf("task2")
        val idx3 = executionOrder.indexOf("task3")
        
        assertTrue(idx1 < idx2, "task1 should execute before task2")
        assertTrue(idx2 < idx3, "task2 should execute before task3")
        scheduler.shutdown()
    }

    @Test
    fun `dag - detects cycles`() = runBlocking {
        val scheduler = DagScheduler()
        
        val task1 = Task(id = "a", name = "A", dependencies = setOf("c"))
        val task2 = Task(id = "b", name = "B", dependencies = setOf("a"))
        val task3 = Task(id = "c", name = "C", dependencies = setOf("b"))
        
        scheduler.schedule(task1, simpleExecutor)
        scheduler.schedule(task2, simpleExecutor)
        
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scheduler.schedule(task3, simpleExecutor) }
        }
        scheduler.shutdown()
    }

    @Test
    fun `dag - returns execution order`() = runBlocking {
        val scheduler = DagScheduler()
        
        val task1 = Task(id = "a", name = "A")
        val task2 = Task(id = "b", name = "B", dependencies = setOf("a"))
        val task3 = Task(id = "c", name = "C", dependencies = setOf("b"))
        
        scheduler.schedule(task1, simpleExecutor)
        scheduler.schedule(task2, simpleExecutor)
        scheduler.schedule(task3, simpleExecutor)
        
        val order = scheduler.getExecutionOrder()
        
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        assertTrue(order.indexOf("b") < order.indexOf("c"))
        scheduler.shutdown()
    }

    // Actor Tests
    @Test
    fun `actor - schedules and executes task`() = runBlocking {
        val scheduler = ActorScheduler(concurrency = 2)
        val taskId = scheduler.schedule(Task(name = "test"), simpleExecutor)
        
        withTimeout(2000) {
            while (scheduler.getStatus(taskId)?.state != TaskState.COMPLETED) {
                delay(50)
            }
        }
        
        assertEquals(TaskState.COMPLETED, scheduler.getStatus(taskId)?.state)
        scheduler.shutdown()
    }

    @Test
    fun `actor - handles concurrent scheduling`() = runBlocking {
        val scheduler = ActorScheduler(concurrency = 4)
        val counter = AtomicInteger(0)
        
        val countingExecutor = object : TaskExecutor {
            override suspend fun execute(task: Task): Any {
                counter.incrementAndGet()
                return "done"
            }
        }
        
        repeat(10) { i ->
            scheduler.schedule(Task(name = "task-$i"), countingExecutor)
        }
        
        withTimeout(3000) {
            while (counter.get() < 10) {
                delay(50)
            }
        }
        
        assertEquals(10, counter.get())
        scheduler.shutdown()
    }

    @Test
    fun `actor - retries on failure`() = runBlocking {
        val scheduler = ActorScheduler(concurrency = 1)
        val executor = failingExecutor(2)
        val task = Task(name = "retry-test", retryPolicy = RetryPolicy(maxRetries = 3, initialDelayMs = 10))
        
        scheduler.schedule(task, executor)
        
        withTimeout(3000) {
            while (executor.attempts.get() < 3) {
                delay(50)
            }
        }
        
        assertTrue(executor.attempts.get() >= 3)
        scheduler.shutdown()
    }

    @Test
    fun `actor - cancels pending task`() = runBlocking {
        val scheduler = ActorScheduler(concurrency = 1)
        val slowExecutor = object : TaskExecutor {
            override suspend fun execute(task: Task): Any {
                delay(5000)
                return "done"
            }
        }
        
        val task1 = Task(name = "slow")
        val task2 = Task(name = "to-cancel")
        
        scheduler.schedule(task1, slowExecutor)
        delay(50)
        val id2 = scheduler.schedule(task2, slowExecutor)
        
        delay(50)
        val cancelled = scheduler.cancel(id2)
        assertTrue(cancelled)
        scheduler.shutdown()
    }

    @Test
    fun `actor - get all statuses`() = runBlocking {
        val scheduler = ActorScheduler(concurrency = 4)
        
        repeat(5) { i ->
            scheduler.schedule(Task(name = "task-$i"), simpleExecutor)
        }
        
        delay(100)
        
        val statuses = scheduler.getAllStatuses()
        assertEquals(5, statuses.size)
        scheduler.shutdown()
    }
}
