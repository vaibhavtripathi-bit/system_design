package com.systemdesign.tokenmanager

import com.systemdesign.tokenmanager.approach_01_simple.*
import com.systemdesign.tokenmanager.approach_02_synchronized.*
import com.systemdesign.tokenmanager.approach_03_proactive.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenManagerTest {

    private fun validTokens(expiresInMs: Long = 3600000) = TokenPair(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresAt = System.currentTimeMillis() + expiresInMs
    )

    private fun expiredTokens() = TokenPair(
        accessToken = "expired-access",
        refreshToken = "refresh-token",
        expiresAt = System.currentTimeMillis() - 1000
    )

    class MockRefresher(
        private val newTokens: TokenPair? = null,
        private val shouldFail: Boolean = false
    ) : TokenRefresher {
        var refreshCount = 0
        
        override suspend fun refresh(refreshToken: String): TokenPair {
            refreshCount++
            if (shouldFail) throw RuntimeException("Refresh failed")
            return newTokens ?: validTokens()
        }
        
        private fun validTokens() = TokenPair(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresAt = System.currentTimeMillis() + 3600000
        )
    }

    // Simple Token Manager Tests
    @Test
    fun `simple - returns null when not logged in`() = runBlocking {
        val manager = SimpleTokenManager(InMemoryTokenStorage(), MockRefresher())
        
        assertNull(manager.getAccessToken())
    }

    @Test
    fun `simple - returns access token when valid`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(validTokens())
        val manager = SimpleTokenManager(storage, MockRefresher())
        
        assertEquals("access-token", manager.getAccessToken())
    }

    @Test
    fun `simple - refreshes when expired`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiredTokens())
        val refresher = MockRefresher()
        val manager = SimpleTokenManager(storage, refresher)
        
        val token = manager.getAccessToken()
        
        assertEquals("new-access-token", token)
        assertEquals(1, refresher.refreshCount)
    }

    @Test
    fun `simple - clears on refresh failure`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiredTokens())
        val manager = SimpleTokenManager(storage, MockRefresher(shouldFail = true))
        
        val token = manager.getAccessToken()
        
        assertNull(token)
        assertFalse(manager.isLoggedIn())
    }

    // Synchronized Token Manager Tests
    @Test
    fun `synchronized - returns access token when valid`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(validTokens())
        val manager = SynchronizedTokenManager(storage, MockRefresher())
        
        assertEquals("access-token", manager.getAccessToken())
        manager.shutdown()
    }

    @Test
    fun `synchronized - refreshes when expired`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(expiredTokens())
        val refresher = MockRefresher()
        val manager = SynchronizedTokenManager(storage, refresher)
        
        val token = manager.getAccessToken()
        
        assertEquals("new-access-token", token)
        assertEquals(1, refresher.refreshCount)
        manager.shutdown()
    }

    // Proactive Token Manager Tests
    @Test
    fun `proactive - returns access token when valid`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(validTokens())
        val manager = ProactiveTokenManager(storage, MockRefresher())
        
        assertEquals("access-token", manager.getAccessToken())
        manager.shutdown()
    }

    @Test
    fun `proactive - initial state is valid when token exists`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(validTokens())
        val manager = ProactiveTokenManager(storage, MockRefresher())
        
        assertEquals(TokenState.VALID, manager.state.value)
        manager.shutdown()
    }

    @Test
    fun `proactive - initial state is logged out when no token`() = runBlocking {
        val manager = ProactiveTokenManager(InMemoryTokenStorage(), MockRefresher())
        
        assertEquals(TokenState.LOGGED_OUT, manager.state.value)
        manager.shutdown()
    }

    @Test
    fun `proactive - set tokens updates state`() = runBlocking {
        val manager = ProactiveTokenManager(InMemoryTokenStorage(), MockRefresher())
        
        manager.setTokens(validTokens())
        
        assertEquals(TokenState.VALID, manager.state.value)
        manager.shutdown()
    }

    @Test
    fun `proactive - clear tokens updates state`() = runBlocking {
        val storage = InMemoryTokenStorage()
        storage.save(validTokens())
        val manager = ProactiveTokenManager(storage, MockRefresher())
        
        manager.clearTokens()
        
        assertEquals(TokenState.LOGGED_OUT, manager.state.value)
        manager.shutdown()
    }
}
