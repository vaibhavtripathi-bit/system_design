/**
 * # Approach 01: Simple Background Task
 *
 * ## Pattern Used
 * Basic coroutine-based background execution.
 *
 * ## Trade-offs
 * - **Pros:** Simple, low overhead
 * - **Cons:** No persistence, no retry, lost on process death
 */
package com.systemdesign.backgroundtask.approach_01_simple

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TaskInfo(
    val id: String,
    val name: String,
    val status: TaskStatus,
    val progress: Float = 0f,
    val error: Throwable? = null
)

enum class TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

class SimpleBackgroundTaskRunner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, TaskInfo>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val taskIdCounter = AtomicInteger(0)

    fun <T> execute(
        name: String,
        block: suspend (updateProgress: (Float) -> Unit) -> T
    ): String {
        val id = "task-${taskIdCounter.incrementAndGet()}"
        tasks[id] = TaskInfo(id, name, TaskStatus.PENDING)
        
        val job = scope.launch {
            tasks[id] = tasks[id]!!.copy(status = TaskStatus.RUNNING)
            
            try {
                block { progress ->
                    tasks[id] = tasks[id]!!.copy(progress = progress)
                }
                tasks[id] = tasks[id]!!.copy(status = TaskStatus.COMPLETED, progress = 1f)
            } catch (e: CancellationException) {
                tasks[id] = tasks[id]!!.copy(status = TaskStatus.CANCELLED)
                throw e
            } catch (e: Exception) {
                tasks[id] = tasks[id]!!.copy(status = TaskStatus.FAILED, error = e)
            }
        }
        
        jobs[id] = job
        return id
    }

    fun cancel(taskId: String): Boolean {
        val job = jobs[taskId] ?: return false
        job.cancel()
        tasks[taskId] = tasks[taskId]!!.copy(status = TaskStatus.CANCELLED)
        return true
    }

    fun getStatus(taskId: String): TaskInfo? = tasks[taskId]

    fun getAllTasks(): List<TaskInfo> = tasks.values.toList()

    suspend fun await(taskId: String) {
        jobs[taskId]?.join()
    }

    fun shutdown() {
        scope.cancel()
    }
}
