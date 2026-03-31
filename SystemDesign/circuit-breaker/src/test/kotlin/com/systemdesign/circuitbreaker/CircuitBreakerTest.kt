package com.systemdesign.circuitbreaker

import com.systemdesign.circuitbreaker.approach_01_count_based.CountBasedCircuitBreaker
import com.systemdesign.circuitbreaker.approach_02_time_based.TimeBasedCircuitBreaker
import com.systemdesign.circuitbreaker.approach_02_time_based.TimeBasedConfig
import com.systemdesign.circuitbreaker.approach_03_adaptive.AdaptiveCircuitBreaker
import com.systemdesign.circuitbreaker.approach_03_adaptive.AdaptiveConfig
import com.systemdesign.circuitbreaker.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CircuitBreakerTest {

    private fun failN(breaker: CircuitBreaker, n: Int) {
        repeat(n) {
            runCatching { breaker.execute { throw RuntimeException("fail") } }
        }
    }

    @Nested
    inner class CountBasedTests {

        @Test
        fun `starts in CLOSED state`() {
            val cb = CountBasedCircuitBreaker()
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `successful calls keep circuit CLOSED`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
            repeat(10) {
                assertEquals("ok", cb.execute { "ok" })
            }
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `trips to OPEN after consecutive failures reach threshold`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
            failN(cb, 3)
            assertEquals(CircuitState.OPEN, cb.state)
        }

        @Test
        fun `rejects calls when OPEN`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 2))
            failN(cb, 2)
            assertThrows<CircuitBreakerOpenException> { cb.execute { "nope" } }
        }

        @Test
        fun `success resets consecutive failure count`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
            failN(cb, 2)
            cb.execute { "ok" }
            failN(cb, 2)
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `transitions to HALF_OPEN after timeout`() {
            val time = FakeTimeSource()
            val cb = CountBasedCircuitBreaker(
                CircuitBreakerConfig(failureThreshold = 2, timeoutMs = 5000),
                timeSource = time
            )
            failN(cb, 2)
            assertEquals(CircuitState.OPEN, cb.state)

            time.advanceTime(5000)
            assertEquals(CircuitState.HALF_OPEN, cb.state)
        }

        @Test
        fun `HALF_OPEN closes after enough successes`() {
            val time = FakeTimeSource()
            val cb = CountBasedCircuitBreaker(
                CircuitBreakerConfig(failureThreshold = 2, successThreshold = 2, timeoutMs = 1000),
                timeSource = time
            )
            failN(cb, 2)
            time.advanceTime(1000)

            cb.execute { "ok" }
            assertEquals(CircuitState.HALF_OPEN, cb.state)
            cb.execute { "ok" }
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `HALF_OPEN reopens on failure`() {
            val time = FakeTimeSource()
            val cb = CountBasedCircuitBreaker(
                CircuitBreakerConfig(failureThreshold = 2, timeoutMs = 1000),
                timeSource = time
            )
            failN(cb, 2)
            time.advanceTime(1000)

            runCatching { cb.execute { throw RuntimeException("fail") } }
            assertEquals(CircuitState.OPEN, cb.state)
        }

        @Test
        fun `HALF_OPEN limits concurrent trial calls`() {
            val time = FakeTimeSource()
            val cb = CountBasedCircuitBreaker(
                CircuitBreakerConfig(failureThreshold = 1, timeoutMs = 100, halfOpenMaxCalls = 1),
                timeSource = time
            )
            failN(cb, 1)
            time.advanceTime(100)

            assertEquals(CircuitState.HALF_OPEN, cb.state)
            cb.execute { "ok" }
        }

        @Test
        fun `reset returns to CLOSED`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))
            failN(cb, 1)
            assertEquals(CircuitState.OPEN, cb.state)
            cb.reset()
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `metrics track totals correctly`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 5))
            repeat(3) { cb.execute { "ok" } }
            failN(cb, 2)

            val m = cb.getMetrics()
            assertEquals(5L, m.totalRequests)
            assertEquals(3L, m.totalSuccesses)
            assertEquals(2L, m.totalFailures)
        }

        @Test
        fun `observer receives state change events`() {
            val transitions = mutableListOf<Pair<CircuitState, CircuitState>>()
            val observer = object : NoOpObserver() {
                override fun onStateChange(from: CircuitState, to: CircuitState) {
                    transitions.add(from to to)
                }
            }
            val cb = CountBasedCircuitBreaker(
                CircuitBreakerConfig(failureThreshold = 1),
                observer = observer
            )
            failN(cb, 1)
            assertEquals(listOf(CircuitState.CLOSED to CircuitState.OPEN), transitions)
        }

        @Test
        fun `thread safety under concurrent access`() {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 100))
            val executor = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(200)
            val errors = AtomicInteger(0)

            repeat(200) { i ->
                executor.submit {
                    try {
                        if (i % 2 == 0) cb.execute { "ok" }
                        else runCatching { cb.execute { throw RuntimeException("fail") } }
                    } catch (e: Exception) {
                        if (e !is CircuitBreakerOpenException) errors.incrementAndGet()
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
        fun `executeSuspend works with coroutines`() = runTest {
            val cb = CountBasedCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
            val result = cb.executeSuspend { "async-ok" }
            assertEquals("async-ok", result)
        }

        @Test
        fun `rejects invalid config`() {
            assertThrows<IllegalArgumentException> { CircuitBreakerConfig(failureThreshold = 0) }
            assertThrows<IllegalArgumentException> { CircuitBreakerConfig(successThreshold = -1) }
            assertThrows<IllegalArgumentException> { CircuitBreakerConfig(timeoutMs = 0) }
            assertThrows<IllegalArgumentException> { CircuitBreakerConfig(halfOpenMaxCalls = 0) }
        }
    }

    @Nested
    inner class TimeBasedTests {

        @Test
        fun `starts in CLOSED state`() {
            val cb = TimeBasedCircuitBreaker()
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `does not trip below minimum calls`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(failureRateThreshold = 0.5, minimumCallsInWindow = 5),
                timeSource = time
            )
            failN(cb, 4)
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `trips when failure rate exceeds threshold`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 4,
                    windowSizeMs = 10_000
                ),
                timeSource = time
            )
            cb.execute { "ok" }
            time.advanceTime(100)
            failN(cb, 3)
            assertEquals(CircuitState.OPEN, cb.state)
        }

        @Test
        fun `stays CLOSED when failure rate is below threshold`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 4,
                    windowSizeMs = 10_000
                ),
                timeSource = time
            )
            repeat(3) {
                cb.execute { "ok" }
                time.advanceTime(100)
            }
            failN(cb, 1)
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `old records expire out of window`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 4,
                    windowSizeMs = 1000
                ),
                timeSource = time
            )
            failN(cb, 3)
            time.advanceTime(1100)
            cb.execute { "ok" }
            time.advanceTime(10)
            assertEquals(CircuitState.CLOSED, cb.state)
            assertTrue(cb.currentFailureRate() < 0.5)
        }

        @Test
        fun `transitions to HALF_OPEN after timeout`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 2,
                    timeoutMs = 5000
                ),
                timeSource = time
            )
            failN(cb, 2)
            assertEquals(CircuitState.OPEN, cb.state)

            time.advanceTime(5000)
            assertEquals(CircuitState.HALF_OPEN, cb.state)
        }

        @Test
        fun `HALF_OPEN closes after successes`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 2,
                    successThreshold = 2,
                    timeoutMs = 1000
                ),
                timeSource = time
            )
            failN(cb, 2)
            time.advanceTime(1000)

            cb.execute { "ok" }
            cb.execute { "ok" }
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `HALF_OPEN reopens on failure`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.5,
                    minimumCallsInWindow = 2,
                    timeoutMs = 1000
                ),
                timeSource = time
            )
            failN(cb, 2)
            time.advanceTime(1000)

            runCatching { cb.execute { throw RuntimeException("fail") } }
            assertEquals(CircuitState.OPEN, cb.state)
        }

        @Test
        fun `metrics reflect windowed counts`() {
            val time = FakeTimeSource()
            val cb = TimeBasedCircuitBreaker(
                TimeBasedConfig(
                    failureRateThreshold = 0.9,
                    minimumCallsInWindow = 100,
                    windowSizeMs = 1000
                ),
                timeSource = time
            )
            repeat(5) { cb.execute { "ok" } }
            time.advanceTime(10)
            failN(cb, 3)

            val m = cb.getMetrics()
            assertEquals(8L, m.totalRequests)
            assertEquals(5, m.successCount)
            assertEquals(3, m.failureCount)
        }

        @Test
        fun `executeSuspend works`() = runTest {
            val cb = TimeBasedCircuitBreaker()
            assertEquals("async", cb.executeSuspend { "async" })
        }

        @Test
        fun `rejects invalid config`() {
            assertThrows<IllegalArgumentException> { TimeBasedConfig(failureRateThreshold = 1.5) }
            assertThrows<IllegalArgumentException> { TimeBasedConfig(windowSizeMs = 0) }
            assertThrows<IllegalArgumentException> { TimeBasedConfig(minimumCallsInWindow = 0) }
        }
    }

    @Nested
    inner class AdaptiveTests {

        @Test
        fun `starts in CLOSED state`() {
            val cb = AdaptiveCircuitBreaker()
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `trips when failure rate exceeds adaptive threshold`() {
            val time = FakeTimeSource()
            val cb = AdaptiveCircuitBreaker(
                AdaptiveConfig(
                    baseFailureRateThreshold = 0.5,
                    minimumCallsInWindow = 4,
                    windowSizeMs = 10_000
                ),
                timeSource = time
            )
            cb.execute { "ok" }
            time.advanceTime(100)
            failN(cb, 3)
            assertEquals(CircuitState.OPEN, cb.state)
        }

        @Test
        fun `timeout increases with exponential backoff on repeated trips`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.5,
                minimumCallsInWindow = 2,
                baseTimeoutMs = 1000,
                maxTimeoutMs = 10_000,
                backoffMultiplier = 2.0,
                successBatchForRampUp = 1,
                halfOpenMaxPermits = 1
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            failN(cb, 2)
            val firstTimeout = cb.effectiveTimeoutMs
            time.advanceTime(firstTimeout)

            runCatching { cb.execute { throw RuntimeException("fail") } }
            val secondTimeout = cb.effectiveTimeoutMs
            assertTrue(secondTimeout > firstTimeout, "Timeout should increase: $secondTimeout > $firstTimeout")
        }

        @Test
        fun `effective threshold tightens after trips`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.5,
                minimumCallsInWindow = 2,
                baseTimeoutMs = 100,
                backoffDecayFactor = 0.5,
                successBatchForRampUp = 1,
                halfOpenMaxPermits = 1
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            val thresholdBefore = cb.effectiveFailureThreshold
            failN(cb, 2)
            time.advanceTime(200)
            runCatching { cb.execute { throw RuntimeException("fail") } }

            val thresholdAfter = cb.effectiveFailureThreshold
            assertTrue(thresholdAfter < thresholdBefore,
                "Threshold should tighten: $thresholdAfter < $thresholdBefore")
        }

        @Test
        fun `HALF_OPEN ramps up permits on consecutive successes`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.5,
                minimumCallsInWindow = 2,
                baseTimeoutMs = 100,
                halfOpenInitialPermits = 1,
                halfOpenMaxPermits = 4,
                successBatchForRampUp = 2
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            failN(cb, 2)
            time.advanceTime(200)
            assertEquals(CircuitState.HALF_OPEN, cb.state)

            cb.execute { "ok" }
            cb.execute { "ok" }
            assertEquals(CircuitState.HALF_OPEN, cb.state)

            cb.execute { "ok" }
            cb.execute { "ok" }
            assertEquals(CircuitState.HALF_OPEN, cb.state)

            cb.execute { "ok" }
            cb.execute { "ok" }
            assertEquals(CircuitState.CLOSED, cb.state)
        }

        @Test
        fun `HALF_OPEN failure reopens and increments trip count`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.5,
                minimumCallsInWindow = 2,
                baseTimeoutMs = 100
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            failN(cb, 2)
            time.advanceTime(100)
            runCatching { cb.execute { throw RuntimeException("fail") } }
            assertEquals(CircuitState.OPEN, cb.state)

            val timeout = cb.effectiveTimeoutMs
            assertTrue(timeout > config.baseTimeoutMs)
        }

        @Test
        fun `EWMA smooths failure rate`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.9,
                minimumCallsInWindow = 100,
                ewmaAlpha = 0.3
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            repeat(10) { cb.execute { "ok" } }
            val rateAfterSuccesses = cb.smoothedFailureRate
            assertTrue(rateAfterSuccesses < 0.01, "EWMA should be near 0 after all successes")

            failN(cb, 1)
            val rateAfterOneFailure = cb.smoothedFailureRate
            assertTrue(rateAfterOneFailure > rateAfterSuccesses)
            assertTrue(rateAfterOneFailure < 0.5, "Single failure shouldn't spike EWMA to 0.5")
        }

        @Test
        fun `timeout is capped at maxTimeoutMs`() {
            val time = FakeTimeSource()
            val config = AdaptiveConfig(
                baseFailureRateThreshold = 0.5,
                minimumCallsInWindow = 2,
                baseTimeoutMs = 1000,
                maxTimeoutMs = 5000,
                backoffMultiplier = 10.0,
                successBatchForRampUp = 1,
                halfOpenMaxPermits = 1
            )
            val cb = AdaptiveCircuitBreaker(config, timeSource = time)

            repeat(3) {
                failN(cb, 2)
                time.advanceTime(config.maxTimeoutMs)
                runCatching { cb.execute { throw RuntimeException("fail") } }
                time.advanceTime(config.maxTimeoutMs)
            }

            assertTrue(cb.effectiveTimeoutMs <= config.maxTimeoutMs)
        }

        @Test
        fun `reset clears all adaptive state`() {
            val time = FakeTimeSource()
            val cb = AdaptiveCircuitBreaker(
                AdaptiveConfig(
                    baseFailureRateThreshold = 0.5,
                    minimumCallsInWindow = 2,
                    baseTimeoutMs = 100
                ),
                timeSource = time
            )
            failN(cb, 2)
            cb.reset()

            assertEquals(CircuitState.CLOSED, cb.state)
            assertEquals(0.0, cb.smoothedFailureRate, 0.001)
            assertEquals(cb.effectiveTimeoutMs, 100L)
        }

        @Test
        fun `concurrent access is safe`() {
            val cb = AdaptiveCircuitBreaker(
                AdaptiveConfig(
                    baseFailureRateThreshold = 0.9,
                    minimumCallsInWindow = 1000
                )
            )
            val executor = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(200)
            val errors = AtomicInteger(0)

            repeat(200) { i ->
                executor.submit {
                    try {
                        if (i % 3 == 0) runCatching { cb.execute { throw RuntimeException("fail") } }
                        else cb.execute { "ok" }
                    } catch (e: Exception) {
                        if (e !is CircuitBreakerOpenException) errors.incrementAndGet()
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
        fun `executeSuspend works`() = runTest {
            val cb = AdaptiveCircuitBreaker()
            assertEquals("async", cb.executeSuspend { "async" })
        }

        @Test
        fun `rejects invalid config`() {
            assertThrows<IllegalArgumentException> { AdaptiveConfig(baseFailureRateThreshold = -0.1) }
            assertThrows<IllegalArgumentException> { AdaptiveConfig(maxTimeoutMs = 1, baseTimeoutMs = 100) }
            assertThrows<IllegalArgumentException> { AdaptiveConfig(backoffMultiplier = 0.5) }
            assertThrows<IllegalArgumentException> { AdaptiveConfig(ewmaAlpha = 1.5) }
        }
    }
}
