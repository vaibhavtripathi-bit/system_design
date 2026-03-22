package com.systemdesign.urlshortener.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class URL(
    val id: String,
    val originalUrl: String,
    val shortCode: String,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val userId: String?,
    val password: String? = null,
    val maxClicks: Long? = null
)

data class URLStats(
    val shortCode: String,
    val totalClicks: Long,
    val uniqueVisitors: Long,
    val clicksByDay: Map<LocalDate, Long>,
    val clicksByReferrer: Map<String, Long> = emptyMap(),
    val clicksByDevice: Map<String, Long> = emptyMap(),
    val clicksByCountry: Map<String, Long> = emptyMap()
)

data class Click(
    val shortCode: String,
    val timestamp: LocalDateTime,
    val referrer: String?,
    val userAgent: String?,
    val ipAddress: String?,
    val country: String? = null,
    val deviceType: String? = null
)

data class User(
    val id: String,
    val name: String,
    val apiKey: String
)

sealed class ResolveResult {
    data class Success(val url: URL) : ResolveResult()
    data class Expired(val shortCode: String) : ResolveResult()
    data class NotFound(val shortCode: String) : ResolveResult()
    data class PasswordRequired(val shortCode: String) : ResolveResult()
    data class ClickLimitExceeded(val shortCode: String) : ResolveResult()
}

sealed class ShortenResult {
    data class Success(val url: URL) : ShortenResult()
    data class CollisionRetry(val shortCode: String, val attempt: Int) : ShortenResult()
    data class InvalidUrl(val originalUrl: String, val reason: String) : ShortenResult()
    data class DuplicateAlias(val alias: String) : ShortenResult()
}

interface URLRepository {
    fun save(url: URL): URL
    fun findByShortCode(shortCode: String): URL?
    fun findByOriginalUrl(originalUrl: String): URL?
    fun existsByShortCode(shortCode: String): Boolean
    fun delete(shortCode: String): Boolean
    fun incrementClicks(shortCode: String): Long
    fun getClickCount(shortCode: String): Long
}

class InMemoryURLRepository : URLRepository {
    private val urls = ConcurrentHashMap<String, URL>()
    private val originalToShort = ConcurrentHashMap<String, String>()
    private val clickCounts = ConcurrentHashMap<String, AtomicLong>()
    
    override fun save(url: URL): URL {
        urls[url.shortCode] = url
        originalToShort[url.originalUrl] = url.shortCode
        clickCounts.putIfAbsent(url.shortCode, AtomicLong(0))
        return url
    }
    
    override fun findByShortCode(shortCode: String): URL? = urls[shortCode]
    
    override fun findByOriginalUrl(originalUrl: String): URL? {
        val shortCode = originalToShort[originalUrl]
        return shortCode?.let { urls[it] }
    }
    
    override fun existsByShortCode(shortCode: String): Boolean = urls.containsKey(shortCode)
    
    override fun delete(shortCode: String): Boolean {
        val url = urls.remove(shortCode)
        url?.let { originalToShort.remove(it.originalUrl) }
        clickCounts.remove(shortCode)
        return url != null
    }
    
    override fun incrementClicks(shortCode: String): Long {
        return clickCounts[shortCode]?.incrementAndGet() ?: 0
    }
    
    override fun getClickCount(shortCode: String): Long {
        return clickCounts[shortCode]?.get() ?: 0
    }
    
    fun clear() {
        urls.clear()
        originalToShort.clear()
        clickCounts.clear()
    }
}

interface ClickRepository {
    fun save(click: Click)
    fun findByShortCode(shortCode: String): List<Click>
    fun countByShortCode(shortCode: String): Long
    fun countUniqueVisitors(shortCode: String): Long
}

class InMemoryClickRepository : ClickRepository {
    private val clicks = ConcurrentHashMap<String, MutableList<Click>>()
    
    override fun save(click: Click) {
        clicks.computeIfAbsent(click.shortCode) { mutableListOf() }.add(click)
    }
    
    override fun findByShortCode(shortCode: String): List<Click> {
        return clicks[shortCode]?.toList() ?: emptyList()
    }
    
    override fun countByShortCode(shortCode: String): Long {
        return clicks[shortCode]?.size?.toLong() ?: 0
    }
    
    override fun countUniqueVisitors(shortCode: String): Long {
        return clicks[shortCode]?.distinctBy { it.ipAddress }?.size?.toLong() ?: 0
    }
    
    fun clear() {
        clicks.clear()
    }
}
