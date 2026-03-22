package com.systemdesign.ratelimiter

import com.systemdesign.ratelimiter.approach_03_sliding_window.SlidingWindowLogRateLimiter
import com.systemdesign.ratelimiter.approach_03_sliding_window.SlidingWindowCounterRateLimiter
import com.systemdesign.ratelimiter.common.FakeTimeSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach03SlidingWindowTest {

    @Test
    fun `log - allows requests within limit`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed")
        }
    }

    @Test
    fun `log - rejects requests exceeding limit`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 3,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `log - window slides correctly`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
        
        timeSource.advanceTime(500)
        assertFalse(limiter.tryAcquire())
        
        timeSource.advanceTime(600)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `log - handles boundary correctly no burst`() {
        val timeSource = FakeTimeSource(0)
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 10,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        timeSource.setTime(900)
        repeat(10) { assertTrue(limiter.tryAcquire()) }
        
        timeSource.setTime(1100)
        assertFalse(limiter.tryAcquire())
        
        timeSource.setTime(1901)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `log - getState returns correct values`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 10,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        var state = limiter.getState()
        assertEquals(10.0, state.availablePermits, 0.01)
        assertEquals(0, state.requestsInWindow)
        
        limiter.tryAcquire(4)
        state = limiter.getState()
        assertEquals(6.0, state.availablePermits, 0.01)
        assertEquals(4, state.requestsInWindow)
    }

    @Test
    fun `log - tracks statistics`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 3,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(3) { limiter.tryAcquire() }
        limiter.tryAcquire()
        limiter.tryAcquire()
        
        val stats = limiter.getStats()
        assertEquals(3, stats.totalRequests)
        assertEquals(2, stats.rejectedRequests)
    }

    @Test
    fun `log - rejects invalid parameters`() {
        assertThrows<IllegalArgumentException> {
            SlidingWindowLogRateLimiter(maxRequests = 0, windowSizeMs = 1000)
        }
        assertThrows<IllegalArgumentException> {
            SlidingWindowLogRateLimiter(maxRequests = 10, windowSizeMs = 0)
        }
    }

    @Test
    fun `counter - allows requests within limit`() {
        val timeSource = FakeTimeSource()
        val limiter = SlidingWindowCounterRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed")
        }
    }

    @Test
    fun `counter - uses weighted calculation across windows`() {
        val timeSource = FakeTimeSource(0)
        val limiter = SlidingWindowCounterRateLimiter(
            maxRequests = 10,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        
        timeSource.setTime(1500)
        
        val state = limiter.getState()
        assertTrue(state.requestsInWindow < 5, "Should account for partial previous window")
    }

    @Test
    fun `counter - resets after two windows`() {
        val timeSource = FakeTimeSource(0)
        val limiter = SlidingWindowCounterRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
        
        timeSource.setTime(2500)
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Should allow after 2 windows: request $it")
        }
    }

    @Test
    fun `log - thread safety`() {
        val limiter = SlidingWindowLogRateLimiter(
            maxRequests = 100,
            windowSizeMs = 1000
        )
        
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(500)
        val errors = AtomicInteger(0)
        
        repeat(500) {
            executor.submit {
                try {
                    limiter.tryAcquire()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        
        latch.await()
        executor.shutdown()
        
        assertEquals(0, errors.get())
    }
}
