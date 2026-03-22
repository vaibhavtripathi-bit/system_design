/**
 * # Approach 02: Persistent Background Task
 *
 * ## Pattern Used
 * Tasks persist to storage and survive process restart.
 *
 * ## Trade-offs
 * - **Pros:** Survives restarts, retry support
 * - **Cons:** More complex, I/O overhead
 */
package com.systemdesign.backgroundtask.approach_02_persistent

import com.systemdesign.backgroundtask.approach_01_simple.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class PersistentTask(
    val id: String,
    val type: String,
    val data: Map<String, Any>,
    val status: TaskStatus,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttempt: Long? = null,
    val error: String? = null
)

interface TaskStorage {
    suspend fun save(task: PersistentTask)
    suspend fun load(id: String): PersistentTask?
    suspend fun loadAll(): List<PersistentTask>
    suspend fun loadPending(): List<PersistentTask>
    suspend fun delete(id: String)
}

interface TaskExecutor {
    val type: String
    suspend fun execute(data: Map<String, Any>, updateProgress: (Float) -> Unit)
}

class InMemoryTaskStorage : TaskStorage {
    private val tasks = ConcurrentHashMap<String, PersistentTask>()
    
    override suspend fun save(task: PersistentTask) { tasks[task.id] = task }
    override suspend fun load(id: String) = tasks[id]
    override suspend fun loadAll() = tasks.values.toList()
    override suspend fun loadPending() = tasks.values.filter { 
        it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING 
    }
    override suspend fun delete(id: String) { tasks.remove(id) }
}

class PersistentBackgroundTaskRunner(
    private val storage: TaskStorage,
    private val retryDelayMs: Long = 5000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val executors = ConcurrentHashMap<String, TaskExecutor>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun registerExecutor(executor: TaskExecutor) {
        executors[executor.type] = executor
    }

    suspend fun schedule(type: String, data: Map<String, Any>, maxRetries: Int = 3): String {
        val id = "task-${System.currentTimeMillis()}"
        val task = PersistentTask(id, type, data, TaskStatus.PENDING, maxRetries = maxRetries)
        storage.save(task)
        executeTask(task)
        return id
    }

    private fun executeTask(task: PersistentTask) {
        val executor = executors[task.type] ?: return
        
        val job = scope.launch {
            storage.save(task.copy(status = TaskStatus.RUNNING, lastAttempt = System.currentTimeMillis()))
            
            try {
                executor.execute(task.data) { /* progress */ }
                storage.save(task.copy(status = TaskStatus.COMPLETED))
            } catch (e: CancellationException) {
                storage.save(task.copy(status = TaskStatus.CANCELLED))
                throw e
            } catch (e: Exception) {
                handleFailure(task, e)
            }
        }
        
        jobs[task.id] = job
    }

    private suspend fun handleFailure(task: PersistentTask, error: Exception) {
        val newRetryCount = task.retryCount + 1
        
        if (newRetryCount >= task.maxRetries) {
            storage.save(task.copy(
                status = TaskStatus.FAILED,
                retryCount = newRetryCount,
                error = error.message
            ))
        } else {
            delay(retryDelayMs)
            val retryTask = task.copy(
                status = TaskStatus.PENDING,
                retryCount = newRetryCount,
                error = error.message
            )
            storage.save(retryTask)
            executeTask(retryTask)
        }
    }

    suspend fun resumePending() {
        storage.loadPending().forEach { task ->
            executeTask(task.copy(status = TaskStatus.PENDING))
        }
    }

    suspend fun getStatus(taskId: String): PersistentTask? = storage.load(taskId)

    fun cancel(taskId: String): Boolean {
        jobs[taskId]?.cancel()
        scope.launch { 
            storage.load(taskId)?.let { 
                storage.save(it.copy(status = TaskStatus.CANCELLED)) 
            }
        }
        return jobs.containsKey(taskId)
    }

    fun shutdown() {
        scope.cancel()
    }
}
