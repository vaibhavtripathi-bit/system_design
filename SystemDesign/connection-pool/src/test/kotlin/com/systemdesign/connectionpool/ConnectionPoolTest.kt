package com.systemdesign.connectionpool

import com.systemdesign.connectionpool.approach_01_blocking.BlockingConnectionPool
import com.systemdesign.connectionpool.approach_02_coroutine.CoroutineConnectionPool
import com.systemdesign.connectionpool.approach_03_adaptive.AdaptiveConfig
import com.systemdesign.connectionpool.approach_03_adaptive.AdaptiveConnectionPool
import com.systemdesign.connectionpool.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class FakeConnection(
    override val id: String = UUID.randomUUID().toString(),
    override val createdAt: Long = System.currentTimeMillis(),
    private val valid: AtomicBoolean = AtomicBoolean(true)
) : Poolable {
    val closed = AtomicBoolean(false)

    override fun isValid(): Boolean = valid.get() && !closed.get()
    override fun close() { closed.set(true) }
    fun invalidate() { valid.set(false) }
}

class RecordingObserver : PoolObserver {
    val acquires = AtomicInteger(0)
    val releases = AtomicInteger(0)
    val evictions = AtomicInteger(0)
    val timeouts = AtomicInteger(0)
    val validationFailures = AtomicInteger(0)
    val resizes = mutableListOf<Pair<Int, Int>>()

    override fun onAcquire(resourceId: String) { acquires.incrementAndGet() }
    override fun onRelease(resourceId: String) { releases.incrementAndGet() }
    override fun onEvict(resourceId: String) { evictions.incrementAndGet() }
    override fun onTimeout(waitMs: Long) { timeouts.incrementAndGet() }
    override fun onValidationFailure(resourceId: String, error: Throwable) {
        validationFailures.incrementAndGet()
    }
    override fun onPoolResized(oldSize: Int, newSize: Int) {
        synchronized(resizes) { resizes.add(oldSize to newSize) }
    }
}

class ConnectionPoolTest {

    @Nested
    @DisplayName("Blocking Connection Pool")
    inner class BlockingPoolTests {

        private fun pool(
            config: PoolConfig = PoolConfig(minSize = 2, maxSize = 5, acquireTimeoutMs = 500),
            observer: PoolObserver = object : PoolObserver {}
        ) = BlockingConnectionPool(config, factory = { FakeConnection() }, observer = observer)

        @Test
        fun `pre-fills to minSize on creation`() {
            val p = pool()
            assertEquals(2, p.stats().totalConnections)
            assertEquals(2, p.stats().idleConnections)
            p.close()
        }

        @Test
        fun `acquire returns a resource and marks it active`() {
            val p = pool()
            val conn = p.acquire()
            assertNotNull(conn)
            assertEquals(1, p.stats().activeConnections)
            p.release(conn)
            p.close()
        }

        @Test
        fun `release returns resource to idle pool`() {
            val p = pool()
            val conn = p.acquire()
            p.release(conn)
            assertEquals(0, p.stats().activeConnections)
            assertEquals(2, p.stats().idleConnections)
            p.close()
        }

        @Test
        fun `grows up to maxSize under demand`() {
            val p = pool()
            val acquired = (1..5).map { p.acquire() }
            assertEquals(5, p.stats().totalConnections)
            acquired.forEach { p.release(it) }
            p.close()
        }

        @Test
        fun `throws on timeout when pool exhausted`() {
            val p = pool()
            val acquired = (1..5).map { p.acquire() }
            assertThrows<IllegalStateException> { p.acquire() }
            acquired.forEach { p.release(it) }
            p.close()
        }

        @Test
        fun `concurrent acquire and release is thread-safe`() {
            val p = pool(PoolConfig(minSize = 2, maxSize = 10, acquireTimeoutMs = 2000))
            val latch = CountDownLatch(20)
            val errors = AtomicInteger(0)

            repeat(20) {
                Thread {
                    try {
                        val c = p.acquire()
                        Thread.sleep(10)
                        p.release(c)
                    } catch (_: Exception) {
                        errors.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }.start()
            }

            latch.await()
            assertEquals(0, errors.get())
            p.close()
        }

        @Test
        fun `evictIdle removes stale connections`() {
            val config = PoolConfig(minSize = 0, maxSize = 5, idleTimeoutMs = 1, acquireTimeoutMs = 500)
            val p = pool(config)
            val conn = p.acquire()
            p.release(conn)
            Thread.sleep(50)

            p.evictIdle()

            assertEquals(0, p.stats().totalConnections)
            p.close()
        }

        @Test
        fun `validateIdle removes invalid connections and refills`() {
            val config = PoolConfig(minSize = 2, maxSize = 5, acquireTimeoutMs = 500)
            val connections = mutableListOf<FakeConnection>()
            val p = BlockingConnectionPool(config, factory = {
                FakeConnection().also { connections.add(it) }
            })

            connections.forEach { it.invalidate() }
            p.validateIdle()

            assertEquals(2, p.stats().totalConnections)
            assertTrue(connections.take(2).all { it.closed.get() })
            p.close()
        }

        @Test
        fun `observer receives acquire and release events`() {
            val obs = RecordingObserver()
            val p = pool(observer = obs)
            val conn = p.acquire()
            p.release(conn)

            assertEquals(1, obs.acquires.get())
            assertEquals(1, obs.releases.get())
            p.close()
        }

        @Test
        fun `close shuts down all resources`() {
            val connections = ConcurrentHashMap.newKeySet<FakeConnection>()
            val config = PoolConfig(minSize = 3, maxSize = 5, acquireTimeoutMs = 500)
            val p = BlockingConnectionPool(config, factory = {
                FakeConnection().also { connections.add(it) }
            })

            p.close()
            assertTrue(connections.all { it.closed.get() })
        }

        @Test
        fun `throws when acquiring from closed pool`() {
            val p = pool()
            p.close()
            assertThrows<IllegalStateException> { p.acquire() }
        }
    }

    @Nested
    @DisplayName("Coroutine Connection Pool")
    inner class CoroutinePoolTests {

        private suspend fun pool(
            config: PoolConfig = PoolConfig(minSize = 2, maxSize = 5, acquireTimeoutMs = 500),
            observer: PoolObserver = object : PoolObserver {},
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): CoroutineConnectionPool<FakeConnection> {
            return CoroutineConnectionPool(config, factory = { FakeConnection() }, observer = observer, scope = scope)
                .also { it.initialize() }
        }

        @Test
        fun `pre-fills to minSize`() = runTest {
            val p = pool()
            assertEquals(2, p.stats().totalConnections)
            p.close()
        }

        @Test
        fun `acquire and release cycle`() = runTest {
            val p = pool()
            val conn = p.acquire()
            assertEquals(1, p.stats().activeConnections)
            p.release(conn)
            assertEquals(0, p.stats().activeConnections)
            p.close()
        }

        @Test
        fun `grows to maxSize`() = runTest {
            val p = pool()
            val acquired = (1..5).map { p.acquire() }
            assertEquals(5, p.stats().totalConnections)
            acquired.forEach { p.release(it) }
            p.close()
        }

        @Test
        fun `throws on timeout when exhausted`() = runTest {
            val p = pool()
            val acquired = (1..5).map { p.acquire() }
            assertThrows<IllegalStateException> { p.acquire() }
            acquired.forEach { p.release(it) }
            p.close()
        }

        @Test
        fun `concurrent coroutine access is safe`() = runTest {
            val p = pool(PoolConfig(minSize = 2, maxSize = 10, acquireTimeoutMs = 5000))
            val results = (1..20).map {
                async(Dispatchers.Default) {
                    val c = p.acquire()
                    delay(10)
                    p.release(c)
                    true
                }
            }
            assertTrue(results.awaitAll().all { it })
            p.close()
        }

        @Test
        fun `maintain evicts expired entries and refills`() = runTest {
            val connections = mutableListOf<FakeConnection>()
            val config = PoolConfig(minSize = 2, maxSize = 5, maxLifetimeMs = 1, acquireTimeoutMs = 500)
            val p = CoroutineConnectionPool(config, factory = {
                FakeConnection().also { connections.add(it) }
            })
            p.initialize()
            delay(50)

            p.maintain()

            assertTrue(p.stats().totalConnections >= 2)
            p.close()
        }

        @Test
        fun `observer receives events`() = runTest {
            val obs = RecordingObserver()
            val p = pool(observer = obs)
            val conn = p.acquire()
            p.release(conn)

            assertEquals(1, obs.acquires.get())
            assertEquals(1, obs.releases.get())
            p.close()
        }

        @Test
        fun `close cancels maintenance and frees resources`() = runTest {
            val connections = ConcurrentHashMap.newKeySet<FakeConnection>()
            val p = CoroutineConnectionPool(
                PoolConfig(minSize = 3, maxSize = 5, acquireTimeoutMs = 500),
                factory = { FakeConnection().also { connections.add(it) } }
            )
            p.initialize()

            p.close()
            assertTrue(connections.all { it.closed.get() })
        }
    }

    @Nested
    @DisplayName("Adaptive Connection Pool")
    inner class AdaptivePoolTests {

        private suspend fun pool(
            adaptiveConfig: AdaptiveConfig = AdaptiveConfig(
                base = PoolConfig(minSize = 2, maxSize = 10, acquireTimeoutMs = 500),
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.3,
                scaleStep = 2,
                tuningIntervalMs = 100_000,
                healthCheckIntervalMs = 100_000
            ),
            observer: PoolObserver = object : PoolObserver {},
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): AdaptiveConnectionPool<FakeConnection> {
            return AdaptiveConnectionPool(adaptiveConfig, factory = { FakeConnection() }, observer = observer, scope = scope)
                .also { it.initialize() }
        }

        @Test
        fun `pre-fills to minSize`() = runTest {
            val p = pool()
            assertEquals(2, p.stats().totalConnections)
            p.close()
        }

        @Test
        fun `acquire and release`() = runTest {
            val p = pool()
            val conn = p.acquire()
            assertEquals(1, p.stats().activeConnections)
            p.release(conn)
            assertEquals(0, p.stats().activeConnections)
            p.close()
        }

        @Test
        fun `scales up under high utilization`() = runTest {
            val obs = RecordingObserver()
            val p = pool(observer = obs)

            val conn1 = p.acquire()
            val conn2 = p.acquire()
            p.tune()

            assertTrue(p.currentTargetSize() > 2)
            assertTrue(obs.resizes.isNotEmpty())

            p.release(conn1)
            p.release(conn2)
            p.close()
        }

        @Test
        fun `scales down when idle`() = runTest {
            val obs = RecordingObserver()
            val cfg = AdaptiveConfig(
                base = PoolConfig(minSize = 2, maxSize = 10, acquireTimeoutMs = 500),
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.3,
                scaleStep = 2,
                tuningIntervalMs = 100_000,
                healthCheckIntervalMs = 100_000
            )
            val p = pool(cfg, obs)

            val conns = (1..6).map { p.acquire() }
            p.tune()
            conns.forEach { p.release(it) }

            p.tune()

            assertTrue(p.currentTargetSize() <= 6)
            p.close()
        }

        @Test
        fun `health check evicts invalid connections`() = runTest {
            val connections = mutableListOf<FakeConnection>()
            val cfg = AdaptiveConfig(
                base = PoolConfig(minSize = 2, maxSize = 10, acquireTimeoutMs = 500),
                tuningIntervalMs = 100_000,
                healthCheckIntervalMs = 100_000
            )
            val p = AdaptiveConnectionPool(cfg, factory = {
                FakeConnection().also { connections.add(it) }
            })
            p.initialize()

            connections.filter { !it.closed.get() }.forEach { it.invalidate() }
            p.healthCheck()

            assertEquals(2, p.stats().totalConnections)
            p.close()
        }

        @Test
        fun `concurrent access under adaptive resizing`() = runTest {
            val p = pool(
                AdaptiveConfig(
                    base = PoolConfig(minSize = 2, maxSize = 20, acquireTimeoutMs = 5000),
                    tuningIntervalMs = 100_000,
                    healthCheckIntervalMs = 100_000
                )
            )

            val results = (1..20).map {
                async(Dispatchers.Default) {
                    val c = p.acquire()
                    delay(10)
                    p.release(c)
                    true
                }
            }

            assertTrue(results.awaitAll().all { it })
            p.close()
        }

        @Test
        fun `observer receives resize events`() = runTest {
            val obs = RecordingObserver()
            val p = pool(observer = obs)

            val conn1 = p.acquire()
            val conn2 = p.acquire()
            p.tune()
            p.release(conn1)
            p.release(conn2)

            assertTrue(obs.resizes.isNotEmpty())
            p.close()
        }

        @Test
        fun `throws on timeout when fully exhausted`() = runTest {
            val cfg = AdaptiveConfig(
                base = PoolConfig(minSize = 1, maxSize = 2, acquireTimeoutMs = 200),
                tuningIntervalMs = 100_000,
                healthCheckIntervalMs = 100_000
            )
            val p = pool(cfg)
            val c1 = p.acquire()
            val c2 = p.acquire()

            assertThrows<IllegalStateException> { p.acquire() }

            p.release(c1)
            p.release(c2)
            p.close()
        }

        @Test
        fun `close cleans up everything`() = runTest {
            val connections = ConcurrentHashMap.newKeySet<FakeConnection>()
            val p = AdaptiveConnectionPool(
                AdaptiveConfig(base = PoolConfig(minSize = 3, maxSize = 5, acquireTimeoutMs = 500)),
                factory = { FakeConnection().also { connections.add(it) } }
            )
            p.initialize()

            p.close()
            assertTrue(connections.all { it.closed.get() })
        }
    }
}
