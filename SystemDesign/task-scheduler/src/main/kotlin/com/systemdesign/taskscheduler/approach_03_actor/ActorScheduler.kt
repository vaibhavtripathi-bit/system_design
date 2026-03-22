/**
 * # Approach 03: Actor-Based Task Scheduler
 *
 * ## Pattern Used
 * Actor model using Kotlin channels for message-passing concurrency.
 * Each component is an actor processing messages sequentially.
 *
 * ## Trade-offs
 * - **Pros:** No shared mutable state, natural concurrency, easy to reason about
 * - **Cons:** Message passing overhead, more complex debugging
 *
 * ## When to Prefer
 * - Complex concurrent workflows
 * - When avoiding locks/shared state is important
 */
package com.systemdesign.taskscheduler.approach_03_actor

import com.systemdesign.taskscheduler.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

sealed class SchedulerMessage {
    data class Schedule(val task: Task, val executor: TaskExecutor, val response: CompletableDeferred<String>) : SchedulerMessage()
    data class Cancel(val taskId: String, val response: CompletableDeferred<Boolean>) : SchedulerMessage()
    data class GetStatus(val taskId: String, val response: CompletableDeferred<TaskStatus?>) : SchedulerMessage()
    data class TaskCompleted(val taskId: String, val result: Any?) : SchedulerMessage()
    data class TaskFailed(val taskId: String, val error: Throwable, val retryCount: Int) : SchedulerMessage()
    object Shutdown : SchedulerMessage()
}

class ActorScheduler(
    private val concurrency: Int = 4,
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : TaskScheduler {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mailbox = Channel<SchedulerMessage>(Channel.UNLIMITED)
    
    private val tasks = mutableMapOf<String, Task>()
    private val executors = mutableMapOf<String, TaskExecutor>()
    private val statuses = mutableMapOf<String, TaskStatus>()
    private val pendingTasks = ArrayDeque<String>()
    private var runningCount = 0
    
    private val results = MutableSharedFlow<TaskResult>(extraBufferCapacity = 100)
    private var isRunning = true
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch { processMessages() }
    }

    private suspend fun processMessages() {
        for (message in mailbox) {
            if (!isRunning && message !is SchedulerMessage.Shutdown) continue
            
            when (message) {
                is SchedulerMessage.Schedule -> handleSchedule(message)
                is SchedulerMessage.Cancel -> handleCancel(message)
                is SchedulerMessage.GetStatus -> handleGetStatus(message)
                is SchedulerMessage.TaskCompleted -> handleTaskCompleted(message)
                is SchedulerMessage.TaskFailed -> handleTaskFailed(message)
                is SchedulerMessage.Shutdown -> {
                    isRunning = false
                    break
                }
            }
            
            processQueue()
        }
    }

    private fun handleSchedule(message: SchedulerMessage.Schedule) {
        val task = message.task
        tasks[task.id] = task
        executors[task.id] = message.executor
        statuses[task.id] = TaskStatus(task.id, TaskState.QUEUED)
        pendingTasks.addLast(task.id)
        message.response.complete(task.id)
    }

    private suspend fun handleCancel(message: SchedulerMessage.Cancel) {
        val taskId = message.taskId
        val removed = pendingTasks.remove(taskId)
        activeJobs[taskId]?.cancel()
        
        if (removed || activeJobs.containsKey(taskId)) {
            statuses[taskId] = TaskStatus(taskId, TaskState.CANCELLED)
            results.emit(TaskResult.Cancelled(taskId))
            message.response.complete(true)
        } else {
            message.response.complete(false)
        }
    }

    private fun handleGetStatus(message: SchedulerMessage.GetStatus) {
        message.response.complete(statuses[message.taskId])
    }

    private suspend fun handleTaskCompleted(message: SchedulerMessage.TaskCompleted) {
        runningCount--
        activeJobs.remove(message.taskId)
        statuses[message.taskId] = TaskStatus(message.taskId, TaskState.COMPLETED,
            startTime = statuses[message.taskId]?.startTime, endTime = clock.now())
        results.emit(TaskResult.Success(message.taskId, message.result))
    }

    private suspend fun handleTaskFailed(message: SchedulerMessage.TaskFailed) {
        runningCount--
        activeJobs.remove(message.taskId)
        
        val task = tasks[message.taskId]!!
        val newRetryCount = message.retryCount
        
        if (newRetryCount <= task.retryPolicy.maxRetries) {
            statuses[message.taskId] = TaskStatus(message.taskId, TaskState.QUEUED, newRetryCount, message.error)
            pendingTasks.addFirst(message.taskId)
        } else {
            statuses[message.taskId] = TaskStatus(message.taskId, TaskState.FAILED, newRetryCount, message.error,
                statuses[message.taskId]?.startTime, clock.now())
            results.emit(TaskResult.Failure(message.taskId, message.error, false))
        }
    }

    private fun processQueue() {
        while (runningCount < concurrency && pendingTasks.isNotEmpty()) {
            val taskId = pendingTasks.removeFirst()
            val task = tasks[taskId] ?: continue
            val executor = executors[taskId] ?: continue
            
            runningCount++
            statuses[taskId] = TaskStatus(taskId, TaskState.RUNNING, 
                statuses[taskId]?.retryCount ?: 0, startTime = clock.now())
            
            val job = scope.launch {
                try {
                    val status = statuses[taskId]
                    if (status?.retryCount ?: 0 > 0) {
                        val delay = calculateBackoff(task.retryPolicy, status?.retryCount ?: 0)
                        delay(delay)
                    }
                    val result = executor.execute(task)
                    mailbox.send(SchedulerMessage.TaskCompleted(taskId, result))
                } catch (e: CancellationException) {
                    statuses[taskId] = TaskStatus(taskId, TaskState.CANCELLED)
                    results.emit(TaskResult.Cancelled(taskId))
                    runningCount--
                } catch (e: Exception) {
                    val retryCount = (statuses[taskId]?.retryCount ?: 0) + 1
                    mailbox.send(SchedulerMessage.TaskFailed(taskId, e, retryCount))
                }
            }
            activeJobs[taskId] = job
        }
    }

    private fun calculateBackoff(policy: RetryPolicy, retryCount: Int): Long {
        val delay = (policy.initialDelayMs * Math.pow(policy.multiplier, (retryCount - 1).toDouble())).toLong()
        return minOf(delay, policy.maxDelayMs)
    }

    override suspend fun schedule(task: Task, executor: TaskExecutor): String {
        val response = CompletableDeferred<String>()
        mailbox.send(SchedulerMessage.Schedule(task, executor, response))
        return response.await()
    }

    override suspend fun cancel(taskId: String): Boolean {
        val response = CompletableDeferred<Boolean>()
        mailbox.send(SchedulerMessage.Cancel(taskId, response))
        return response.await()
    }

    override fun getStatus(taskId: String): TaskStatus? = statuses[taskId]

    override fun getAllStatuses(): List<TaskStatus> = statuses.values.toList()

    override fun getCompletedTasks(): Flow<TaskResult> = results.asSharedFlow()

    override suspend fun shutdown() {
        mailbox.send(SchedulerMessage.Shutdown)
        activeJobs.values.forEach { it.cancel() }
        scope.cancel()
    }
}
