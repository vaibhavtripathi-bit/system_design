/**
 * # Approach 02: Synchronized Token Manager
 *
 * ## Pattern Used
 * Thread-safe token management with request coalescing.
 * Multiple concurrent requests share a single refresh.
 *
 * ## Trade-offs
 * - **Pros:** Thread-safe, efficient (single refresh), prevents race conditions
 * - **Cons:** Blocking during refresh, more complex
 *
 * ## When to Prefer
 * - Multi-threaded apps
 * - Many concurrent API calls
 */
package com.systemdesign.tokenmanager.approach_02_synchronized

import com.systemdesign.tokenmanager.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class SynchronizedTokenManager(
    private val storage: TokenStorage,
    private val refresher: TokenRefresher,
    private val bufferMs: Long = 60000
) {
    private val mutex = Mutex()
    private var refreshJob: Deferred<TokenPair?>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun getAccessToken(): String? = mutex.withLock {
        val tokens = storage.load() ?: return null
        
        if (!isExpired(tokens)) {
            return tokens.accessToken
        }

        val existingJob = refreshJob
        if (existingJob != null && existingJob.isActive) {
            return existingJob.await()?.accessToken
        }

        refreshJob = scope.async {
            try {
                val newTokens = refresher.refresh(tokens.refreshToken)
                storage.save(newTokens)
                newTokens
            } catch (e: Exception) {
                storage.clear()
                null
            }
        }

        return refreshJob?.await()?.accessToken
    }

    fun setTokens(tokens: TokenPair) = storage.save(tokens)

    fun clearTokens() {
        refreshJob?.cancel()
        storage.clear()
    }

    fun isLoggedIn(): Boolean = storage.load() != null

    private fun isExpired(tokens: TokenPair): Boolean {
        return System.currentTimeMillis() + bufferMs >= tokens.expiresAt
    }

    fun shutdown() {
        scope.cancel()
    }
}
