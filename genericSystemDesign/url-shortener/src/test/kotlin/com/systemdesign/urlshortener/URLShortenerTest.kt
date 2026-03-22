package com.systemdesign.urlshortener

import com.systemdesign.urlshortener.common.*
import com.systemdesign.urlshortener.approach_01_strategy_generation.*
import com.systemdesign.urlshortener.approach_02_decorator_features.*
import com.systemdesign.urlshortener.approach_03_observer_analytics.*
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDate
import java.time.LocalDateTime

class URLShortenerTest {
    
    @Nested
    inner class IdGenerationStrategiesTest {
        
        @Nested
        inner class Base62StrategyTest {
            
            @Test
            fun `generates unique sequential codes`() {
                val strategy = Base62Strategy(codeLength = 7, initialCounter = 0)
                
                val codes = (1..100).map { strategy.generateId("http://example.com") }
                
                assertEquals(100, codes.distinct().size)
            }
            
            @Test
            fun `codes have consistent length`() {
                val strategy = Base62Strategy(codeLength = 7)
                
                val codes = (1..50).map { strategy.generateId("http://example.com") }
                
                assertTrue(codes.all { it.length == 7 })
            }
            
            @Test
            fun `counter increments correctly`() {
                val strategy = Base62Strategy(initialCounter = 100)
                
                strategy.generateId("http://example.com")
                strategy.generateId("http://example.com")
                
                assertEquals(102, strategy.getCurrentCounter())
            }
            
            @Test
            fun `different URLs get different codes`() {
                val strategy = Base62Strategy()
                
                val code1 = strategy.generateId("http://example1.com")
                val code2 = strategy.generateId("http://example2.com")
                
                assertNotEquals(code1, code2)
            }
        }
        
        @Nested
        inner class HashBasedStrategyTest {
            
            @Test
            fun `generates codes of specified length`() {
                val strategy = HashBasedStrategy(algorithm = "MD5", codeLength = 8)
                
                val code = strategy.generateId("http://example.com")
                
                assertEquals(8, code.length)
            }
            
            @Test
            fun `supports different hash algorithms`() {
                val md5Strategy = HashBasedStrategy(algorithm = "MD5")
                val sha256Strategy = HashBasedStrategy(algorithm = "SHA-256")
                
                val md5Code = md5Strategy.generateId("http://example.com")
                val sha256Code = sha256Strategy.generateId("http://example.com")
                
                assertNotNull(md5Code)
                assertNotNull(sha256Code)
            }
            
            @Test
            fun `same URL generates different codes due to timestamp`() {
                val strategy = HashBasedStrategy()
                
                val code1 = strategy.generateId("http://example.com")
                Thread.sleep(1)
                val code2 = strategy.generateId("http://example.com")
                
                assertNotEquals(code1, code2)
            }
        }
        
        @Nested
        inner class RandomStrategyTest {
            
            @Test
            fun `generates codes of specified length`() {
                val strategy = RandomStrategy(codeLength = 10)
                
                val code = strategy.generateId("http://example.com")
                
                assertEquals(10, code.length)
            }
            
            @Test
            fun `generates alphanumeric codes`() {
                val strategy = RandomStrategy()
                
                val codes = (1..100).map { strategy.generateId("http://example.com") }
                
                assertTrue(codes.all { it.matches(Regex("[0-9A-Za-z]+")) })
            }
            
            @Test
            fun `generates unique codes with high probability`() {
                val strategy = RandomStrategy(codeLength = 10)
                
                val codes = (1..1000).map { strategy.generateId("http://example.com") }
                
                assertEquals(1000, codes.distinct().size)
            }
        }
        
        @Nested
        inner class CustomAliasStrategyTest {
            
            @Test
            fun `uses provided alias`() {
                val strategy = CustomAliasStrategy()
                strategy.setAlias("my-custom-url")
                
                val code = strategy.generateId("http://example.com")
                
                assertEquals("my-custom-url", code)
            }
            
            @Test
            fun `rejects blank alias`() {
                val strategy = CustomAliasStrategy()
                
                assertThrows(IllegalArgumentException::class.java) {
                    strategy.setAlias("")
                }
            }
            
            @Test
            fun `rejects invalid characters`() {
                val strategy = CustomAliasStrategy()
                
                assertThrows(IllegalArgumentException::class.java) {
                    strategy.setAlias("invalid alias!")
                }
            }
            
            @Test
            fun `accepts valid alias with hyphens and underscores`() {
                val strategy = CustomAliasStrategy()
                strategy.setAlias("my_custom-url123")
                
                val code = strategy.generateId("http://example.com")
                
                assertEquals("my_custom-url123", code)
            }
            
            @Test
            fun `throws when alias not set`() {
                val strategy = CustomAliasStrategy()
                
                assertThrows(IllegalStateException::class.java) {
                    strategy.generateId("http://example.com")
                }
            }
        }
    }
    
    @Nested
    inner class CollisionHandlingTest {
        
        @Test
        fun `retry handler generates new code`() {
            val strategy = RandomStrategy()
            val handler = RetryCollisionHandler(maxRetries = 5)
            
            val result = handler.handle("abc123", strategy, "http://example.com", 1)
            
            assertTrue(result is CollisionResult.NewCode)
            assertNotEquals("abc123", (result as CollisionResult.NewCode).code)
        }
        
        @Test
        fun `retry handler fails after max retries`() {
            val handler = RetryCollisionHandler(maxRetries = 3)
            val strategy = RandomStrategy()
            
            val result = handler.handle("abc123", strategy, "http://example.com", 5)
            
            assertTrue(result is CollisionResult.Failed)
        }
        
        @Test
        fun `suffix handler appends attempt number`() {
            val handler = SuffixCollisionHandler()
            val strategy = RandomStrategy()
            
            val result = handler.handle("abc123", strategy, "http://example.com", 1)
            
            assertTrue(result is CollisionResult.NewCode)
            assertEquals("abc123_1", (result as CollisionResult.NewCode).code)
        }
        
        @Test
        fun `shortener handles collision and retries`() {
            val repository = InMemoryURLRepository()
            repository.save(URL(
                id = "1",
                originalUrl = "http://existing.com",
                shortCode = "0000001",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null
            ))
            
            val shortener = StrategyBasedURLShortener(
                repository = repository,
                defaultStrategy = Base62Strategy(initialCounter = 0)
            )
            
            val result = shortener.shorten("http://new.com")
            
            assertTrue(result is ShortenResult.Success)
            val success = result as ShortenResult.Success
            assertNotEquals("0000001", success.url.shortCode)
        }
        
        @Test
        fun `custom alias collision returns duplicate error`() {
            val repository = InMemoryURLRepository()
            repository.save(URL(
                id = "1",
                originalUrl = "http://existing.com",
                shortCode = "my-alias",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null
            ))
            
            val shortener = StrategyBasedURLShortener(repository = repository)
            
            val result = shortener.shorten("http://new.com", customAlias = "my-alias")
            
            assertTrue(result is ShortenResult.DuplicateAlias)
        }
    }
    
    @Nested
    inner class ExpirationCheckingTest {
        
        @Test
        fun `expired URL returns expired result`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withExpiration().build()
            
            val expiredUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now().minusDays(10),
                expiresAt = LocalDateTime.now().minusDays(1),
                userId = null
            )
            repository.save(expiredUrl)
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.Expired)
        }
        
        @Test
        fun `non-expired URL returns success`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withExpiration().build()
            
            val validUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = LocalDateTime.now().plusDays(1),
                userId = null
            )
            repository.save(validUrl)
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.Success)
        }
        
        @Test
        fun `URL without expiration never expires`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withExpiration().build()
            
            val permanentUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now().minusYears(10),
                expiresAt = null,
                userId = null
            )
            repository.save(permanentUrl)
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.Success)
        }
    }
    
    @Nested
    inner class PasswordProtectionTest {
        
        @Test
        fun `password protected URL requires password`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withPasswordProtection().build()
            
            val protectedUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null,
                password = "secret123"
            )
            repository.save(protectedUrl)
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.PasswordRequired)
        }
        
        @Test
        fun `correct password allows access`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withPasswordProtection().build()
            
            val protectedUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null,
                password = "secret123"
            )
            repository.save(protectedUrl)
            
            val result = handler.resolve("abc123", ResolveContext(password = "secret123"))
            
            assertTrue(result is ResolveResult.Success)
        }
        
        @Test
        fun `wrong password denies access`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withPasswordProtection().build()
            
            val protectedUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null,
                password = "secret123"
            )
            repository.save(protectedUrl)
            
            val result = handler.resolve("abc123", ResolveContext(password = "wrongpassword"))
            
            assertTrue(result is ResolveResult.PasswordRequired)
        }
        
        @Test
        fun `URL without password does not require password`() {
            val repository = InMemoryURLRepository()
            val builder = DecoratorChainBuilder(repository)
            val handler = builder.withPasswordProtection().build()
            
            val publicUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null,
                password = null
            )
            repository.save(publicUrl)
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.Success)
        }
    }
    
    @Nested
    inner class ClickTrackingTest {
        
        @Test
        fun `click tracker counts accesses`() {
            val clickTracker = ClickTracker()
            
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "1.1.1.1", null, null))
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "2.2.2.2", null, null))
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "3.3.3.3", null, null))
            
            assertEquals(3, clickTracker.getTotalClicks("abc123"))
        }
        
        @Test
        fun `click tracker separates counts by URL`() {
            val clickTracker = ClickTracker()
            
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "1.1.1.1", null, null))
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "2.2.2.2", null, null))
            clickTracker.onURLAccessed(URLEvent.URLAccessed("xyz789", "3.3.3.3", null, null))
            
            assertEquals(2, clickTracker.getTotalClicks("abc123"))
            assertEquals(1, clickTracker.getTotalClicks("xyz789"))
        }
        
        @Test
        fun `click tracker tracks clicks by day`() {
            val clickTracker = ClickTracker()
            val today = LocalDateTime.now()
            
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "1.1.1.1", null, null, today))
            clickTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "2.2.2.2", null, null, today))
            
            val clicksByDay = clickTracker.getClicksByDay("abc123")
            
            assertEquals(2, clicksByDay[today.toLocalDate()])
        }
        
        @Test
        fun `unique visitor tracker counts distinct IPs`() {
            val visitorTracker = UniqueVisitorTracker()
            
            visitorTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "1.1.1.1", null, null))
            visitorTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "1.1.1.1", null, null))
            visitorTracker.onURLAccessed(URLEvent.URLAccessed("abc123", "2.2.2.2", null, null))
            
            assertEquals(2, visitorTracker.getUniqueVisitors("abc123"))
        }
        
        @Test
        fun `click limit decorator enforces limit`() {
            val repository = InMemoryURLRepository()
            val url = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = null,
                userId = null,
                maxClicks = 2
            )
            repository.save(url)
            
            repository.incrementClicks("abc123")
            repository.incrementClicks("abc123")
            
            val handler = DecoratorChainBuilder(repository)
                .withClickLimit()
                .build()
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.ClickLimitExceeded)
        }
    }
    
    @Nested
    inner class AnalyticsAggregationTest {
        
        @Test
        fun `referrer tracker extracts domain`() {
            val referrerTracker = ReferrerTracker()
            
            referrerTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "1.1.1.1", null, "https://google.com/search?q=test"
            ))
            referrerTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "2.2.2.2", null, "https://twitter.com/user"
            ))
            referrerTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "3.3.3.3", null, null
            ))
            
            val stats = referrerTracker.getReferrerStats("abc123")
            
            assertEquals(1, stats["google.com"])
            assertEquals(1, stats["twitter.com"])
            assertEquals(1, stats["direct"])
        }
        
        @Test
        fun `device tracker categorizes user agents`() {
            val deviceTracker = DeviceTracker()
            
            deviceTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "1.1.1.1", "Mozilla/5.0 (iPhone; Mobile)", null
            ))
            deviceTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "2.2.2.2", "Mozilla/5.0 (Windows NT 10.0)", null
            ))
            deviceTracker.onURLAccessed(URLEvent.URLAccessed(
                "abc123", "3.3.3.3", "Googlebot/2.1", null
            ))
            
            val stats = deviceTracker.getDeviceStats("abc123")
            
            assertEquals(1, stats["Mobile"])
            assertEquals(1, stats["Desktop"])
            assertEquals(1, stats["Bot"])
        }
        
        @Test
        fun `report generator aggregates all stats`() {
            val clickTracker = ClickTracker()
            val visitorTracker = UniqueVisitorTracker()
            val referrerTracker = ReferrerTracker()
            val deviceTracker = DeviceTracker()
            
            val reportGenerator = ReportGenerator(
                clickTracker, visitorTracker, referrerTracker, deviceTracker
            )
            
            val event1 = URLEvent.URLAccessed("abc123", "1.1.1.1", "Mobile Safari", "https://google.com")
            val event2 = URLEvent.URLAccessed("abc123", "2.2.2.2", "Chrome Desktop", "https://twitter.com")
            
            clickTracker.onURLAccessed(event1)
            clickTracker.onURLAccessed(event2)
            visitorTracker.onURLAccessed(event1)
            visitorTracker.onURLAccessed(event2)
            referrerTracker.onURLAccessed(event1)
            referrerTracker.onURLAccessed(event2)
            deviceTracker.onURLAccessed(event1)
            deviceTracker.onURLAccessed(event2)
            
            val report = reportGenerator.generateReport("abc123")
            
            assertEquals("abc123", report.shortCode)
            assertEquals(2, report.totalClicks)
            assertEquals(2, report.uniqueVisitors)
            assertEquals(2, report.clicksByReferrer.size)
            assertEquals(2, report.clicksByDevice.size)
        }
        
        @Test
        fun `real-time stats aggregator tracks global stats`() {
            val aggregator = RealTimeStatsAggregator()
            
            val url1 = URL("1", "http://a.com", "a", LocalDateTime.now(), null, null)
            val url2 = URL("2", "http://b.com", "b", LocalDateTime.now(), null, null)
            
            aggregator.onURLCreated(URLEvent.URLCreated(url1))
            aggregator.onURLCreated(URLEvent.URLCreated(url2))
            aggregator.onURLAccessed(URLEvent.URLAccessed("a", null, null, null))
            aggregator.onURLAccessed(URLEvent.URLAccessed("b", null, null, null))
            aggregator.onURLAccessed(URLEvent.URLAccessed("a", null, null, null))
            aggregator.onURLExpired(URLEvent.URLExpired("a"))
            
            val stats = aggregator.getStats()
            
            assertEquals(2, stats.totalUrls)
            assertEquals(1, stats.activeUrls)
            assertEquals(1, stats.expiredUrls)
            assertEquals(3, stats.totalClicks)
        }
    }
    
    @Nested
    inner class DecoratorStackingTest {
        
        @Test
        fun `multiple decorators can be combined`() {
            val repository = InMemoryURLRepository()
            val url = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now(),
                expiresAt = LocalDateTime.now().plusDays(1),
                userId = null,
                password = "secret",
                maxClicks = 100
            )
            repository.save(url)
            
            val handler = DecoratorChainBuilder(repository)
                .withExpiration()
                .withPasswordProtection()
                .withClickLimit()
                .withAnalytics()
                .build()
            
            val result = handler.resolve("abc123", ResolveContext(password = "secret"))
            
            assertTrue(result is ResolveResult.Success)
        }
        
        @Test
        fun `decorator order matters - expiration checked before password`() {
            val repository = InMemoryURLRepository()
            val expiredUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now().minusDays(10),
                expiresAt = LocalDateTime.now().minusDays(1),
                userId = null,
                password = "secret"
            )
            repository.save(expiredUrl)
            
            val handler = DecoratorChainBuilder(repository)
                .withExpiration()
                .withPasswordProtection()
                .build()
            
            val result = handler.resolve("abc123", ResolveContext())
            
            assertTrue(result is ResolveResult.Expired)
        }
    }
    
    @Nested
    inner class ObserverPatternTest {
        
        @Test
        fun `observers receive creation events`() {
            val shortener = ObserverBasedURLShortener()
            val observer = mockk<URLObserver>(relaxed = true)
            shortener.subscribe(observer)
            
            shortener.shorten("http://example.com")
            
            verify { observer.onURLCreated(any()) }
        }
        
        @Test
        fun `observers receive access events`() {
            val shortener = ObserverBasedURLShortener()
            val observer = mockk<URLObserver>(relaxed = true)
            shortener.subscribe(observer)
            
            val result = shortener.shorten("http://example.com") as ShortenResult.Success
            shortener.resolve(result.url.shortCode)
            
            verify { observer.onURLAccessed(any()) }
        }
        
        @Test
        fun `observers receive expiration events`() {
            val repository = InMemoryURLRepository()
            val shortener = ObserverBasedURLShortener(repository)
            val observer = mockk<URLObserver>(relaxed = true)
            shortener.subscribe(observer)
            
            val expiredUrl = URL(
                id = "1",
                originalUrl = "http://example.com",
                shortCode = "abc123",
                createdAt = LocalDateTime.now().minusDays(10),
                expiresAt = LocalDateTime.now().minusDays(1),
                userId = null
            )
            repository.save(expiredUrl)
            
            shortener.resolve("abc123")
            
            verify { observer.onURLExpired(any()) }
        }
        
        @Test
        fun `unsubscribed observers do not receive events`() {
            val shortener = ObserverBasedURLShortener()
            val observer = mockk<URLObserver>(relaxed = true)
            
            shortener.subscribe(observer)
            shortener.unsubscribe(observer)
            shortener.shorten("http://example.com")
            
            verify(exactly = 0) { observer.onURLCreated(any()) }
        }
        
        @Test
        fun `multiple observers all receive events`() {
            val shortener = ObserverBasedURLShortener()
            val observer1 = mockk<URLObserver>(relaxed = true)
            val observer2 = mockk<URLObserver>(relaxed = true)
            
            shortener.subscribe(observer1)
            shortener.subscribe(observer2)
            shortener.shorten("http://example.com")
            
            verify { observer1.onURLCreated(any()) }
            verify { observer2.onURLCreated(any()) }
        }
    }
    
    @Nested
    inner class StrategyBasedShortenerTest {
        
        private lateinit var shortener: StrategyBasedURLShortener
        
        @BeforeEach
        fun setup() {
            shortener = URLShortenerBuilder()
                .withDefaultStrategy(Base62Strategy())
                .addStrategy(HashBasedStrategy())
                .addStrategy(RandomStrategy())
                .build()
        }
        
        @Test
        fun `shortens URL successfully`() {
            val result = shortener.shorten("http://example.com")
            
            assertTrue(result is ShortenResult.Success)
            val success = result as ShortenResult.Success
            assertNotNull(success.url.shortCode)
            assertEquals("http://example.com", success.url.originalUrl)
        }
        
        @Test
        fun `resolves shortened URL`() {
            val shortenResult = shortener.shorten("http://example.com") as ShortenResult.Success
            
            val resolveResult = shortener.resolve(shortenResult.url.shortCode)
            
            assertTrue(resolveResult is ResolveResult.Success)
            assertEquals("http://example.com", (resolveResult as ResolveResult.Success).url.originalUrl)
        }
        
        @Test
        fun `returns not found for unknown code`() {
            val result = shortener.resolve("nonexistent")
            
            assertTrue(result is ResolveResult.NotFound)
        }
        
        @Test
        fun `rejects invalid URLs`() {
            val result = shortener.shorten("not-a-valid-url")
            
            assertTrue(result is ShortenResult.InvalidUrl)
        }
        
        @Test
        fun `allows custom alias`() {
            val result = shortener.shorten("http://example.com", customAlias = "my-link")
            
            assertTrue(result is ShortenResult.Success)
            assertEquals("my-link", (result as ShortenResult.Success).url.shortCode)
        }
        
        @Test
        fun `allows selecting specific strategy`() {
            val result = shortener.shorten(
                "http://example.com",
                strategyName = "Random"
            )
            
            assertTrue(result is ShortenResult.Success)
        }
        
        @Test
        fun `deletes URL successfully`() {
            val result = shortener.shorten("http://example.com") as ShortenResult.Success
            
            val deleted = shortener.delete(result.url.shortCode)
            val resolveResult = shortener.resolve(result.url.shortCode)
            
            assertTrue(deleted)
            assertTrue(resolveResult is ResolveResult.NotFound)
        }
        
        @Test
        fun `lists available strategies`() {
            val strategies = shortener.getAvailableStrategies()
            
            assertTrue(strategies.contains("Base62"))
            assertTrue(strategies.contains("Random"))
        }
    }
    
    @Nested
    inner class IntegrationTest {
        
        @Test
        fun `full workflow with strategy pattern`() {
            val shortener = URLShortenerBuilder()
                .withDefaultStrategy(Base62Strategy())
                .withCollisionHandler(RetryCollisionHandler(maxRetries = 3))
                .build()
            
            val shortenResult = shortener.shorten(
                originalUrl = "http://example.com/very/long/path",
                userId = "user123",
                expiresAt = LocalDateTime.now().plusDays(7)
            )
            
            assertTrue(shortenResult is ShortenResult.Success)
            val url = (shortenResult as ShortenResult.Success).url
            
            val resolveResult = shortener.resolve(url.shortCode)
            assertTrue(resolveResult is ResolveResult.Success)
        }
        
        @Test
        fun `full workflow with decorator pattern`() {
            val repository = InMemoryURLRepository()
            val shortener = DecoratorBasedURLShortener.withAllFeatures(repository)
            
            val request = ShortenRequest(
                originalUrl = "http://example.com",
                expiresAt = LocalDateTime.now().plusDays(7),
                password = "secret123",
                maxClicks = 1000
            )
            
            val shortenResult = shortener.shorten(request)
            assertTrue(shortenResult is ShortenResult.Success)
            val url = (shortenResult as ShortenResult.Success).url
            
            val resolveResult = shortener.resolve(
                url.shortCode,
                ResolveContext(
                    password = "secret123",
                    ipAddress = "192.168.1.1"
                )
            )
            
            assertTrue(resolveResult is ResolveResult.Success)
        }
        
        @Test
        fun `full workflow with observer pattern`() {
            val (shortener, reportGenerator) = ObserverBasedURLShortener.withFullAnalytics()
            
            val shortenResult = shortener.shorten("http://example.com")
            assertTrue(shortenResult is ShortenResult.Success)
            val url = (shortenResult as ShortenResult.Success).url
            
            repeat(5) { i ->
                shortener.resolve(
                    url.shortCode,
                    ipAddress = "192.168.1.$i",
                    userAgent = if (i % 2 == 0) "Mobile Safari" else "Chrome Desktop",
                    referrer = if (i % 3 == 0) "https://google.com" else "https://twitter.com"
                )
            }
            
            val stats = reportGenerator.generateReport(url.shortCode)
            
            assertEquals(5, stats.totalClicks)
            assertEquals(5, stats.uniqueVisitors)
            assertTrue(stats.clicksByReferrer.isNotEmpty())
            assertTrue(stats.clicksByDevice.isNotEmpty())
        }
    }
}
