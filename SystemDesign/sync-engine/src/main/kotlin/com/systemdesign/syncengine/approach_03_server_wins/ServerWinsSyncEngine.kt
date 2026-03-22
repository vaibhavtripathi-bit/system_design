/**
 * # Approach 03: Server-Wins Sync Engine
 *
 * ## Pattern Used
 * Server-authoritative conflict resolution where the server's version
 * always takes precedence in case of conflicts.
 *
 * ## How It Works
 * 1. Client optimistically applies changes locally
 * 2. Changes are queued for sync
 * 3. On sync, if server has newer version:
 *    - Server version overwrites local
 *    - Local changes are discarded or re-applied on top
 * 4. Client re-fetches after push to get canonical state
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Simple conflict resolution logic
 *   - Server is single source of truth
 *   - No complex merge algorithms needed
 *   - Good for transactional data
 *
 * - **Cons:**
 *   - Local changes can be lost
 *   - Poor offline experience (changes may be discarded)
 *   - Not suitable for collaborative editing
 *   - Requires connectivity for authoritative state
 *
 * ## When to Prefer This Approach
 * - When server is the single source of truth
 * - For financial/transactional data
 * - When data consistency is critical
 * - Systems with infrequent offline periods
 *
 * ## Comparison with Other Approaches
 * - **vs LWW (Approach 01):** Server-wins is simpler; LWW considers timestamps
 * - **vs CRDT (Approach 02):** Server-wins discards local; CRDT preserves all
 */
package com.systemdesign.syncengine.approach_03_server_wins

import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-Wins Sync Engine implementation.
 */
class ServerWinsSyncEngine<T : SyncableEntity>(
    private val api: SyncApi<T>,
    private val storage: LocalStorage<T>,
    private val clock: Clock = SystemClock,
    private val retryPolicy: RetryPolicy = ExponentialBackoffRetry()
) : SyncEngine<T> {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncEngineState())
    private val syncQueue = MutableStateFlow<List<QueuedChange<T>>>(emptyList())

    override suspend fun sync(): SyncResult<List<T>> = mutex.withLock {
        _state.update { it.copy(isSyncing = true, lastError = null) }
        
        try {
            val results = mutableListOf<T>()
            
            val remoteEntities = api.fetchAll(_state.value.lastSyncTime)
            
            for (remote in remoteEntities) {
                val local = storage.get(remote.id)
                
                if (local == null || local.syncState != SyncState.PENDING_UPLOAD) {
                    storage.save(SyncRecord(
                        entityId = remote.id,
                        entityType = remote::class.simpleName ?: "Unknown",
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        lastModified = remote.lastModified,
                        syncState = SyncState.SYNCED
                    ))
                    results.add(remote)
                }
            }
            
            val pendingUploads = storage.getPending()
            
            for (record in pendingUploads) {
                try {
                    val serverVersion = api.fetch(record.entityId)
                    
                    if (serverVersion == null || serverVersion.version == record.serverVersion) {
                        val pushed = api.push(record.data)
                        storage.save(record.copy(
                            data = pushed,
                            serverVersion = pushed.version,
                            syncState = SyncState.SYNCED
                        ))
                        results.add(pushed)
                    } else {
                        storage.save(record.copy(
                            data = serverVersion,
                            localVersion = serverVersion.version,
                            serverVersion = serverVersion.version,
                            syncState = SyncState.SYNCED
                        ))
                        results.add(serverVersion)
                    }
                } catch (e: Exception) {
                    storage.save(record.copy(syncState = SyncState.ERROR))
                }
            }
            
            _state.update { it.copy(
                isSyncing = false,
                lastSyncTime = clock.now(),
                pendingCount = storage.getPending().size
            ) }
            
            SyncResult.Success(results, clock.now())
        } catch (e: Exception) {
            _state.update { it.copy(isSyncing = false, lastError = e) }
            SyncResult.Error(e)
        }
    }

    override suspend fun push(entity: T): SyncResult<T> = mutex.withLock {
        try {
            val serverVersion = api.fetch(entity.id)
            
            if (serverVersion != null && serverVersion.version != entity.version - 1) {
                storage.save(SyncRecord(
                    entityId = serverVersion.id,
                    entityType = serverVersion::class.simpleName ?: "Unknown",
                    data = serverVersion,
                    localVersion = serverVersion.version,
                    serverVersion = serverVersion.version,
                    lastModified = serverVersion.lastModified,
                    syncState = SyncState.SYNCED
                ))
                return SyncResult.Conflict(entity, serverVersion, serverVersion)
            }
            
            val pushed = api.push(entity)
            storage.save(SyncRecord(
                entityId = pushed.id,
                entityType = pushed::class.simpleName ?: "Unknown",
                data = pushed,
                localVersion = pushed.version,
                serverVersion = pushed.version,
                lastModified = pushed.lastModified,
                syncState = SyncState.SYNCED
            ))
            
            SyncResult.Success(pushed, pushed.version)
        } catch (e: Exception) {
            storage.save(SyncRecord(
                entityId = entity.id,
                entityType = entity::class.simpleName ?: "Unknown",
                data = entity,
                localVersion = entity.version,
                serverVersion = null,
                lastModified = clock.now(),
                syncState = SyncState.PENDING_UPLOAD
            ))
            _state.update { it.copy(pendingCount = it.pendingCount + 1) }
            SyncResult.Error(e)
        }
    }

    override suspend fun pull(entityId: String): SyncResult<T> = mutex.withLock {
        try {
            val remote = api.fetch(entityId)
                ?: return SyncResult.Error(NoSuchElementException("Entity not found: $entityId"))
            
            storage.save(SyncRecord(
                entityId = remote.id,
                entityType = remote::class.simpleName ?: "Unknown",
                data = remote,
                localVersion = remote.version,
                serverVersion = remote.version,
                lastModified = remote.lastModified,
                syncState = SyncState.SYNCED
            ))
            
            SyncResult.Success(remote, remote.version)
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }

    override suspend fun resolveConflict(entityId: String, resolution: ConflictResolution): SyncResult<T> = mutex.withLock {
        val record = storage.get(entityId)
            ?: return SyncResult.Error(NoSuchElementException("Entity not found: $entityId"))
        
        return when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                try {
                    val pushed = api.push(record.data)
                    storage.save(record.copy(
                        data = pushed,
                        serverVersion = pushed.version,
                        syncState = SyncState.SYNCED
                    ))
                    SyncResult.Success(pushed, pushed.version)
                } catch (e: Exception) {
                    SyncResult.Error(e)
                }
            }
            ConflictResolution.KEEP_REMOTE -> {
                try {
                    val remote = api.fetch(entityId)
                        ?: return SyncResult.Error(NoSuchElementException("Remote entity not found"))
                    storage.save(record.copy(
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        syncState = SyncState.SYNCED
                    ))
                    SyncResult.Success(remote, remote.version)
                } catch (e: Exception) {
                    SyncResult.Error(e)
                }
            }
            else -> SyncResult.Error(UnsupportedOperationException("Server-wins only supports KEEP_LOCAL or KEEP_REMOTE"))
        }
    }

    override fun getPendingChanges(): Flow<List<ChangeEvent<T>>> = flow {
        emit(storage.getPending().map { record ->
            ChangeEvent(
                entityId = record.entityId,
                entityType = record.entityType,
                operation = if (record.serverVersion == null) SyncOperation.CREATE else SyncOperation.UPDATE,
                data = record.data,
                timestamp = record.lastModified,
                localVersion = record.localVersion
            )
        })
    }

    override fun getSyncState(): StateFlow<SyncEngineState> = _state.asStateFlow()

    suspend fun saveLocal(entity: T) {
        val existing = storage.get(entity.id)
        val record = if (existing != null) {
            existing.copy(
                data = entity,
                localVersion = entity.version,
                lastModified = clock.now(),
                syncState = SyncState.PENDING_UPLOAD
            )
        } else {
            SyncRecord(
                entityId = entity.id,
                entityType = entity::class.simpleName ?: "Unknown",
                data = entity,
                localVersion = entity.version,
                serverVersion = null,
                lastModified = clock.now(),
                syncState = SyncState.PENDING_UPLOAD
            )
        }
        storage.save(record)
        _state.update { it.copy(pendingCount = storage.getPending().size) }
    }

    suspend fun getLocal(entityId: String): T? = storage.get(entityId)?.data

    suspend fun getAllLocal(): List<T> = storage.getAll().map { it.data }

    suspend fun forcePull(): SyncResult<List<T>> = mutex.withLock {
        try {
            val remoteEntities = api.fetchAll(null)
            
            storage.clear()
            
            for (remote in remoteEntities) {
                storage.save(SyncRecord(
                    entityId = remote.id,
                    entityType = remote::class.simpleName ?: "Unknown",
                    data = remote,
                    localVersion = remote.version,
                    serverVersion = remote.version,
                    lastModified = remote.lastModified,
                    syncState = SyncState.SYNCED
                ))
            }
            
            _state.update { it.copy(
                lastSyncTime = clock.now(),
                pendingCount = 0
            ) }
            
            SyncResult.Success(remoteEntities, clock.now())
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }
}

/**
 * Queued change for retry.
 */
data class QueuedChange<T>(
    val id: String = UUID.randomUUID().toString(),
    val entity: T,
    val operation: SyncOperation,
    val retryCount: Int = 0,
    val nextRetryTime: Long = 0
)

/**
 * Retry policy interface.
 */
interface RetryPolicy {
    fun getNextRetryDelay(retryCount: Int): Long
    fun shouldRetry(retryCount: Int): Boolean
}

/**
 * Exponential backoff retry policy.
 */
class ExponentialBackoffRetry(
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 60000,
    private val maxRetries: Int = 5,
    private val multiplier: Double = 2.0
) : RetryPolicy {
    
    override fun getNextRetryDelay(retryCount: Int): Long {
        val delay = (initialDelayMs * Math.pow(multiplier, retryCount.toDouble())).toLong()
        return minOf(delay, maxDelayMs)
    }
    
    override fun shouldRetry(retryCount: Int): Boolean = retryCount < maxRetries
}

/**
 * In-memory implementation of LocalStorage for server-wins approach.
 */
class ServerWinsStorage<T : SyncableEntity> : LocalStorage<T> {
    private val records = ConcurrentHashMap<String, SyncRecord<T>>()

    override suspend fun get(entityId: String): SyncRecord<T>? = records[entityId]

    override suspend fun getAll(): List<SyncRecord<T>> = records.values.toList()

    override suspend fun getPending(): List<SyncRecord<T>> = 
        records.values.filter { it.syncState == SyncState.PENDING_UPLOAD }

    override suspend fun getConflicts(): List<SyncRecord<T>> = 
        records.values.filter { it.syncState == SyncState.CONFLICT }

    override suspend fun save(record: SyncRecord<T>) {
        records[record.entityId] = record
    }

    override suspend fun delete(entityId: String) {
        records.remove(entityId)
    }

    override suspend fun clear() {
        records.clear()
    }
}

/**
 * Mock API for server-wins testing.
 */
class ServerWinsMockApi<T : SyncableEntity>(
    private val data: MutableMap<String, T> = mutableMapOf()
) : SyncApi<T> {
    var fetchCount = 0
        private set
    var pushCount = 0
        private set
    var shouldFail = false

    override suspend fun fetch(entityId: String): T? {
        fetchCount++
        if (shouldFail) throw RuntimeException("Network error")
        return data[entityId]
    }

    override suspend fun fetchAll(since: Long?): List<T> {
        fetchCount++
        if (shouldFail) throw RuntimeException("Network error")
        return if (since != null) {
            data.values.filter { it.lastModified > since }
        } else {
            data.values.toList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun push(entity: T): T {
        pushCount++
        if (shouldFail) throw RuntimeException("Network error")
        val updated = when (entity) {
            is Note -> entity.copy(version = entity.version + 1) as T
            else -> entity
        }
        data[entity.id] = updated
        return updated
    }

    override suspend fun delete(entityId: String): Boolean {
        if (shouldFail) throw RuntimeException("Network error")
        return data.remove(entityId) != null
    }

    fun setRemoteData(entity: T) {
        data[entity.id] = entity
    }
}
