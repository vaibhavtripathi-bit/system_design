/**
 * # Approach 03: Hybrid Batching Pipeline
 *
 * ## Pattern Used
 * Combines time, size, and lifecycle triggers for optimal batching.
 * Flushes when: batch full, timeout reached, or lifecycle event.
 *
 * ## Trade-offs
 * - **Pros:** Best of both worlds, handles all scenarios, production-ready
 * - **Cons:** More complex, more configuration options
 *
 * ## When to Prefer
 * - Production applications
 * - When reliability is critical
 */
package com.systemdesign.analytics.approach_03_hybrid

import com.systemdesign.analytics.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class HybridPipeline(
    private val backend: AnalyticsBackend,
    private val storage: EventStorage,
    private val config: BatchConfig = BatchConfig(),
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AnalyticsPipeline {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private val buffer = ConcurrentLinkedQueue<AnalyticsEvent>()
    private val flushSignal = Channel<FlushTrigger>(Channel.CONFLATED)
    
    private val totalTracked = AtomicLong(0)
    private val totalSent = AtomicLong(0)
    private val totalFailed = AtomicLong(0)
    private val batchesSent = AtomicLong(0)
    
    private var lastFlushTime = clock.now()
    private var isRunning = true
    private var timerJob: Job? = null
    private var processingJob: Job? = null

    init {
        startTimerJob()
        startProcessingJob()
    }

    private fun startTimerJob() {
        timerJob = scope.launch {
            while (isRunning) {
                delay(config.maxBatchAgeMs)
                if (buffer.isNotEmpty()) {
                    flushSignal.trySend(FlushTrigger.TimeBased)
                }
            }
        }
    }

    private fun startProcessingJob() {
        processingJob = scope.launch {
            for (trigger in flushSignal) {
                if (!isRunning) break
                doFlush()
            }
        }
    }

    private suspend fun doFlush() {
        flush()
    }

    override fun track(event: AnalyticsEvent) {
        buffer.offer(event)
        totalTracked.incrementAndGet()
        
        if (buffer.size >= config.maxBatchSize) {
            flushSignal.trySend(FlushTrigger.SizeBased)
        }
    }

    fun onLifecycleEvent() {
        flushSignal.trySend(FlushTrigger.Lifecycle)
    }

    override suspend fun flush(): SendResult = mutex.withLock {
        val events = mutableListOf<AnalyticsEvent>()
        while (buffer.isNotEmpty() && events.size < config.maxBatchSize) {
            buffer.poll()?.let { events.add(it) }
        }
        
        events.addAll(storage.getPending().take(config.maxBatchSize - events.size))
        
        if (events.isEmpty()) {
            lastFlushTime = clock.now()
            return SendResult.Success(0)
        }
        
        val result = sendWithRetry(events)
        lastFlushTime = clock.now()
        return result
    }

    private suspend fun sendWithRetry(events: List<AnalyticsEvent>): SendResult {
        var lastError: Throwable? = null
        var delayMs = config.retryDelayMs
        
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
                        val sentIds = events.map { it.id } - result.failedEvents.map { it.id }.toSet()
                        storage.remove(sentIds.toList())
                        storage.storeAll(result.failedEvents)
                        totalSent.addAndGet(result.sent.toLong())
                        totalFailed.addAndGet(result.failed.toLong())
                        batchesSent.incrementAndGet()
                        return result
                    }
                    is SendResult.Failure -> {
                        lastError = result.error
                        if (attempt < config.maxRetries - 1) {
                            delay(delayMs)
                            delayMs *= 2
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < config.maxRetries - 1) {
                    delay(delayMs)
                    delayMs *= 2
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
        timerJob?.cancel()
        processingJob?.cancel()
        flush()
        scope.cancel()
    }
}
