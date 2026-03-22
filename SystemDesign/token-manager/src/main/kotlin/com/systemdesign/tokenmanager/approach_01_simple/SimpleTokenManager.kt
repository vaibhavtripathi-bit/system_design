/**
 * # Approach 01: Simple Token Manager
 *
 * ## Pattern Used
 * Basic token storage with refresh handling.
 *
 * ## Trade-offs
 * - **Pros:** Simple, easy to understand
 * - **Cons:** No concurrent request handling, manual refresh
 *
 * ## When to Prefer
 * - Simple apps
 * - Single request at a time
 */
package com.systemdesign.tokenmanager.approach_01_simple

import java.util.concurrent.atomic.AtomicReference

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

interface TokenStorage {
    fun save(tokens: TokenPair)
    fun load(): TokenPair?
    fun clear()
}

interface TokenRefresher {
    suspend fun refresh(refreshToken: String): TokenPair
}

class InMemoryTokenStorage : TokenStorage {
    private val tokens = AtomicReference<TokenPair?>(null)
    
    override fun save(tokens: TokenPair) { this.tokens.set(tokens) }
    override fun load(): TokenPair? = tokens.get()
    override fun clear() { tokens.set(null) }
}

class SimpleTokenManager(
    private val storage: TokenStorage,
    private val refresher: TokenRefresher,
    private val bufferMs: Long = 60000
) {
    suspend fun getAccessToken(): String? {
        val tokens = storage.load() ?: return null
        
        if (isExpired(tokens)) {
            return try {
                val newTokens = refresher.refresh(tokens.refreshToken)
                storage.save(newTokens)
                newTokens.accessToken
            } catch (e: Exception) {
                storage.clear()
                null
            }
        }
        
        return tokens.accessToken
    }

    fun setTokens(tokens: TokenPair) = storage.save(tokens)

    fun clearTokens() = storage.clear()

    fun isLoggedIn(): Boolean = storage.load() != null

    private fun isExpired(tokens: TokenPair): Boolean {
        return System.currentTimeMillis() + bufferMs >= tokens.expiresAt
    }
}
