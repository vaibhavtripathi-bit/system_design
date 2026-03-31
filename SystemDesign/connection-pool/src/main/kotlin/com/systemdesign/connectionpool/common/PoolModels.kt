package com.systemdesign.connectionpool.common

data class PoolConfig(
    val minSize: Int = 2,
    val maxSize: Int = 10,
    val acquireTimeoutMs: Long = 5000,
    val idleTimeoutMs: Long = 60_000,
    val maxLifetimeMs: Long = 300_000,
    val validationIntervalMs: Long = 30_000
) {
    init {
        require(minSize >= 0) { "minSize must be non-negative" }
        require(maxSize > 0) { "maxSize must be positive" }
        require(maxSize >= minSize) { "maxSize must be >= minSize" }
        require(acquireTimeoutMs > 0) { "acquireTimeoutMs must be positive" }
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
        require(maxLifetimeMs > 0) { "maxLifetimeMs must be positive" }
    }
}

data class PoolStats(
    val totalConnections: Int,
    val activeConnections: Int,
    val idleConnections: Int,
    val waitingRequests: Int
)

enum class ConnectionState {
    IDLE,
    IN_USE,
    VALIDATING,
    EVICTED,
    CLOSED
}

interface PoolObserver {
    fun onAcquire(resourceId: String) {}
    fun onRelease(resourceId: String) {}
    fun onEvict(resourceId: String) {}
    fun onTimeout(waitMs: Long) {}
    fun onValidationFailure(resourceId: String, error: Throwable) {}
    fun onPoolResized(oldSize: Int, newSize: Int) {}
}

interface Poolable : AutoCloseable {
    val id: String
    val createdAt: Long
    fun isValid(): Boolean
}
