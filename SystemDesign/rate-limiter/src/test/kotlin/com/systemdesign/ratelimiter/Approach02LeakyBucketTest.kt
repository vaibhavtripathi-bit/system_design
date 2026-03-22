package com.systemdesign.ratelimiter

import com.systemdesign.ratelimiter.approach_02_leaky_bucket.LeakyBucketRateLimiter
import com.systemdesign.ratelimiter.approach_02_leaky_bucket.QueuedLeakyBucketRateLimiter
import com.systemdesign.ratelimiter.common.FakeTimeSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach02LeakyBucketTest {

    @Test
    fun `allows requests within capacity`() {
        val timeSource = FakeTimeSource()
        val limiter = LeakyBucketRateLimiter(
            capacity = 5,
            leakRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed")
        }
    }

    @Test
    fun `rejects requests when bucket full`() {
        val timeSource = FakeTimeSource()
        val limiter = LeakyBucketRateLimiter(
            capacity = 3,
            leakRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `leaks over time`() {
        val timeSource = FakeTimeSource()
        val limiter = LeakyBucketRateLimiter(
            capacity = 5,
            leakRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
        
        timeSource.advanceTime(100)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `water level tracks correctly`() {
        val timeSource = FakeTimeSource()
        val limiter = LeakyBucketRateLimiter(
            capacity = 10,
            leakRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        assertEquals(0.0, limiter.getCurrentLevel(), 0.01)
        
        limiter.tryAcquire(5)
        assertEquals(5.0, limiter.getCurrentLevel(), 0.01)
        
        timeSource.advanceTime(200)
        assertEquals(3.0, limiter.getCurrentLevel(), 0.01)
    }

    @Test
    fun `getState returns correct values`() {
        val timeSource = FakeTimeSource()
        val limiter = LeakyBucketRateLimiter(
            capacity = 10,
            leakRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        var state = limiter.getState()
        assertEquals(10.0, state.availablePermits, 0.01)
        assertEquals(10.0, state.maxPermits, 0.01)
        
        limiter.tryAcquire(6)
        state = limiter.getState()
        assertEquals(4.0, state.availablePermits, 0.01)
    }

    @Test
    fun `rejects invalid constructor parameters`() {
        assertThrows<IllegalArgumentException> {
            LeakyBucketRateLimiter(capacity = 0, leakRatePerSecond = 10.0)
        }
        assertThrows<IllegalArgumentException> {
            LeakyBucketRateLimiter(capacity = 10, leakRatePerSecond = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            LeakyBucketRateLimiter(capacity = 10, leakRatePerSecond = -1.0)
        }
    }

    @Test
    fun `thread safety under concurrent access`() {
        val limiter = LeakyBucketRateLimiter(
            capacity = 100,
            leakRatePerSecond = 1000.0
        )
        
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1000)
        val errors = AtomicInteger(0)
        
        repeat(1000) {
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

    @Test
    fun `queued leaky bucket enqueues requests`() {
        val timeSource = FakeTimeSource()
        val limiter = QueuedLeakyBucketRateLimiter<String>(
            queueCapacity = 5,
            processRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        assertTrue(limiter.offer("req1"))
        assertTrue(limiter.offer("req2"))
        assertTrue(limiter.offer("req3"))
        
        assertEquals(3, limiter.size())
    }

    @Test
    fun `queued leaky bucket rejects when full`() {
        val timeSource = FakeTimeSource()
        val limiter = QueuedLeakyBucketRateLimiter<String>(
            queueCapacity = 3,
            processRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        assertTrue(limiter.offer("req1"))
        assertTrue(limiter.offer("req2"))
        assertTrue(limiter.offer("req3"))
        assertFalse(limiter.offer("req4"))
    }

    @Test
    fun `queued leaky bucket processes at constant rate`() {
        val timeSource = FakeTimeSource()
        val limiter = QueuedLeakyBucketRateLimiter<String>(
            queueCapacity = 10,
            processRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        limiter.offer("req1")
        limiter.offer("req2")
        
        assertNull(limiter.poll())
        
        timeSource.advanceTime(100)
        assertEquals("req1", limiter.poll())
        
        assertNull(limiter.poll())
        
        timeSource.advanceTime(100)
        assertEquals("req2", limiter.poll())
    }

    @Test
    fun `queued leaky bucket getTimeUntilNextReady`() {
        val timeSource = FakeTimeSource()
        val limiter = QueuedLeakyBucketRateLimiter<String>(
            queueCapacity = 10,
            processRatePerSecond = 10.0,
            timeSource = timeSource
        )
        
        assertEquals(-1, limiter.getTimeUntilNextReady())
        
        limiter.offer("req1")
        assertTrue(limiter.getTimeUntilNextReady() >= 0)
    }
}
