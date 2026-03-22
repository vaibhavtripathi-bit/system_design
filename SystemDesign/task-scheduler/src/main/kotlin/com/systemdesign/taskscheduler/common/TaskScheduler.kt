/**
 * Common interfaces for Task Scheduler implementations.
 */
package com.systemdesign.taskscheduler.common

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Represents a schedulable task.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val dependencies: Set<String> = emptySet(),
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val deadline: Long? = null,
    val metadata: Map<String, Any> = emptyMap()
)

enum class TaskPriority(val value: Int) {
    LOW(0), NORMAL(1), HIGH(2), CRITICAL(3)
}

data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 60000,
    val multiplier: Double = 2.0
)

/**
 * Result of task execution.
 */
sealed class TaskResult {
    data class Success(val taskId: String, val result: Any? = null) : TaskResult()
    data class Failure(val taskId: String, val error: Throwable, val retryable: Boolean = true) : TaskResult()
    data class Cancelled(val taskId: String) : TaskResult()
}

/**
 * Task execution state.
 */
enum class TaskState {
    PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, WAITING_DEPENDENCIES
}

/**
 * Task status with execution details.
 */
data class TaskStatus(
    val taskId: String,
    val state: TaskState,
    val retryCount: Int = 0,
    val lastError: Throwable? = null,
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * Task executor interface.
 */
interface TaskExecutor {
    suspend fun execute(task: Task): Any?
}

/**
 * Core scheduler interface.
 */
interface TaskScheduler {
    suspend fun schedule(task: Task, executor: TaskExecutor): String
    suspend fun cancel(taskId: String): Boolean
    fun getStatus(taskId: String): TaskStatus?
    fun getAllStatuses(): List<TaskStatus>
    fun getCompletedTasks(): Flow<TaskResult>
    suspend fun shutdown()
}

/**
 * Clock interface for testability.
 */
interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FakeClock(private var time: Long = 0) : Clock {
    override fun now(): Long = time
    fun advance(ms: Long) { time += ms }
    fun set(ms: Long) { time = ms }
}
