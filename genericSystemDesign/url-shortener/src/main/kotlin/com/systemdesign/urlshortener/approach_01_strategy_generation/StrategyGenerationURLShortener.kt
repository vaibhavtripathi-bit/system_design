package com.systemdesign.urlshortener.approach_01_strategy_generation

import com.systemdesign.urlshortener.common.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Approach 1: Strategy Pattern for ID Generation
 * 
 * Different strategies for generating short codes can be swapped at runtime.
 * Each strategy has different trade-offs for collision probability, predictability,
 * and distribution characteristics.
 * 
 * Pattern: Strategy
 * 
 * Trade-offs:
 * + Easy to add new ID generation algorithms
 * + Runtime strategy switching possible
 * + Clear separation of generation logic
 * - Strategy selection adds complexity
 * - Some strategies need additional state (counters)
 * 
 * When to use:
 * - When multiple ID generation approaches are needed
 * - When different users/contexts need different strategies
 * - When A/B testing generation algorithms
 * 
 * Extensibility:
 * - New strategy: Implement IdGenerationStrategy interface
 * - Custom collision handling: Override CollisionHandler
 */

interface IdGenerationStrategy {
    fun generateId(url: String): String
    val name: String
}

class Base62Strategy(
    private val codeLength: Int = 7,
    initialCounter: Long = 0
) : IdGenerationStrategy {
    
    private val counter = AtomicLong(initialCounter)
    
    companion object {
        private const val BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
    
    override val name: String = "Base62"
    
    override fun generateId(url: String): String {
        val count = counter.incrementAndGet()
        return encodeBase62(count)
    }
    
    private fun encodeBase62(number: Long): String {
        if (number == 0L) return BASE62_CHARS[0].toString().padStart(codeLength, '0')
        
        val sb = StringBuilder()
        var num = number
        while (num > 0) {
            sb.append(BASE62_CHARS[(num % 62).toInt()])
            num /= 62
        }
        return sb.reverse().toString().padStart(codeLength, '0')
    }
    
    fun getCurrentCounter(): Long = counter.get()
}

class HashBasedStrategy(
    private val algorithm: String = "MD5",
    private val codeLength: Int = 7
) : IdGenerationStrategy {
    
    override val name: String = "Hash-$algorithm"
    
    override fun generateId(url: String): String {
        val timestamp = System.nanoTime()
        val input = "$url$timestamp"
        
        val digest = MessageDigest.getInstance(algorithm)
        val hashBytes = digest.digest(input.toByteArray())
        
        return hashBytes
            .take(codeLength)
            .joinToString("") { byte -> 
                Integer.toHexString(byte.toInt() and 0xFF).padStart(2, '0') 
            }
            .take(codeLength)
    }
}

class RandomStrategy(
    private val codeLength: Int = 7
) : IdGenerationStrategy {
    
    companion object {
        private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
    
    override val name: String = "Random"
    
    override fun generateId(url: String): String {
        return (1..codeLength)
            .map { CHARS.random() }
            .joinToString("")
    }
}

class CustomAliasStrategy : IdGenerationStrategy {
    
    override val name: String = "CustomAlias"
    
    private var customAlias: String? = null
    
    fun setAlias(alias: String) {
        require(alias.isNotBlank()) { "Alias cannot be blank" }
        require(alias.matches(Regex("^[a-zA-Z0-9_-]+$"))) { 
            "Alias can only contain alphanumeric characters, hyphens, and underscores" 
        }
        customAlias = alias
    }
    
    override fun generateId(url: String): String {
        return customAlias ?: throw IllegalStateException("Custom alias not set")
    }
    
    fun clearAlias() {
        customAlias = null
    }
}

class UUIDStrategy(
    private val codeLength: Int = 8
) : IdGenerationStrategy {
    
    override val name: String = "UUID"
    
    override fun generateId(url: String): String {
        return UUID.randomUUID().toString().replace("-", "").take(codeLength)
    }
}

interface CollisionHandler {
    fun handle(
        shortCode: String,
        strategy: IdGenerationStrategy,
        url: String,
        attempt: Int
    ): CollisionResult
}

sealed class CollisionResult {
    data class NewCode(val code: String) : CollisionResult()
    data class Failed(val reason: String) : CollisionResult()
}

class RetryCollisionHandler(
    private val maxRetries: Int = 5
) : CollisionHandler {
    
    override fun handle(
        shortCode: String,
        strategy: IdGenerationStrategy,
        url: String,
        attempt: Int
    ): CollisionResult {
        if (attempt >= maxRetries) {
            return CollisionResult.Failed("Max retries ($maxRetries) exceeded for URL: $url")
        }
        return CollisionResult.NewCode(strategy.generateId(url))
    }
}

class SuffixCollisionHandler : CollisionHandler {
    
    override fun handle(
        shortCode: String,
        strategy: IdGenerationStrategy,
        url: String,
        attempt: Int
    ): CollisionResult {
        if (attempt > 99) {
            return CollisionResult.Failed("Max suffix attempts exceeded")
        }
        return CollisionResult.NewCode("${shortCode}_$attempt")
    }
}

class StrategyBasedURLShortener(
    private val repository: URLRepository = InMemoryURLRepository(),
    private val defaultStrategy: IdGenerationStrategy = Base62Strategy(),
    private val collisionHandler: CollisionHandler = RetryCollisionHandler()
) {
    private val strategies = mutableMapOf<String, IdGenerationStrategy>()
    
    init {
        registerStrategy(defaultStrategy)
    }
    
    fun registerStrategy(strategy: IdGenerationStrategy) {
        strategies[strategy.name] = strategy
    }
    
    fun shorten(
        originalUrl: String,
        userId: String? = null,
        expiresAt: LocalDateTime? = null,
        strategyName: String? = null,
        customAlias: String? = null
    ): ShortenResult {
        if (!isValidUrl(originalUrl)) {
            return ShortenResult.InvalidUrl(originalUrl, "Invalid URL format")
        }
        
        val strategy = when {
            customAlias != null -> {
                val aliasStrategy = CustomAliasStrategy()
                aliasStrategy.setAlias(customAlias)
                aliasStrategy
            }
            strategyName != null -> strategies[strategyName] ?: defaultStrategy
            else -> defaultStrategy
        }
        
        return generateWithCollisionHandling(originalUrl, userId, expiresAt, strategy)
    }
    
    private fun generateWithCollisionHandling(
        originalUrl: String,
        userId: String?,
        expiresAt: LocalDateTime?,
        strategy: IdGenerationStrategy,
        attempt: Int = 0
    ): ShortenResult {
        val shortCode = strategy.generateId(originalUrl)
        
        if (repository.existsByShortCode(shortCode)) {
            if (strategy is CustomAliasStrategy) {
                return ShortenResult.DuplicateAlias(shortCode)
            }
            
            return when (val result = collisionHandler.handle(shortCode, strategy, originalUrl, attempt + 1)) {
                is CollisionResult.NewCode -> {
                    val newShortCode = result.code
                    if (repository.existsByShortCode(newShortCode)) {
                        generateWithCollisionHandling(originalUrl, userId, expiresAt, strategy, attempt + 1)
                    } else {
                        createAndSaveUrl(originalUrl, newShortCode, userId, expiresAt)
                    }
                }
                is CollisionResult.Failed -> ShortenResult.CollisionRetry(shortCode, attempt)
            }
        }
        
        return createAndSaveUrl(originalUrl, shortCode, userId, expiresAt)
    }
    
    private fun createAndSaveUrl(
        originalUrl: String,
        shortCode: String,
        userId: String?,
        expiresAt: LocalDateTime?
    ): ShortenResult {
        val url = URL(
            id = UUID.randomUUID().toString(),
            originalUrl = originalUrl,
            shortCode = shortCode,
            createdAt = LocalDateTime.now(),
            expiresAt = expiresAt,
            userId = userId
        )
        repository.save(url)
        return ShortenResult.Success(url)
    }
    
    fun resolve(shortCode: String): ResolveResult {
        val url = repository.findByShortCode(shortCode)
            ?: return ResolveResult.NotFound(shortCode)
        
        if (url.expiresAt != null && LocalDateTime.now().isAfter(url.expiresAt)) {
            return ResolveResult.Expired(shortCode)
        }
        
        return ResolveResult.Success(url)
    }
    
    fun delete(shortCode: String): Boolean = repository.delete(shortCode)
    
    fun getAvailableStrategies(): List<String> = strategies.keys.toList()
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            val regex = Regex("^https?://[\\w.-]+(:[0-9]+)?(/.*)?$")
            regex.matches(url)
        } catch (e: Exception) {
            false
        }
    }
}

class URLShortenerBuilder {
    private var repository: URLRepository = InMemoryURLRepository()
    private var defaultStrategy: IdGenerationStrategy = Base62Strategy()
    private var collisionHandler: CollisionHandler = RetryCollisionHandler()
    private val additionalStrategies = mutableListOf<IdGenerationStrategy>()
    
    fun withRepository(repository: URLRepository) = apply { this.repository = repository }
    fun withDefaultStrategy(strategy: IdGenerationStrategy) = apply { this.defaultStrategy = strategy }
    fun withCollisionHandler(handler: CollisionHandler) = apply { this.collisionHandler = handler }
    fun addStrategy(strategy: IdGenerationStrategy) = apply { additionalStrategies.add(strategy) }
    
    fun build(): StrategyBasedURLShortener {
        val shortener = StrategyBasedURLShortener(repository, defaultStrategy, collisionHandler)
        additionalStrategies.forEach { shortener.registerStrategy(it) }
        return shortener
    }
}
