/**
 * Common interfaces for Analytics & Event Logging Pipeline.
 */
package com.systemdesign.analytics.common

import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class AnalyticsEvent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val properties: Map<String, Any> = emptyMap(),
    val userId: String? = null,
    val sessionId: String? = null
)

data class BatchConfig(
    val maxBatchSize: Int = 100,
    val maxBatchAgeMs: Long = 30000,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000
)

sealed class FlushTrigger {
    object TimeBased : FlushTrigger()
    object SizeBased : FlushTrigger()
    object Lifecycle : FlushTrigger()
    object Manual : FlushTrigger()
}

sealed class SendResult {
    data class Success(val eventCount: Int) : SendResult()
    data class Failure(val error: Throwable, val events: List<AnalyticsEvent>) : SendResult()
    data class PartialSuccess(val sent: Int, val failed: Int, val failedEvents: List<AnalyticsEvent>) : SendResult()
}

interface AnalyticsBackend {
    suspend fun send(events: List<AnalyticsEvent>): SendResult
}

interface EventStorage {
    suspend fun store(event: AnalyticsEvent)
    suspend fun storeAll(events: List<AnalyticsEvent>)
    suspend fun getPending(): List<AnalyticsEvent>
    suspend fun remove(eventIds: List<String>)
    suspend fun clear()
    suspend fun count(): Int
}

interface AnalyticsPipeline {
    fun track(event: AnalyticsEvent)
    suspend fun flush(): SendResult
    fun getPendingCount(): Int
    fun getStats(): PipelineStats
    suspend fun shutdown()
}

data class PipelineStats(
    val totalTracked: Long,
    val totalSent: Long,
    val totalFailed: Long,
    val pendingCount: Int,
    val batchesSent: Long
)

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
