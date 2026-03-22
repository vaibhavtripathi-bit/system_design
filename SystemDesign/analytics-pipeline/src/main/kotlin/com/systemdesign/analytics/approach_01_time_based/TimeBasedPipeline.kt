/**
 * # Approach 01: Time-Based Batching Pipeline
 *
 * ## Pattern Used
 * Events are batched and flushed at regular time intervals.
 *
 * ## Trade-offs
 * - **Pros:** Predictable network usage, simple timing logic
 * - **Cons:** May delay important events, potential data loss on crash
 *
 * ## When to Prefer
 * - When consistent network patterns are desired
 * - Low-volume event streams
 */
package com.systemdesign.analytics.approach_01_time_based

import com.systemdesign.analytics.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class TimeBasedPipeline(
    private val backend: AnalyticsBackend,
    private val storage: EventStorage,
    private val config: BatchConfig = BatchConfig(),
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AnalyticsPipeline {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private val buffer = ConcurrentLinkedQueue<AnalyticsEvent>()
    
    private val totalTracked = AtomicLong(0)
    private val totalSent = AtomicLong(0)
    private val totalFailed = AtomicLong(0)
    private val batchesSent = AtomicLong(0)
    
    private var flushJob: Job? = null
    private var isRunning = true

    init {
        startPeriodicFlush()
    }

    private fun startPeriodicFlush() {
        flushJob = scope.launch {
            while (isRunning) {
                delay(config.maxBatchAgeMs)
                if (buffer.isNotEmpty() || storage.count() > 0) {
                    flush()
                }
            }
        }
    }

    override fun track(event: AnalyticsEvent) {
        buffer.offer(event)
        totalTracked.incrementAndGet()
    }

    override suspend fun flush(): SendResult = mutex.withLock {
        val events = mutableListOf<AnalyticsEvent>()
        while (buffer.isNotEmpty() && events.size < config.maxBatchSize) {
            buffer.poll()?.let { events.add(it) }
        }
        
        events.addAll(storage.getPending().take(config.maxBatchSize - events.size))
        
        if (events.isEmpty()) return SendResult.Success(0)
        
        return sendWithRetry(events)
    }

    private suspend fun sendWithRetry(events: List<AnalyticsEvent>): SendResult {
        var lastError: Throwable? = null
        
        repeat(config.maxRetries) { attempt ->
            try {
                val result = backend.send(events)
                when (result) {
                    is SendResult.Success -> {
                        storage.remove(events.map { it.id })
                        totalSent.addAndGet(events.size.toLong())
                        batchesSent.incrementAndGet()
                        return result
                    }
                    is SendResult.PartialSuccess -> {
                        storage.remove(events.map { it.id } - result.failedEvents.map { it.id }.toSet())
                        storage.storeAll(result.failedEvents)
                        totalSent.addAndGet(result.sent.toLong())
                        totalFailed.addAndGet(result.failed.toLong())
                        batchesSent.incrementAndGet()
                        return result
                    }
                    is SendResult.Failure -> {
                        lastError = result.error
                        if (attempt < config.maxRetries - 1) {
                            delay(config.retryDelayMs * (attempt + 1))
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < config.maxRetries - 1) {
                    delay(config.retryDelayMs * (attempt + 1))
                }
            }
        }
        
        storage.storeAll(events)
        totalFailed.addAndGet(events.size.toLong())
        return SendResult.Failure(lastError ?: RuntimeException("Unknown error"), events)
    }

    override fun getPendingCount(): Int = buffer.size + runBlocking { storage.count() }

    override fun getStats(): PipelineStats = PipelineStats(
        totalTracked = totalTracked.get(),
        totalSent = totalSent.get(),
        totalFailed = totalFailed.get(),
        pendingCount = getPendingCount(),
        batchesSent = batchesSent.get()
    )

    override suspend fun shutdown() {
        isRunning = false
        flushJob?.cancel()
        flush()
        scope.cancel()
    }
}
