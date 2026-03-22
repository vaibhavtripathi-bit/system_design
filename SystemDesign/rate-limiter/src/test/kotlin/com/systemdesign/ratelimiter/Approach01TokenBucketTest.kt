package com.systemdesign.ratelimiter

import com.systemdesign.ratelimiter.approach_01_token_bucket.TokenBucketRateLimiter
import com.systemdesign.ratelimiter.approach_01_token_bucket.SuspendingTokenBucketRateLimiter
import com.systemdesign.ratelimiter.common.FakeTimeSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Approach01TokenBucketTest {

    @Test
    fun `allows requests within rate limit`() {
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 5
        )
        
        repeat(5) {
            assertTrue(limiter.tryAcquire(), "Request $it should be allowed")
        }
    }

    @Test
    fun `rejects requests when bucket empty`() {
        val timeSource = FakeTimeSource()
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 3,
            timeSource = timeSource
        )
        
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `refills tokens over time`() {
        val timeSource = FakeTimeSource()
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 5,
            timeSource = timeSource
        )
        
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
        
        timeSource.advanceTime(100)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `burst size limits token accumulation`() {
        val timeSource = FakeTimeSource()
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 5,
            timeSource = timeSource
        )
        
        timeSource.advanceTime(1000)
        
        var allowed = 0
        while (limiter.tryAcquire()) allowed++
        
        assertEquals(5, allowed, "Should allow exactly burst size after long wait")
    }

    @Test
    fun `tryAcquire with multiple permits`() {
        val timeSource = FakeTimeSource()
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 10,
            timeSource = timeSource
        )
        
        assertTrue(limiter.tryAcquire(5))
        assertTrue(limiter.tryAcquire(5))
        assertFalse(limiter.tryAcquire(1))
    }

    @Test
    fun `getState returns correct values`() {
        val timeSource = FakeTimeSource()
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 5,
            timeSource = timeSource
        )
        
        var state = limiter.getState()
        assertEquals(5.0, state.availablePermits, 0.01)
        assertEquals(5.0, state.maxPermits, 0.01)
        
        limiter.tryAcquire(3)
        state = limiter.getState()
        assertEquals(2.0, state.availablePermits, 0.01)
    }

    @Test
    fun `rejects invalid constructor parameters`() {
        assertThrows<IllegalArgumentException> {
            TokenBucketRateLimiter(permitsPerSecond = 0.0, maxBurstSize = 5)
        }
        assertThrows<IllegalArgumentException> {
            TokenBucketRateLimiter(permitsPerSecond = -1.0, maxBurstSize = 5)
        }
        assertThrows<IllegalArgumentException> {
            TokenBucketRateLimiter(permitsPerSecond = 10.0, maxBurstSize = 0)
        }
    }

    @Test
    fun `rejects invalid permits parameter`() {
        val limiter = TokenBucketRateLimiter(permitsPerSecond = 10.0, maxBurstSize = 5)
        assertThrows<IllegalArgumentException> { limiter.tryAcquire(0) }
        assertThrows<IllegalArgumentException> { limiter.tryAcquire(-1) }
    }

    @Test
    fun `thread safety under concurrent access`() {
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 1000.0,
            maxBurstSize = 100
        )
        
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1000)
        val allowed = AtomicInteger(0)
        val errors = AtomicInteger(0)
        
        repeat(1000) {
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
        assertTrue(allowed.get() > 0)
    }

    @Test
    fun `suspending rate limiter works`() = runTest {
        val timeSource = FakeTimeSource()
        val limiter = SuspendingTokenBucketRateLimiter(
            permitsPerSecond = 10.0,
            maxBurstSize = 3,
            timeSource = timeSource
        )
        
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }
}
