package com.systemdesign.ratelimiter

import com.systemdesign.ratelimiter.approach_04_fixed_window.FixedWindowRateLimiter
import com.systemdesign.ratelimiter.approach_04_fixed_window.AtomicFixedWindowRateLimiter
import com.systemdesign.ratelimiter.approach_04_fixed_window.PerKeyFixedWindowRateLimiter
import com.systemdesign.ratelimiter.common.FakeTimeSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach04FixedWindowTest {

    @Test
    fun `allows requests within limit`() {
        val timeSource = FakeTimeSource()
        val limiter = FixedWindowRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed")
        }
    }

    @Test
    fun `rejects requests exceeding limit`() {
        val timeSource = FakeTimeSource()
        val limiter = FixedWindowRateLimiter(
            maxRequests = 3,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `resets at window boundary`() {
        val timeSource = FakeTimeSource(0)
        val limiter = FixedWindowRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
        
        timeSource.setTime(1000)
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed after window reset")
        }
    }

    @Test
    fun `demonstrates boundary burst problem`() {
        val timeSource = FakeTimeSource(0)
        val limiter = FixedWindowRateLimiter(
            maxRequests = 10,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        timeSource.setTime(999)
        repeat(10) { assertTrue(limiter.tryAcquire()) }
        
        timeSource.setTime(1001)
        repeat(10) { assertTrue(limiter.tryAcquire()) }
    }

    @Test
    fun `getState returns correct values`() {
        val timeSource = FakeTimeSource()
        val limiter = FixedWindowRateLimiter(
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
    fun `getTimeUntilReset works correctly`() {
        val timeSource = FakeTimeSource(100)
        val limiter = FixedWindowRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        limiter.tryAcquire()
        
        val timeUntilReset = limiter.getTimeUntilReset()
        assertTrue(timeUntilReset > 0)
        assertTrue(timeUntilReset <= 1000)
    }

    @Test
    fun `tracks statistics`() {
        val timeSource = FakeTimeSource()
        val limiter = FixedWindowRateLimiter(
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
    fun `rejects invalid parameters`() {
        assertThrows<IllegalArgumentException> {
            FixedWindowRateLimiter(maxRequests = 0, windowSizeMs = 1000)
        }
        assertThrows<IllegalArgumentException> {
            FixedWindowRateLimiter(maxRequests = 10, windowSizeMs = 0)
        }
    }

    @Test
    fun `atomic - works correctly`() {
        val timeSource = FakeTimeSource()
        val limiter = AtomicFixedWindowRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `atomic - resets at boundary`() {
        val timeSource = FakeTimeSource(0)
        val limiter = AtomicFixedWindowRateLimiter(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        
        timeSource.setTime(1000)
        repeat(5) { assertTrue(limiter.tryAcquire()) }
    }

    @Test
    fun `atomic - thread safety`() {
        val limiter = AtomicFixedWindowRateLimiter(
            maxRequests = 1000,
            windowSizeMs = 10000
        )
        
        val executor = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(2000)
        val allowed = AtomicInteger(0)
        val errors = AtomicInteger(0)
        
        repeat(2000) {
            executor.submit {
                try {
                    if (limiter.tryAcquire()) allowed.incrementAndGet()
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
        assertEquals(1000, allowed.get())
    }

    @Test
    fun `per-key - independent limits per key`() {
        val timeSource = FakeTimeSource()
        val limiter = PerKeyFixedWindowRateLimiter<String>(
            maxRequests = 3,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(3) { assertTrue(limiter.tryAcquire("user1")) }
        assertFalse(limiter.tryAcquire("user1"))
        
        repeat(3) { assertTrue(limiter.tryAcquire("user2")) }
        assertFalse(limiter.tryAcquire("user2"))
    }

    @Test
    fun `per-key - resets at window boundary`() {
        val timeSource = FakeTimeSource(0)
        val limiter = PerKeyFixedWindowRateLimiter<String>(
            maxRequests = 3,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(3) { limiter.tryAcquire("user1") }
        
        timeSource.setTime(1000)
        repeat(3) { assertTrue(limiter.tryAcquire("user1")) }
    }

    @Test
    fun `per-key - getState returns per-key state`() {
        val timeSource = FakeTimeSource()
        val limiter = PerKeyFixedWindowRateLimiter<String>(
            maxRequests = 10,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire("user1") }
        repeat(3) { limiter.tryAcquire("user2") }
        
        val state1 = limiter.getState("user1")
        val state2 = limiter.getState("user2")
        
        assertEquals(5.0, state1.availablePermits, 0.01)
        assertEquals(7.0, state2.availablePermits, 0.01)
    }

    @Test
    fun `per-key - cleanup removes old entries`() {
        val timeSource = FakeTimeSource(0)
        val limiter = PerKeyFixedWindowRateLimiter<String>(
            maxRequests = 5,
            windowSizeMs = 1000,
            timeSource = timeSource
        )
        
        limiter.tryAcquire("user1")
        limiter.tryAcquire("user2")
        
        timeSource.setTime(2500)
        limiter.cleanup()
        
        val state = limiter.getState("user1")
        assertEquals(5.0, state.availablePermits, 0.01)
    }

    @Test
    fun `regular - thread safety`() {
        val limiter = FixedWindowRateLimiter(
            maxRequests = 100,
            windowSizeMs = 10000
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
