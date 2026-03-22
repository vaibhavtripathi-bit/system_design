/**
 * # Approach 01: Priority Queue Task Scheduler
 *
 * ## Pattern Used
 * Priority queue (heap-based) for task ordering with concurrent execution.
 * Higher priority tasks are executed first.
 *
 * ## Trade-offs
 * - **Pros:** Simple, efficient O(log n) insertion, natural priority ordering
 * - **Cons:** No dependency handling, priority inversion possible
 *
 * ## When to Prefer
 * - Simple priority-based scheduling without dependencies
 * - When task ordering by priority is the primary concern
 */
package com.systemdesign.taskscheduler.approach_01_priority_queue

import com.systemdesign.taskscheduler.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

class PriorityQueueScheduler(
    private val concurrency: Int = 4,
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : TaskScheduler {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    
    private val taskQueue = PriorityQueue<QueuedTask>(compareByDescending { it.task.priority.value })
    private val statuses = ConcurrentHashMap<String, TaskStatus>()
    private val executors = ConcurrentHashMap<String, TaskExecutor>()
    
    private val results = MutableSharedFlow<TaskResult>(extraBufferCapacity = 100)
    private val workChannel = Channel<QueuedTask>(Channel.UNLIMITED)
    
    private var isRunning = true

    init {
        repeat(concurrency) { startWorker() }
    }

    private fun startWorker() {
        scope.launch {
            while (isRunning) {
                val queued = mutex.withLock {
                    taskQueue.poll()
                }
                if (queued != null) {
                    executeTask(queued)
                } else {
                    delay(10)
                }
            }
        }
    }

    override suspend fun schedule(task: Task, executor: TaskExecutor): String {
        executors[task.id] = executor
        statuses[task.id] = TaskStatus(task.id, TaskState.QUEUED)
        
        mutex.withLock {
            taskQueue.offer(QueuedTask(task, clock.now()))
        }
        
        return task.id
    }

    private suspend fun executeTask(queued: QueuedTask) {
        val task = queued.task
        val executor = executors[task.id] ?: return
        
        statuses[task.id] = TaskStatus(task.id, TaskState.RUNNING, startTime = clock.now())
        
        try {
            val result = executor.execute(task)
            statuses[task.id] = TaskStatus(task.id, TaskState.COMPLETED, 
                startTime = statuses[task.id]?.startTime, endTime = clock.now())
            results.emit(TaskResult.Success(task.id, result))
        } catch (e: CancellationException) {
            statuses[task.id] = TaskStatus(task.id, TaskState.CANCELLED)
            results.emit(TaskResult.Cancelled(task.id))
        } catch (e: Exception) {
            val status = statuses[task.id]!!
            val newRetryCount = status.retryCount + 1
            
            if (newRetryCount <= task.retryPolicy.maxRetries) {
                statuses[task.id] = status.copy(state = TaskState.QUEUED, retryCount = newRetryCount, lastError = e)
                val delay = calculateBackoff(task.retryPolicy, newRetryCount)
                delay(delay)
                mutex.withLock { taskQueue.offer(queued.copy(enqueuedAt = clock.now())) }
            } else {
                statuses[task.id] = TaskStatus(task.id, TaskState.FAILED, newRetryCount, e, 
                    status.startTime, clock.now())
                results.emit(TaskResult.Failure(task.id, e, false))
            }
        }
    }

    private fun calculateBackoff(policy: RetryPolicy, retryCount: Int): Long {
        val delay = (policy.initialDelayMs * Math.pow(policy.multiplier, (retryCount - 1).toDouble())).toLong()
        return minOf(delay, policy.maxDelayMs)
    }

    override suspend fun cancel(taskId: String): Boolean {
        val removed = mutex.withLock {
            taskQueue.removeIf { it.task.id == taskId }
        }
        if (removed) {
            statuses[taskId] = TaskStatus(taskId, TaskState.CANCELLED)
            results.emit(TaskResult.Cancelled(taskId))
        }
        return removed
    }

    override fun getStatus(taskId: String): TaskStatus? = statuses[taskId]

    override fun getAllStatuses(): List<TaskStatus> = statuses.values.toList()

    override fun getCompletedTasks(): Flow<TaskResult> = results.asSharedFlow()

    override suspend fun shutdown() {
        isRunning = false
        scope.cancel()
    }

    private data class QueuedTask(val task: Task, val enqueuedAt: Long)
}
