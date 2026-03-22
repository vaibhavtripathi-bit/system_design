/**
 * # Approach 03: Bulkhead Pattern (Isolation)
 *
 * ## Pattern Used
 * Semaphore-based concurrency limiting to isolate failures.
 * Named after ship bulkheads that prevent flooding from spreading.
 *
 * ## Trade-offs
 * - **Pros:** Prevents resource exhaustion, isolates failures, predictable load
 * - **Cons:** May reject requests under load, needs capacity planning
 *
 * ## When to Prefer
 * - Multiple external dependencies
 * - When isolation is critical
 */
package com.systemdesign.httpclient.approach_03_bulkhead

import com.systemdesign.httpclient.common.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class BulkheadConfig(
    val maxConcurrent: Int = 10,
    val maxWaitingMs: Long = 1000,
    val name: String = "default"
)

class Bulkhead(
    private val config: BulkheadConfig = BulkheadConfig()
) : ResiliencePolicy {

    private val semaphore = Semaphore(config.maxConcurrent)
    private val activeCount = AtomicInteger(0)
    private val waitingCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)

    override suspend fun <T> execute(block: suspend () -> T): T {
        waitingCount.incrementAndGet()
        try {
            return withTimeout(config.maxWaitingMs) {
                semaphore.withPermit {
                    waitingCount.decrementAndGet()
                    activeCount.incrementAndGet()
                    try {
                        block()
                    } finally {
                        activeCount.decrementAndGet()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            waitingCount.decrementAndGet()
            rejectedCount.incrementAndGet()
            throw BulkheadRejectedException("Bulkhead ${config.name} rejected request: timeout waiting for permit")
        }
    }

    fun getMetrics(): BulkheadMetrics = BulkheadMetrics(
        name = config.name,
        maxConcurrent = config.maxConcurrent,
        activeCount = activeCount.get(),
        waitingCount = waitingCount.get(),
        availablePermits = config.maxConcurrent - activeCount.get(),
        rejectedCount = rejectedCount.get()
    )
}

data class BulkheadMetrics(
    val name: String,
    val maxConcurrent: Int,
    val activeCount: Int,
    val waitingCount: Int,
    val availablePermits: Int,
    val rejectedCount: Int
)

class BulkheadRejectedException(message: String) : Exception(message)

class BulkheadRegistry {
    private val bulkheads = ConcurrentHashMap<String, Bulkhead>()

    fun getOrCreate(name: String, config: BulkheadConfig = BulkheadConfig(name = name)): Bulkhead {
        return bulkheads.getOrPut(name) { Bulkhead(config) }
    }

    fun get(name: String): Bulkhead? = bulkheads[name]

    fun getAllMetrics(): Map<String, BulkheadMetrics> {
        return bulkheads.mapValues { it.value.getMetrics() }
    }
}

class IsolatedHttpClient(
    private val engine: HttpEngine,
    private val bulkheadRegistry: BulkheadRegistry = BulkheadRegistry()
) {
    suspend fun execute(request: HttpRequest, bulkheadName: String = "default"): HttpResult {
        val bulkhead = bulkheadRegistry.getOrCreate(bulkheadName)
        return try {
            val response = bulkhead.execute { engine.execute(request) }
            HttpResult.Success(response)
        } catch (e: BulkheadRejectedException) {
            HttpResult.Failure(e)
        } catch (e: Exception) {
            HttpResult.Failure(e)
        }
    }

    fun getMetrics(): Map<String, BulkheadMetrics> = bulkheadRegistry.getAllMetrics()
}

class CompositeResiliencePolicy(
    private val policies: List<ResiliencePolicy>
) : ResiliencePolicy {
    
    override suspend fun <T> execute(block: suspend () -> T): T {
        return policies.foldRight(block) { policy, acc ->
            { policy.execute(acc) }
        }()
    }
}

fun resilience(vararg policies: ResiliencePolicy): ResiliencePolicy {
    return CompositeResiliencePolicy(policies.toList())
}
