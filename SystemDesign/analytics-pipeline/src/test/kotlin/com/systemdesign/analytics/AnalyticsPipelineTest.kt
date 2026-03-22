package com.systemdesign.analytics

import com.systemdesign.analytics.common.*
import com.systemdesign.analytics.approach_01_time_based.TimeBasedPipeline
import com.systemdesign.analytics.approach_02_size_based.SizeBasedPipeline
import com.systemdesign.analytics.approach_03_hybrid.HybridPipeline
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AnalyticsPipelineTest {

    class MockBackend(
        private val shouldFail: Boolean = false,
        private val failCount: Int = 0
    ) : AnalyticsBackend {
        val sentEvents = CopyOnWriteArrayList<AnalyticsEvent>()
        val sendCount = AtomicInteger(0)
        private val failures = AtomicInteger(0)
        
        override suspend fun send(events: List<AnalyticsEvent>): SendResult {
            sendCount.incrementAndGet()
            if (shouldFail && failures.incrementAndGet() <= failCount) {
                return SendResult.Failure(RuntimeException("Simulated failure"), events)
            }
            sentEvents.addAll(events)
            return SendResult.Success(events.size)
        }
    }

    class InMemoryStorage : EventStorage {
        private val events = ConcurrentHashMap<String, AnalyticsEvent>()
        
        override suspend fun store(event: AnalyticsEvent) {
            events[event.id] = event
        }
        
        override suspend fun storeAll(events: List<AnalyticsEvent>) {
            events.forEach { store(it) }
        }
        
        override suspend fun getPending(): List<AnalyticsEvent> = events.values.toList()
        
        override suspend fun remove(eventIds: List<String>) {
            eventIds.forEach { events.remove(it) }
        }
        
        override suspend fun clear() = events.clear()
        
        override suspend fun count(): Int = events.size
    }

    // Time-Based Tests
    @Test
    fun `time-based - tracks events`() = runBlocking {
        val backend = MockBackend()
        val pipeline = TimeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchAgeMs = 100))
        
        pipeline.track(AnalyticsEvent(name = "test"))
        
        assertEquals(1, pipeline.getPendingCount())
        pipeline.shutdown()
    }

    @Test
    fun `time-based - flushes on interval`() = runBlocking {
        val backend = MockBackend()
        val pipeline = TimeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchAgeMs = 100))
        
        pipeline.track(AnalyticsEvent(name = "test"))
        
        delay(200)
        
        assertEquals(1, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `time-based - manual flush works`() = runBlocking {
        val backend = MockBackend()
        val pipeline = TimeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchAgeMs = 60000))
        
        pipeline.track(AnalyticsEvent(name = "test1"))
        pipeline.track(AnalyticsEvent(name = "test2"))
        
        pipeline.flush()
        
        assertEquals(2, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `time-based - retries on failure`() = runBlocking {
        val backend = MockBackend(shouldFail = true, failCount = 2)
        val pipeline = TimeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxRetries = 3, retryDelayMs = 10))
        
        pipeline.track(AnalyticsEvent(name = "test"))
        pipeline.flush()
        
        assertEquals(3, backend.sendCount.get())
        assertEquals(1, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `time-based - stats tracking`() = runBlocking {
        val backend = MockBackend()
        val pipeline = TimeBasedPipeline(backend, InMemoryStorage())
        
        repeat(5) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        pipeline.flush()
        
        val stats = pipeline.getStats()
        assertEquals(5, stats.totalTracked)
        assertEquals(5, stats.totalSent)
        assertEquals(1, stats.batchesSent)
        pipeline.shutdown()
    }

    // Size-Based Tests
    @Test
    fun `size-based - tracks events`() = runBlocking {
        val backend = MockBackend()
        val pipeline = SizeBasedPipeline(backend, InMemoryStorage())
        
        pipeline.track(AnalyticsEvent(name = "test"))
        
        assertEquals(1, pipeline.getPendingCount())
        pipeline.shutdown()
    }

    @Test
    fun `size-based - flushes on size threshold`() = runBlocking {
        val backend = MockBackend()
        val pipeline = SizeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchSize = 5))
        
        repeat(5) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        
        delay(100)
        
        assertEquals(5, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `size-based - manual flush with partial batch`() = runBlocking {
        val backend = MockBackend()
        val pipeline = SizeBasedPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchSize = 100))
        
        repeat(3) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        pipeline.flush()
        
        assertEquals(3, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `size-based - stats tracking`() = runBlocking {
        val backend = MockBackend()
        val pipeline = SizeBasedPipeline(backend, InMemoryStorage())
        
        repeat(10) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        pipeline.flush()
        
        val stats = pipeline.getStats()
        assertEquals(10, stats.totalTracked)
        assertEquals(10, stats.totalSent)
        pipeline.shutdown()
    }

    // Hybrid Tests
    @Test
    fun `hybrid - tracks events`() = runBlocking {
        val backend = MockBackend()
        val pipeline = HybridPipeline(backend, InMemoryStorage())
        
        pipeline.track(AnalyticsEvent(name = "test"))
        
        assertEquals(1, pipeline.getPendingCount())
        pipeline.shutdown()
    }

    @Test
    fun `hybrid - flushes on size threshold`() = runBlocking {
        val backend = MockBackend()
        val pipeline = HybridPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchSize = 5, maxBatchAgeMs = 60000))
        
        repeat(5) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        
        delay(100)
        
        assertEquals(5, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `hybrid - flushes on lifecycle`() = runBlocking {
        val backend = MockBackend()
        val pipeline = HybridPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchSize = 100, maxBatchAgeMs = 60000))
        
        repeat(3) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        
        pipeline.onLifecycleEvent()
        delay(100)
        
        assertTrue(backend.sentEvents.size >= 3)
        pipeline.shutdown()
    }

    @Test
    fun `hybrid - retries with exponential backoff`() = runBlocking {
        val backend = MockBackend(shouldFail = true, failCount = 2)
        val pipeline = HybridPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxRetries = 3, retryDelayMs = 10))
        
        pipeline.track(AnalyticsEvent(name = "test"))
        pipeline.flush()
        
        assertEquals(3, backend.sendCount.get())
        assertEquals(1, backend.sentEvents.size)
        pipeline.shutdown()
    }

    @Test
    fun `hybrid - stats tracking`() = runBlocking {
        val backend = MockBackend()
        val pipeline = HybridPipeline(backend, InMemoryStorage())
        
        repeat(5) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        pipeline.flush()
        
        val stats = pipeline.getStats()
        assertEquals(5, stats.totalTracked)
        assertEquals(5, stats.totalSent)
        pipeline.shutdown()
    }

    @Test
    fun `hybrid - shutdown flushes pending`() = runBlocking {
        val backend = MockBackend()
        val pipeline = HybridPipeline(backend, InMemoryStorage(), 
            BatchConfig(maxBatchSize = 100, maxBatchAgeMs = 60000))
        
        repeat(3) { pipeline.track(AnalyticsEvent(name = "test-$it")) }
        
        pipeline.shutdown()
        
        assertEquals(3, backend.sentEvents.size)
    }
}
