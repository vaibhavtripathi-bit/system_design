/**
 * # Approach 03: WorkManager-Style Background Task
 *
 * ## Pattern Used
 * Constraint-based scheduling with chaining support.
 *
 * ## Trade-offs
 * - **Pros:** Rich constraints, chaining, Android-style API
 * - **Cons:** Most complex, more overhead
 */
package com.systemdesign.backgroundtask.approach_03_workmanager

import com.systemdesign.backgroundtask.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class Constraints(
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresIdle: Boolean = false
)

data class WorkRequest(
    val id: String,
    val type: String,
    val data: Map<String, Any> = emptyMap(),
    val constraints: Constraints = Constraints(),
    val initialDelayMs: Long = 0,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMs: Long = 1000
)

enum class BackoffPolicy { LINEAR, EXPONENTIAL }

sealed class WorkResult {
    object Success : WorkResult()
    object Retry : WorkResult()
    data class Failure(val error: Throwable) : WorkResult()
}

interface Worker {
    val type: String
    suspend fun doWork(data: Map<String, Any>): WorkResult
}

interface ConstraintChecker {
    fun isNetworkAvailable(): Boolean
    fun isCharging(): Boolean
    fun isIdle(): Boolean
}

class DefaultConstraintChecker : ConstraintChecker {
    override fun isNetworkAvailable() = true
    override fun isCharging() = true
    override fun isIdle() = true
}

class WorkManager(
    private val constraintChecker: ConstraintChecker = DefaultConstraintChecker(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val workers = ConcurrentHashMap<String, Worker>()
    private val workQueue = ConcurrentHashMap<String, WorkRequest>()
    private val workStatus = ConcurrentHashMap<String, TaskStatus>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val chains = ConcurrentHashMap<String, List<String>>()

    fun registerWorker(worker: Worker) {
        workers[worker.type] = worker
    }

    fun enqueue(request: WorkRequest): String {
        workQueue[request.id] = request
        workStatus[request.id] = TaskStatus.PENDING
        
        val job = scope.launch {
            if (request.initialDelayMs > 0) {
                delay(request.initialDelayMs)
            }
            executeWork(request)
        }
        
        jobs[request.id] = job
        return request.id
    }

    fun enqueueChain(requests: List<WorkRequest>): String {
        val chainId = "chain-${System.currentTimeMillis()}"
        val workIds = requests.map { it.id }
        chains[chainId] = workIds
        
        scope.launch {
            for (request in requests) {
                workQueue[request.id] = request
                workStatus[request.id] = TaskStatus.PENDING
                executeWork(request)
                
                if (workStatus[request.id] != TaskStatus.COMPLETED) {
                    break
                }
            }
        }
        
        return chainId
    }

    private suspend fun executeWork(request: WorkRequest, retryCount: Int = 0) {
        if (!checkConstraints(request.constraints)) {
            workStatus[request.id] = TaskStatus.PENDING
            return
        }
        
        val worker = workers[request.type] ?: run {
            workStatus[request.id] = TaskStatus.FAILED
            return
        }
        
        workStatus[request.id] = TaskStatus.RUNNING
        
        when (val result = worker.doWork(request.data)) {
            WorkResult.Success -> {
                workStatus[request.id] = TaskStatus.COMPLETED
            }
            WorkResult.Retry -> {
                val delay = calculateBackoff(request, retryCount)
                delay(delay)
                executeWork(request, retryCount + 1)
            }
            is WorkResult.Failure -> {
                workStatus[request.id] = TaskStatus.FAILED
            }
        }
    }

    private fun checkConstraints(constraints: Constraints): Boolean {
        if (constraints.requiresNetwork && !constraintChecker.isNetworkAvailable()) return false
        if (constraints.requiresCharging && !constraintChecker.isCharging()) return false
        if (constraints.requiresIdle && !constraintChecker.isIdle()) return false
        return true
    }

    private fun calculateBackoff(request: WorkRequest, retryCount: Int): Long {
        return when (request.backoffPolicy) {
            BackoffPolicy.LINEAR -> request.backoffDelayMs * (retryCount + 1)
            BackoffPolicy.EXPONENTIAL -> request.backoffDelayMs * (1 shl retryCount)
        }
    }

    fun getWorkStatus(workId: String): TaskStatus? = workStatus[workId]

    fun cancel(workId: String): Boolean {
        jobs[workId]?.cancel()
        workStatus[workId] = TaskStatus.CANCELLED
        return jobs.containsKey(workId)
    }

    fun shutdown() {
        scope.cancel()
    }
}
