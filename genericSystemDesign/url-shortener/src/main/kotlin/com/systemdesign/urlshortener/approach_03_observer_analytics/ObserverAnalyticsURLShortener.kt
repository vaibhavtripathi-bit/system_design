package com.systemdesign.urlshortener.approach_03_observer_analytics

import com.systemdesign.urlshortener.common.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Approach 3: Observer Pattern for Analytics
 * 
 * URL events (creation, access, expiration) are published to observers.
 * Different observers can track different analytics aspects independently.
 * 
 * Pattern: Observer (Pub/Sub)
 * 
 * Trade-offs:
 * + Loose coupling between URL handling and analytics
 * + Easy to add new analytics trackers
 * + Observers can be added/removed dynamically
 * - Event ordering can be complex
 * - Observer failures need handling
 * 
 * When to use:
 * - When multiple systems need URL event notifications
 * - When analytics requirements change frequently
 * - When you need real-time statistics
 * 
 * Extensibility:
 * - New tracker: Implement URLObserver interface
 * - New event type: Add to URLEvent sealed class
 */

sealed class URLEvent {
    abstract val timestamp: LocalDateTime
    
    data class URLCreated(
        val url: URL,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : URLEvent()
    
    data class URLAccessed(
        val shortCode: String,
        val ipAddress: String?,
        val userAgent: String?,
        val referrer: String?,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : URLEvent()
    
    data class URLExpired(
        val shortCode: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : URLEvent()
    
    data class URLDeleted(
        val shortCode: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : URLEvent()
    
    data class URLLimitReached(
        val shortCode: String,
        val limitType: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : URLEvent()
}

interface URLObserver {
    fun onURLCreated(event: URLEvent.URLCreated) {}
    fun onURLAccessed(event: URLEvent.URLAccessed) {}
    fun onURLExpired(event: URLEvent.URLExpired) {}
    fun onURLDeleted(event: URLEvent.URLDeleted) {}
    fun onURLLimitReached(event: URLEvent.URLLimitReached) {}
}

interface URLEventPublisher {
    fun subscribe(observer: URLObserver)
    fun unsubscribe(observer: URLObserver)
    fun publish(event: URLEvent)
}

class DefaultURLEventPublisher : URLEventPublisher {
    private val observers = CopyOnWriteArrayList<URLObserver>()
    
    override fun subscribe(observer: URLObserver) {
        observers.add(observer)
    }
    
    override fun unsubscribe(observer: URLObserver) {
        observers.remove(observer)
    }
    
    override fun publish(event: URLEvent) {
        observers.forEach { observer ->
            try {
                when (event) {
                    is URLEvent.URLCreated -> observer.onURLCreated(event)
                    is URLEvent.URLAccessed -> observer.onURLAccessed(event)
                    is URLEvent.URLExpired -> observer.onURLExpired(event)
                    is URLEvent.URLDeleted -> observer.onURLDeleted(event)
                    is URLEvent.URLLimitReached -> observer.onURLLimitReached(event)
                }
            } catch (e: Exception) {
                // Log and continue with other observers
            }
        }
    }
    
    fun getObserverCount(): Int = observers.size
}

class ClickTracker : URLObserver {
    private val clickCounts = ConcurrentHashMap<String, AtomicLong>()
    private val clicksByDay = ConcurrentHashMap<String, ConcurrentHashMap<LocalDate, AtomicLong>>()
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        clickCounts.computeIfAbsent(event.shortCode) { AtomicLong(0) }.incrementAndGet()
        
        val dayMap = clicksByDay.computeIfAbsent(event.shortCode) { ConcurrentHashMap() }
        val day = event.timestamp.toLocalDate()
        dayMap.computeIfAbsent(day) { AtomicLong(0) }.incrementAndGet()
    }
    
    fun getTotalClicks(shortCode: String): Long {
        return clickCounts[shortCode]?.get() ?: 0
    }
    
    fun getClicksByDay(shortCode: String): Map<LocalDate, Long> {
        return clicksByDay[shortCode]?.mapValues { it.value.get() } ?: emptyMap()
    }
    
    fun getAllStats(): Map<String, Long> {
        return clickCounts.mapValues { it.value.get() }
    }
}

class UniqueVisitorTracker : URLObserver {
    private val uniqueIPs = ConcurrentHashMap<String, MutableSet<String>>()
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        val ip = event.ipAddress ?: return
        uniqueIPs.computeIfAbsent(event.shortCode) { ConcurrentHashMap.newKeySet() }.add(ip)
    }
    
    fun getUniqueVisitors(shortCode: String): Long {
        return uniqueIPs[shortCode]?.size?.toLong() ?: 0
    }
    
    fun getAllUniqueVisitors(): Map<String, Long> {
        return uniqueIPs.mapValues { it.value.size.toLong() }
    }
}

class ReferrerTracker : URLObserver {
    private val referrerCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        val referrer = event.referrer ?: "direct"
        val domain = extractDomain(referrer)
        
        referrerCounts
            .computeIfAbsent(event.shortCode) { ConcurrentHashMap() }
            .computeIfAbsent(domain) { AtomicLong(0) }
            .incrementAndGet()
    }
    
    private fun extractDomain(referrer: String): String {
        if (referrer == "direct") return referrer
        return try {
            val withoutProtocol = referrer.replace(Regex("^https?://"), "")
            withoutProtocol.split("/").firstOrNull()?.split("?")?.firstOrNull() ?: referrer
        } catch (e: Exception) {
            referrer
        }
    }
    
    fun getReferrerStats(shortCode: String): Map<String, Long> {
        return referrerCounts[shortCode]?.mapValues { it.value.get() } ?: emptyMap()
    }
}

class DeviceTracker : URLObserver {
    private val deviceCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        val deviceType = parseDeviceType(event.userAgent)
        
        deviceCounts
            .computeIfAbsent(event.shortCode) { ConcurrentHashMap() }
            .computeIfAbsent(deviceType) { AtomicLong(0) }
            .incrementAndGet()
    }
    
    private fun parseDeviceType(userAgent: String?): String {
        if (userAgent == null) return "Unknown"
        return when {
            userAgent.contains("Mobile", ignoreCase = true) -> "Mobile"
            userAgent.contains("Tablet", ignoreCase = true) -> "Tablet"
            userAgent.contains("Bot", ignoreCase = true) -> "Bot"
            else -> "Desktop"
        }
    }
    
    fun getDeviceStats(shortCode: String): Map<String, Long> {
        return deviceCounts[shortCode]?.mapValues { it.value.get() } ?: emptyMap()
    }
}

class GeolocationTracker(
    private val geoResolver: (String) -> String? = { null }
) : URLObserver {
    
    private val countryCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        val ip = event.ipAddress ?: return
        val country = geoResolver(ip) ?: "Unknown"
        
        countryCounts
            .computeIfAbsent(event.shortCode) { ConcurrentHashMap() }
            .computeIfAbsent(country) { AtomicLong(0) }
            .incrementAndGet()
    }
    
    fun getCountryStats(shortCode: String): Map<String, Long> {
        return countryCounts[shortCode]?.mapValues { it.value.get() } ?: emptyMap()
    }
}

class ReportGenerator(
    private val clickTracker: ClickTracker,
    private val visitorTracker: UniqueVisitorTracker,
    private val referrerTracker: ReferrerTracker,
    private val deviceTracker: DeviceTracker
) : URLObserver {
    
    private val createdUrls = ConcurrentHashMap<String, LocalDateTime>()
    
    override fun onURLCreated(event: URLEvent.URLCreated) {
        createdUrls[event.url.shortCode] = event.timestamp
    }
    
    fun generateReport(shortCode: String): URLStats {
        return URLStats(
            shortCode = shortCode,
            totalClicks = clickTracker.getTotalClicks(shortCode),
            uniqueVisitors = visitorTracker.getUniqueVisitors(shortCode),
            clicksByDay = clickTracker.getClicksByDay(shortCode),
            clicksByReferrer = referrerTracker.getReferrerStats(shortCode),
            clicksByDevice = deviceTracker.getDeviceStats(shortCode)
        )
    }
    
    fun generateSummaryReport(): List<URLStats> {
        return createdUrls.keys.map { generateReport(it) }
    }
}

class RealTimeStatsAggregator : URLObserver {
    private val totalUrls = AtomicLong(0)
    private val totalClicks = AtomicLong(0)
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()
    private val expiredUrls = ConcurrentHashMap.newKeySet<String>()
    
    override fun onURLCreated(event: URLEvent.URLCreated) {
        totalUrls.incrementAndGet()
        activeUrls.add(event.url.shortCode)
    }
    
    override fun onURLAccessed(event: URLEvent.URLAccessed) {
        totalClicks.incrementAndGet()
    }
    
    override fun onURLExpired(event: URLEvent.URLExpired) {
        activeUrls.remove(event.shortCode)
        expiredUrls.add(event.shortCode)
    }
    
    override fun onURLDeleted(event: URLEvent.URLDeleted) {
        activeUrls.remove(event.shortCode)
    }
    
    fun getStats(): RealTimeStats {
        return RealTimeStats(
            totalUrls = totalUrls.get(),
            activeUrls = activeUrls.size.toLong(),
            expiredUrls = expiredUrls.size.toLong(),
            totalClicks = totalClicks.get()
        )
    }
}

data class RealTimeStats(
    val totalUrls: Long,
    val activeUrls: Long,
    val expiredUrls: Long,
    val totalClicks: Long
)

class ObserverBasedURLShortener(
    private val repository: URLRepository = InMemoryURLRepository(),
    private val publisher: URLEventPublisher = DefaultURLEventPublisher()
) {
    fun subscribe(observer: URLObserver) {
        publisher.subscribe(observer)
    }
    
    fun unsubscribe(observer: URLObserver) {
        publisher.unsubscribe(observer)
    }
    
    fun shorten(
        originalUrl: String,
        userId: String? = null,
        expiresAt: LocalDateTime? = null,
        customAlias: String? = null
    ): ShortenResult {
        val shortCode = customAlias ?: generateShortCode()
        
        if (repository.existsByShortCode(shortCode)) {
            return if (customAlias != null) {
                ShortenResult.DuplicateAlias(shortCode)
            } else {
                ShortenResult.CollisionRetry(shortCode, 1)
            }
        }
        
        val url = URL(
            id = UUID.randomUUID().toString(),
            originalUrl = originalUrl,
            shortCode = shortCode,
            createdAt = LocalDateTime.now(),
            expiresAt = expiresAt,
            userId = userId
        )
        
        repository.save(url)
        publisher.publish(URLEvent.URLCreated(url))
        
        return ShortenResult.Success(url)
    }
    
    fun resolve(
        shortCode: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        referrer: String? = null
    ): ResolveResult {
        val url = repository.findByShortCode(shortCode)
            ?: return ResolveResult.NotFound(shortCode)
        
        if (url.expiresAt != null && LocalDateTime.now().isAfter(url.expiresAt)) {
            publisher.publish(URLEvent.URLExpired(shortCode))
            return ResolveResult.Expired(shortCode)
        }
        
        publisher.publish(URLEvent.URLAccessed(shortCode, ipAddress, userAgent, referrer))
        
        return ResolveResult.Success(url)
    }
    
    fun delete(shortCode: String): Boolean {
        val deleted = repository.delete(shortCode)
        if (deleted) {
            publisher.publish(URLEvent.URLDeleted(shortCode))
        }
        return deleted
    }
    
    private fun generateShortCode(): String {
        return UUID.randomUUID().toString().replace("-", "").take(7)
    }
    
    companion object {
        fun withFullAnalytics(): Pair<ObserverBasedURLShortener, ReportGenerator> {
            val shortener = ObserverBasedURLShortener()
            
            val clickTracker = ClickTracker()
            val visitorTracker = UniqueVisitorTracker()
            val referrerTracker = ReferrerTracker()
            val deviceTracker = DeviceTracker()
            
            shortener.subscribe(clickTracker)
            shortener.subscribe(visitorTracker)
            shortener.subscribe(referrerTracker)
            shortener.subscribe(deviceTracker)
            
            val reportGenerator = ReportGenerator(
                clickTracker,
                visitorTracker,
                referrerTracker,
                deviceTracker
            )
            shortener.subscribe(reportGenerator)
            
            return shortener to reportGenerator
        }
    }
}

class AnalyticsService(
    private val shortener: ObserverBasedURLShortener,
    private val reportGenerator: ReportGenerator
) {
    fun getStats(shortCode: String): URLStats {
        return reportGenerator.generateReport(shortCode)
    }
    
    fun getAllStats(): List<URLStats> {
        return reportGenerator.generateSummaryReport()
    }
}
