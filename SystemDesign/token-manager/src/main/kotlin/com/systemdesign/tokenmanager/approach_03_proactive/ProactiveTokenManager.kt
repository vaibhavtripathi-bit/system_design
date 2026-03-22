/**
 * # Approach 03: Proactive Token Manager
 *
 * ## Pattern Used
 * Background refresh before expiry to prevent request delays.
 *
 * ## Trade-offs
 * - **Pros:** No request delay for refresh, always fresh token ready
 * - **Cons:** More network calls, more complex lifecycle
 *
 * ## When to Prefer
 * - Real-time apps
 * - When latency is critical
 */
package com.systemdesign.tokenmanager.approach_03_proactive

import com.systemdesign.tokenmanager.approach_01_simple.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TokenState {
    LOGGED_OUT, VALID, REFRESHING, EXPIRED, ERROR
}

class ProactiveTokenManager(
    private val storage: TokenStorage,
    private val refresher: TokenRefresher,
    private val bufferMs: Long = 300000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private var refreshJob: Job? = null
    
    private val _state = MutableStateFlow(TokenState.LOGGED_OUT)
    val state: StateFlow<TokenState> = _state

    init {
        storage.load()?.let {
            _state.value = if (isValid(it)) TokenState.VALID else TokenState.EXPIRED
            scheduleRefresh(it)
        }
    }

    suspend fun getAccessToken(): String? = mutex.withLock {
        val tokens = storage.load() ?: return null
        
        if (isExpired(tokens)) {
            return doRefresh(tokens)?.accessToken
        }
        
        return tokens.accessToken
    }

    fun setTokens(tokens: TokenPair) {
        storage.save(tokens)
        _state.value = TokenState.VALID
        scheduleRefresh(tokens)
    }

    fun clearTokens() {
        refreshJob?.cancel()
        storage.clear()
        _state.value = TokenState.LOGGED_OUT
    }

    private fun scheduleRefresh(tokens: TokenPair) {
        refreshJob?.cancel()
        
        val delayMs = tokens.expiresAt - System.currentTimeMillis() - bufferMs
        if (delayMs <= 0) {
            scope.launch { doRefresh(tokens) }
            return
        }
        
        refreshJob = scope.launch {
            delay(delayMs)
            doRefresh(tokens)
        }
    }

    private suspend fun doRefresh(tokens: TokenPair): TokenPair? = mutex.withLock {
        _state.value = TokenState.REFRESHING
        
        return try {
            val newTokens = refresher.refresh(tokens.refreshToken)
            storage.save(newTokens)
            _state.value = TokenState.VALID
            scheduleRefresh(newTokens)
            newTokens
        } catch (e: Exception) {
            _state.value = TokenState.ERROR
            storage.clear()
            null
        }
    }

    private fun isValid(tokens: TokenPair): Boolean {
        return System.currentTimeMillis() < tokens.expiresAt
    }

    private fun isExpired(tokens: TokenPair): Boolean {
        return System.currentTimeMillis() >= tokens.expiresAt
    }

    fun shutdown() {
        refreshJob?.cancel()
        scope.cancel()
    }
}
