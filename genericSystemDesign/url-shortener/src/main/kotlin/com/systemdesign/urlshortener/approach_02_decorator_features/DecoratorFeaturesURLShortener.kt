package com.systemdesign.urlshortener.approach_02_decorator_features

import com.systemdesign.urlshortener.common.*
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Approach 2: Decorator Pattern for URL Features
 * 
 * URL resolution is wrapped in decorators that add features like
 * expiration checking, password protection, click limits, and analytics.
 * Features can be stacked in any combination.
 * 
 * Pattern: Decorator
 * 
 * Trade-offs:
 * + Features can be combined flexibly at runtime
 * + Single Responsibility - each decorator does one thing
 * + Open/Closed - new features don't modify existing code
 * - Deep decorator chains can be hard to debug
 * - Order of decorators can matter
 * 
 * When to use:
 * - When URLs need different feature combinations
 * - When features should be optional per URL
 * - When you want to add features without modifying core logic
 * 
 * Extensibility:
 * - New feature: Create new decorator implementing URLHandler
 * - Feature ordering: Use DecoratorChainBuilder
 */

interface URLHandler {
    fun resolve(shortCode: String, context: ResolveContext): ResolveResult
    fun shorten(request: ShortenRequest): ShortenResult
}

data class ResolveContext(
    val password: String? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

data class ShortenRequest(
    val originalUrl: String,
    val userId: String? = null,
    val expiresAt: LocalDateTime? = null,
    val password: String? = null,
    val maxClicks: Long? = null,
    val customAlias: String? = null
)

class BaseURLHandler(
    private val repository: URLRepository = InMemoryURLRepository()
) : URLHandler {
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val url = repository.findByShortCode(shortCode)
            ?: return ResolveResult.NotFound(shortCode)
        return ResolveResult.Success(url)
    }
    
    override fun shorten(request: ShortenRequest): ShortenResult {
        val shortCode = request.customAlias ?: generateShortCode()
        
        if (repository.existsByShortCode(shortCode)) {
            return if (request.customAlias != null) {
                ShortenResult.DuplicateAlias(shortCode)
            } else {
                ShortenResult.CollisionRetry(shortCode, 1)
            }
        }
        
        val url = URL(
            id = UUID.randomUUID().toString(),
            originalUrl = request.originalUrl,
            shortCode = shortCode,
            createdAt = LocalDateTime.now(),
            expiresAt = request.expiresAt,
            userId = request.userId,
            password = request.password,
            maxClicks = request.maxClicks
        )
        
        repository.save(url)
        return ShortenResult.Success(url)
    }
    
    private fun generateShortCode(): String {
        return UUID.randomUUID().toString().replace("-", "").take(7)
    }
    
    fun getRepository(): URLRepository = repository
}

abstract class URLHandlerDecorator(
    protected val wrapped: URLHandler
) : URLHandler {
    
    override fun shorten(request: ShortenRequest): ShortenResult {
        return wrapped.shorten(request)
    }
}

class ExpirationDecorator(
    wrapped: URLHandler
) : URLHandlerDecorator(wrapped) {
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val result = wrapped.resolve(shortCode, context)
        
        if (result is ResolveResult.Success) {
            val url = result.url
            if (url.expiresAt != null && context.timestamp.isAfter(url.expiresAt)) {
                return ResolveResult.Expired(shortCode)
            }
        }
        
        return result
    }
}

class PasswordProtectionDecorator(
    wrapped: URLHandler
) : URLHandlerDecorator(wrapped) {
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val result = wrapped.resolve(shortCode, context)
        
        if (result is ResolveResult.Success) {
            val url = result.url
            if (url.password != null) {
                if (context.password == null) {
                    return ResolveResult.PasswordRequired(shortCode)
                }
                if (context.password != url.password) {
                    return ResolveResult.PasswordRequired(shortCode)
                }
            }
        }
        
        return result
    }
}

class ClickLimitDecorator(
    wrapped: URLHandler,
    private val repository: URLRepository
) : URLHandlerDecorator(wrapped) {
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val result = wrapped.resolve(shortCode, context)
        
        if (result is ResolveResult.Success) {
            val url = result.url
            if (url.maxClicks != null) {
                val currentClicks = repository.getClickCount(shortCode)
                if (currentClicks >= url.maxClicks) {
                    return ResolveResult.ClickLimitExceeded(shortCode)
                }
            }
        }
        
        return result
    }
}

class AnalyticsDecorator(
    wrapped: URLHandler,
    private val urlRepository: URLRepository,
    private val clickRepository: ClickRepository = InMemoryClickRepository()
) : URLHandlerDecorator(wrapped) {
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val result = wrapped.resolve(shortCode, context)
        
        if (result is ResolveResult.Success) {
            recordClick(shortCode, context)
            urlRepository.incrementClicks(shortCode)
        }
        
        return result
    }
    
    private fun recordClick(shortCode: String, context: ResolveContext) {
        val click = Click(
            shortCode = shortCode,
            timestamp = context.timestamp,
            referrer = context.referrer,
            userAgent = context.userAgent,
            ipAddress = context.ipAddress,
            deviceType = parseDeviceType(context.userAgent),
            country = null
        )
        clickRepository.save(click)
    }
    
    private fun parseDeviceType(userAgent: String?): String? {
        if (userAgent == null) return null
        return when {
            userAgent.contains("Mobile", ignoreCase = true) -> "Mobile"
            userAgent.contains("Tablet", ignoreCase = true) -> "Tablet"
            else -> "Desktop"
        }
    }
    
    fun getClickRepository(): ClickRepository = clickRepository
}

class RateLimitDecorator(
    wrapped: URLHandler,
    private val maxRequestsPerMinute: Int = 60
) : URLHandlerDecorator(wrapped) {
    
    private val requestCounts = ConcurrentHashMap<String, AtomicLong>()
    private val windowStart = ConcurrentHashMap<String, Long>()
    private val windowDurationMs = 60_000L
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val ip = context.ipAddress ?: "unknown"
        val currentTime = System.currentTimeMillis()
        
        val start = windowStart.getOrPut(ip) { currentTime }
        if (currentTime - start > windowDurationMs) {
            windowStart[ip] = currentTime
            requestCounts[ip] = AtomicLong(0)
        }
        
        val count = requestCounts.getOrPut(ip) { AtomicLong(0) }
        if (count.incrementAndGet() > maxRequestsPerMinute) {
            return ResolveResult.NotFound("Rate limit exceeded")
        }
        
        return wrapped.resolve(shortCode, context)
    }
}

class CachingDecorator(
    wrapped: URLHandler,
    private val maxCacheSize: Int = 1000,
    private val ttlSeconds: Long = 300
) : URLHandlerDecorator(wrapped) {
    
    private data class CacheEntry(val result: ResolveResult, val timestamp: Long)
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    
    override fun resolve(shortCode: String, context: ResolveContext): ResolveResult {
        val now = System.currentTimeMillis()
        val cached = cache[shortCode]
        
        if (cached != null && (now - cached.timestamp) < ttlSeconds * 1000) {
            return cached.result
        }
        
        val result = wrapped.resolve(shortCode, context)
        
        if (result is ResolveResult.Success && cache.size < maxCacheSize) {
            cache[shortCode] = CacheEntry(result, now)
        }
        
        return result
    }
    
    fun invalidate(shortCode: String) {
        cache.remove(shortCode)
    }
    
    fun clearCache() {
        cache.clear()
    }
}

class DecoratorChainBuilder(
    private val repository: URLRepository = InMemoryURLRepository()
) {
    private var handler: URLHandler = BaseURLHandler(repository)
    private var clickRepository: ClickRepository? = null
    
    fun withExpiration() = apply {
        handler = ExpirationDecorator(handler)
    }
    
    fun withPasswordProtection() = apply {
        handler = PasswordProtectionDecorator(handler)
    }
    
    fun withClickLimit() = apply {
        handler = ClickLimitDecorator(handler, repository)
    }
    
    fun withAnalytics(clickRepo: ClickRepository = InMemoryClickRepository()) = apply {
        clickRepository = clickRepo
        handler = AnalyticsDecorator(handler, repository, clickRepo)
    }
    
    fun withRateLimit(maxRequestsPerMinute: Int = 60) = apply {
        handler = RateLimitDecorator(handler, maxRequestsPerMinute)
    }
    
    fun withCaching(maxSize: Int = 1000, ttlSeconds: Long = 300) = apply {
        handler = CachingDecorator(handler, maxSize, ttlSeconds)
    }
    
    fun build(): URLHandler = handler
    
    fun getClickRepository(): ClickRepository? = clickRepository
}

class DecoratorBasedURLShortener(
    private val handler: URLHandler
) {
    fun shorten(request: ShortenRequest): ShortenResult {
        return handler.shorten(request)
    }
    
    fun resolve(shortCode: String, context: ResolveContext = ResolveContext()): ResolveResult {
        return handler.resolve(shortCode, context)
    }
    
    companion object {
        fun withAllFeatures(repository: URLRepository = InMemoryURLRepository()): DecoratorBasedURLShortener {
            val handler = DecoratorChainBuilder(repository)
                .withExpiration()
                .withPasswordProtection()
                .withClickLimit()
                .withAnalytics()
                .withCaching()
                .build()
            return DecoratorBasedURLShortener(handler)
        }
        
        fun basic(repository: URLRepository = InMemoryURLRepository()): DecoratorBasedURLShortener {
            return DecoratorBasedURLShortener(BaseURLHandler(repository))
        }
    }
}
