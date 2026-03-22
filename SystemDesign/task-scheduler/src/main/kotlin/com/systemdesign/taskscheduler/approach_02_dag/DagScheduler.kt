/**
 * # Approach 02: DAG-Based Task Scheduler
 *
 * ## Pattern Used
 * Directed Acyclic Graph (DAG) for dependency-aware scheduling.
 * Tasks execute only when all dependencies are satisfied.
 *
 * ## Trade-offs
 * - **Pros:** Handles complex dependencies, parallel execution of independent tasks
 * - **Cons:** More complex, cycle detection needed, overhead for simple tasks
 *
 * ## When to Prefer
 * - Tasks with dependencies (build systems, data pipelines)
 * - When execution order matters
 */
package com.systemdesign.taskscheduler.approach_02_dag

import com.systemdesign.taskscheduler.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class DagScheduler(
    private val concurrency: Int = 4,
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : TaskScheduler {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    
    private val tasks = ConcurrentHashMap<String, Task>()
    private val executors = ConcurrentHashMap<String, TaskExecutor>()
    private val statuses = ConcurrentHashMap<String, TaskStatus>()
    private val completedTasks = ConcurrentHashMap.newKeySet<String>()
    
    private val results = MutableSharedFlow<TaskResult>(extraBufferCapacity = 100)
    private var isRunning = true
    private var processingJob: Job? = null

    override suspend fun schedule(task: Task, executor: TaskExecutor): String {
        if (wouldCreateCycle(task)) {
            throw IllegalArgumentException("Task ${task.id} would create a dependency cycle")
        }
        
        tasks[task.id] = task
        executors[task.id] = executor
        
        val initialState = if (task.dependencies.isEmpty()) TaskState.QUEUED else TaskState.WAITING_DEPENDENCIES
        statuses[task.id] = TaskStatus(task.id, initialState)
        
        startProcessingIfNeeded()
        return task.id
    }

    private fun wouldCreateCycle(newTask: Task): Boolean {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()
        
        fun hasCycle(taskId: String): Boolean {
            if (stack.contains(taskId)) return true
            if (visited.contains(taskId)) return false
            
            visited.add(taskId)
            stack.add(taskId)
            
            val task = if (taskId == newTask.id) newTask else tasks[taskId]
            task?.dependencies?.forEach { depId ->
                if (hasCycle(depId)) return true
            }
            
            stack.remove(taskId)
            return false
        }
        
        return hasCycle(newTask.id)
    }

    private fun startProcessingIfNeeded() {
        if (processingJob?.isActive == true) return
        
        processingJob = scope.launch {
            while (isRunning) {
                val readyTasks = findReadyTasks()
                if (readyTasks.isEmpty()) {
                    delay(10)
                    continue
                }
                
                readyTasks.take(concurrency).map { task ->
                    launch { executeTask(task) }
                }.forEach { it.join() }
            }
        }
    }

    private suspend fun findReadyTasks(): List<Task> = mutex.withLock {
        tasks.values.filter { task ->
            val status = statuses[task.id]
            (status?.state == TaskState.QUEUED || status?.state == TaskState.WAITING_DEPENDENCIES) &&
                task.dependencies.all { completedTasks.contains(it) }
        }.also { readyTasks ->
            readyTasks.forEach { task ->
                statuses[task.id] = statuses[task.id]!!.copy(state = TaskState.QUEUED)
            }
        }
    }

    private suspend fun executeTask(task: Task) {
        val executor = executors[task.id] ?: return
        
        mutex.withLock {
            statuses[task.id] = TaskStatus(task.id, TaskState.RUNNING, startTime = clock.now())
        }
        
        try {
            val result = executor.execute(task)
            mutex.withLock {
                completedTasks.add(task.id)
                statuses[task.id] = TaskStatus(task.id, TaskState.COMPLETED,
                    startTime = statuses[task.id]?.startTime, endTime = clock.now())
            }
            results.emit(TaskResult.Success(task.id, result))
        } catch (e: CancellationException) {
            mutex.withLock {
                statuses[task.id] = TaskStatus(task.id, TaskState.CANCELLED)
            }
            results.emit(TaskResult.Cancelled(task.id))
        } catch (e: Exception) {
            val status = statuses[task.id]!!
            val newRetryCount = status.retryCount + 1
            
            if (newRetryCount <= task.retryPolicy.maxRetries) {
                val delay = calculateBackoff(task.retryPolicy, newRetryCount)
                delay(delay)
                mutex.withLock {
                    statuses[task.id] = status.copy(state = TaskState.QUEUED, retryCount = newRetryCount, lastError = e)
                }
            } else {
                mutex.withLock {
                    statuses[task.id] = TaskStatus(task.id, TaskState.FAILED, newRetryCount, e,
                        status.startTime, clock.now())
                }
                results.emit(TaskResult.Failure(task.id, e, false))
                cancelDependentTasks(task.id)
            }
        }
    }

    private suspend fun cancelDependentTasks(failedTaskId: String) {
        tasks.values.filter { it.dependencies.contains(failedTaskId) }.forEach { task ->
            statuses[task.id] = TaskStatus(task.id, TaskState.CANCELLED)
            results.emit(TaskResult.Cancelled(task.id))
            cancelDependentTasks(task.id)
        }
    }

    private fun calculateBackoff(policy: RetryPolicy, retryCount: Int): Long {
        val delay = (policy.initialDelayMs * Math.pow(policy.multiplier, (retryCount - 1).toDouble())).toLong()
        return minOf(delay, policy.maxDelayMs)
    }

    override suspend fun cancel(taskId: String): Boolean = mutex.withLock {
        val task = tasks[taskId] ?: return false
        statuses[taskId] = TaskStatus(taskId, TaskState.CANCELLED)
        results.emit(TaskResult.Cancelled(taskId))
        cancelDependentTasks(taskId)
        true
    }

    override fun getStatus(taskId: String): TaskStatus? = statuses[taskId]

    override fun getAllStatuses(): List<TaskStatus> = statuses.values.toList()

    override fun getCompletedTasks(): Flow<TaskResult> = results.asSharedFlow()

    override suspend fun shutdown() {
        isRunning = false
        processingJob?.cancel()
        scope.cancel()
    }

    fun getExecutionOrder(): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        
        fun visit(taskId: String) {
            if (visited.contains(taskId)) return
            visited.add(taskId)
            tasks[taskId]?.dependencies?.forEach { visit(it) }
            result.add(taskId)
        }
        
        tasks.keys.forEach { visit(it) }
        return result
    }
}
