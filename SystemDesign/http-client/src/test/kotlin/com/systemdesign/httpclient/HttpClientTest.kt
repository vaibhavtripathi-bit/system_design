package com.systemdesign.httpclient

import com.systemdesign.httpclient.common.*
import com.systemdesign.httpclient.approach_01_retry.*
import com.systemdesign.httpclient.approach_02_circuit_breaker.*
import com.systemdesign.httpclient.approach_03_bulkhead.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HttpClientTest {

    class MockHttpEngine(
        private val responses: List<() -> HttpResponse> = listOf { HttpResponse(200) },
        private val delayMs: Long = 0
    ) : HttpEngine {
        val callCount = AtomicInteger(0)
        
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (delayMs > 0) delay(delayMs)
            val idx = callCount.getAndIncrement()
            return responses.getOrElse(idx) { responses.last() }()
        }
    }

    class FailingEngine(private val failCount: Int) : HttpEngine {
        val callCount = AtomicInteger(0)
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (callCount.incrementAndGet() <= failCount) {
                throw IOException("Simulated failure")
            }
            return HttpResponse(200)
        }
    }

    class AlwaysFailingEngine : HttpEngine {
        val callCount = AtomicInteger(0)
        override suspend fun execute(request: HttpRequest): HttpResponse {
            callCount.incrementAndGet()
            throw IOException("Always fails")
        }
    }

    // Retry Tests
    @Test
    fun `retry - succeeds without retry`() = runBlocking {
        val engine = MockHttpEngine()
        val client = RetryingHttpClient(engine)
        
        val result = client.execute(HttpRequest("GET", "http://test.com"))
        
        assertTrue(result is HttpResult.Success)
        assertEquals(1, engine.callCount.get())
    }

    @Test
    fun `retry - retries on failure then succeeds`() = runBlocking {
        val engine = FailingEngine(2)
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        val client = RetryingHttpClient(engine, RetryPolicy(config))
        
        val result = client.execute(HttpRequest("GET", "http://test.com"))
        
        assertTrue(result is HttpResult.Success)
        assertEquals(3, engine.callCount.get())
    }

    @Test
    fun `retry - fails after max retries`() = runBlocking {
        val engine = AlwaysFailingEngine()
        val config = RetryConfig(maxRetries = 2, initialDelayMs = 10)
        val client = RetryingHttpClient(engine, RetryPolicy(config))
        
        val result = client.execute(HttpRequest("GET", "http://test.com"))
        
        assertTrue(result is HttpResult.Failure)
        assertEquals(3, engine.callCount.get())
    }

    // Circuit Breaker Tests
    @Test
    fun `circuit breaker - stays closed on success`() = runBlocking {
        val engine = MockHttpEngine()
        val cb = CircuitBreaker()
        val client = CircuitBreakingHttpClient(engine, cb)
        
        repeat(10) { client.execute(HttpRequest("GET", "http://test.com")) }
        
        assertEquals(CircuitState.CLOSED, client.getCircuitState())
    }

    @Test
    fun `circuit breaker - opens after failures`() = runBlocking {
        val engine = AlwaysFailingEngine()
        val config = CircuitBreakerConfig(failureThreshold = 3)
        val cb = CircuitBreaker(config)
        val client = CircuitBreakingHttpClient(engine, cb)
        
        repeat(5) { client.execute(HttpRequest("GET", "http://test.com")) }
        
        assertEquals(CircuitState.OPEN, client.getCircuitState())
    }

    @Test
    fun `circuit breaker - rejects when open`() = runBlocking {
        val engine = AlwaysFailingEngine()
        val config = CircuitBreakerConfig(failureThreshold = 1, openDurationMs = 60000)
        val cb = CircuitBreaker(config)
        val client = CircuitBreakingHttpClient(engine, cb)
        
        client.execute(HttpRequest("GET", "http://test.com"))
        
        val callsBefore = engine.callCount.get()
        val result = client.execute(HttpRequest("GET", "http://test.com"))
        
        assertTrue(result is HttpResult.Failure)
        assertTrue((result as HttpResult.Failure).error is CircuitOpenException)
        assertEquals(callsBefore, engine.callCount.get())
    }

    @Test
    fun `circuit breaker - transitions to half-open after timeout`() = runBlocking {
        val clock = FakeClock()
        val engine = AlwaysFailingEngine()
        val config = CircuitBreakerConfig(failureThreshold = 1, openDurationMs = 1000)
        val cb = CircuitBreaker(config, clock)
        val client = CircuitBreakingHttpClient(engine, cb)
        
        client.execute(HttpRequest("GET", "http://test.com"))
        assertEquals(CircuitState.OPEN, cb.getState())
        
        clock.advance(1001)
        client.execute(HttpRequest("GET", "http://test.com"))
        
        assertEquals(CircuitState.OPEN, cb.getState())
    }

    @Test
    fun `circuit breaker - closes after success in half-open`() = runBlocking {
        val clock = FakeClock()
        val failingThenSuccessEngine = object : HttpEngine {
            val calls = AtomicInteger(0)
            override suspend fun execute(request: HttpRequest): HttpResponse {
                return if (calls.incrementAndGet() <= 1) {
                    throw IOException("Fail")
                } else {
                    HttpResponse(200)
                }
            }
        }
        val config = CircuitBreakerConfig(failureThreshold = 1, successThreshold = 2, openDurationMs = 100)
        val cb = CircuitBreaker(config, clock)
        val client = CircuitBreakingHttpClient(failingThenSuccessEngine, cb)
        
        client.execute(HttpRequest("GET", "http://test.com"))
        assertEquals(CircuitState.OPEN, cb.getState())
        
        clock.advance(101)
        
        client.execute(HttpRequest("GET", "http://test.com"))
        client.execute(HttpRequest("GET", "http://test.com"))
        
        assertEquals(CircuitState.CLOSED, cb.getState())
    }

    // Bulkhead Tests
    @Test
    fun `bulkhead - allows concurrent calls within limit`() = runBlocking {
        val engine = MockHttpEngine(delayMs = 50)
        val config = BulkheadConfig(maxConcurrent = 5)
        val bulkhead = Bulkhead(config)
        val registry = BulkheadRegistry()
        registry.getOrCreate("test", config)
        val client = IsolatedHttpClient(engine, registry)
        
        val results = (1..5).map {
            async { client.execute(HttpRequest("GET", "http://test.com"), "test") }
        }.awaitAll()
        
        assertTrue(results.all { it is HttpResult.Success })
    }

    @Test
    fun `bulkhead - rejects when over limit`() = runBlocking {
        val engine = MockHttpEngine(delayMs = 500)
        val config = BulkheadConfig(maxConcurrent = 2, maxWaitingMs = 100)
        val bulkhead = Bulkhead(config)
        
        val results = (1..5).map { idx ->
            async {
                try {
                    bulkhead.execute { engine.execute(HttpRequest("GET", "http://test.com")) }
                    "success"
                } catch (e: BulkheadRejectedException) {
                    "rejected"
                }
            }
        }.awaitAll()
        
        assertTrue(results.contains("rejected"))
    }

    @Test
    fun `bulkhead - metrics track usage`() = runBlocking {
        val config = BulkheadConfig(maxConcurrent = 5, name = "test")
        val bulkhead = Bulkhead(config)
        
        val metrics = bulkhead.getMetrics()
        
        assertEquals("test", metrics.name)
        assertEquals(5, metrics.maxConcurrent)
        assertEquals(0, metrics.activeCount)
    }

    @Test
    fun `bulkhead registry - creates and retrieves bulkheads`() = runBlocking {
        val registry = BulkheadRegistry()
        
        val b1 = registry.getOrCreate("api", BulkheadConfig(maxConcurrent = 10, name = "api"))
        val b2 = registry.getOrCreate("api")
        
        assertSame(b1, b2)
    }

    // Composite Policy Tests
    @Test
    fun `composite - chains policies`() = runBlocking {
        val engine = FailingEngine(1)
        val retryPolicy = RetryPolicy(RetryConfig(maxRetries = 2, initialDelayMs = 10))
        val circuitBreaker = CircuitBreaker()
        
        val composite = resilience(circuitBreaker, retryPolicy)
        
        val result = composite.execute { engine.execute(HttpRequest("GET", "http://test.com")) }
        
        assertEquals(200, result.statusCode)
        assertEquals(CircuitState.CLOSED, circuitBreaker.getState())
    }
}
