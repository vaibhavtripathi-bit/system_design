/**
 * # Approach 01: Last-Write-Wins (LWW) Sync Engine
 *
 * ## Pattern Used
 * Last-Write-Wins conflict resolution where the most recent modification
 * (based on timestamp) always wins in case of conflicts.
 *
 * ## How It Works
 * 1. Each entity has a lastModified timestamp
 * 2. When conflict detected (local vs remote versions differ):
 *    - Compare timestamps
 *    - Higher timestamp wins
 * 3. Losing changes are discarded
 * 4. Simple but potentially loses data
 *
 * ## Trade-offs
 * - **Pros:**
 *   - Simple to implement and understand
 *   - Deterministic conflict resolution
 *   - No user intervention needed
 *   - Works well for low-conflict scenarios
 *
 * - **Cons:**
 *   - Data loss: earlier changes are discarded
 *   - Clock synchronization issues across devices
 *   - Not suitable for collaborative editing
 *   - No merge of concurrent changes
 *
 * ## When to Prefer This Approach
 * - When data loss is acceptable
 * - For settings/preferences sync
 * - When conflicts are rare
 * - When simplicity is prioritized over completeness
 *
 * ## Comparison with Other Approaches
 * - **vs CRDT (Approach 02):** LWW is simpler but loses data; CRDT preserves all changes
 * - **vs Server-wins (Approach 03):** LWW considers timestamps; Server-wins always trusts server
 */
package com.systemdesign.syncengine.approach_01_lww

import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Last-Write-Wins Sync Engine implementation.
 */
class LastWriteWinsSyncEngine<T : SyncableEntity>(
    private val api: SyncApi<T>,
    private val storage: LocalStorage<T>,
    private val clock: Clock = SystemClock
) : SyncEngine<T> {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncEngineState())
    private val pendingChanges = MutableStateFlow<List<ChangeEvent<T>>>(emptyList())

    override suspend fun sync(): SyncResult<List<T>> = mutex.withLock {
        _state.update { it.copy(isSyncing = true, lastError = null) }
        
        try {
            val pendingUploads = storage.getPending()
            val uploadResults = mutableListOf<T>()
            
            for (record in pendingUploads) {
                when (val result = pushInternal(record)) {
                    is SyncResult.Success -> uploadResults.add(result.data)
                    is SyncResult.Conflict -> {
                        val resolved = resolveWithLWW(record.data, result.remote)
                        val pushResult = api.push(resolved)
                        storage.save(record.copy(
                            data = pushResult,
                            serverVersion = pushResult.version,
                            syncState = SyncState.SYNCED
                        ))
                        uploadResults.add(pushResult)
                    }
                    is SyncResult.Error -> {
                        storage.save(record.copy(syncState = SyncState.ERROR))
                    }
                }
            }
            
            val lastSync = _state.value.lastSyncTime
            val remoteEntities = api.fetchAll(lastSync)
            
            for (remote in remoteEntities) {
                val local = storage.get(remote.id)
                if (local == null) {
                    storage.save(SyncRecord(
                        entityId = remote.id,
                        entityType = remote::class.simpleName ?: "Unknown",
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        lastModified = remote.lastModified,
                        syncState = SyncState.SYNCED
                    ))
                } else if (local.syncState == SyncState.SYNCED) {
                    storage.save(local.copy(
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        lastModified = remote.lastModified
                    ))
                }
            }
            
            _state.update { it.copy(
                isSyncing = false,
                lastSyncTime = clock.now(),
                pendingCount = 0
            ) }
            
            SyncResult.Success(storage.getAll().map { it.data }, clock.now())
        } catch (e: Exception) {
            _state.update { it.copy(isSyncing = false, lastError = e) }
            SyncResult.Error(e)
        }
    }

    override suspend fun push(entity: T): SyncResult<T> = mutex.withLock {
        val record = SyncRecord(
            entityId = entity.id,
            entityType = entity::class.simpleName ?: "Unknown",
            data = entity,
            localVersion = entity.version,
            serverVersion = null,
            lastModified = clock.now(),
            syncState = SyncState.PENDING_UPLOAD
        )
        storage.save(record)
        updatePendingCount()
        
        pushInternal(record)
    }

    private suspend fun pushInternal(record: SyncRecord<T>): SyncResult<T> {
        return try {
            val remote = api.fetch(record.entityId)
            
            if (remote == null || remote.version == record.serverVersion) {
                val pushed = api.push(record.data)
                storage.save(record.copy(
                    data = pushed,
                    serverVersion = pushed.version,
                    syncState = SyncState.SYNCED
                ))
                updatePendingCount()
                SyncResult.Success(pushed, pushed.version)
            } else {
                val resolved = resolveWithLWW(record.data, remote)
                SyncResult.Conflict(record.data, remote, resolved)
            }
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveWithLWW(local: T, remote: T): T {
        return if (local.lastModified >= remote.lastModified) local else remote
    }

    override suspend fun pull(entityId: String): SyncResult<T> = mutex.withLock {
        try {
            val remote = api.fetch(entityId)
                ?: return SyncResult.Error(NoSuchElementException("Entity not found: $entityId"))
            
            val local = storage.get(entityId)
            
            if (local == null) {
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
            } else if (local.syncState == SyncState.PENDING_UPLOAD) {
                val resolved = resolveWithLWW(local.data, remote)
                SyncResult.Conflict(local.data, remote, resolved)
            } else {
                storage.save(local.copy(
                    data = remote,
                    localVersion = remote.version,
                    serverVersion = remote.version,
                    lastModified = remote.lastModified,
                    syncState = SyncState.SYNCED
                ))
                SyncResult.Success(remote, remote.version)
            }
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }

    override suspend fun resolveConflict(entityId: String, resolution: ConflictResolution): SyncResult<T> = mutex.withLock {
        val record = storage.get(entityId)
            ?: return SyncResult.Error(NoSuchElementException("Entity not found: $entityId"))
        
        if (record.syncState != SyncState.CONFLICT) {
            return SyncResult.Error(IllegalStateException("Entity is not in conflict state"))
        }
        
        val resolved = when (resolution) {
            ConflictResolution.KEEP_LOCAL -> record.data
            ConflictResolution.KEEP_REMOTE -> record.conflictData ?: record.data
            ConflictResolution.MERGE -> resolveWithLWW(record.data, record.conflictData ?: record.data)
            ConflictResolution.MANUAL -> return SyncResult.Error(UnsupportedOperationException("Manual resolution not supported in LWW"))
        }
        
        try {
            val pushed = api.push(resolved)
            storage.save(record.copy(
                data = pushed,
                serverVersion = pushed.version,
                syncState = SyncState.SYNCED,
                conflictData = null
            ))
            updatePendingCount()
            SyncResult.Success(pushed, pushed.version)
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }

    override fun getPendingChanges(): Flow<List<ChangeEvent<T>>> = pendingChanges.asStateFlow()

    override fun getSyncState(): StateFlow<SyncEngineState> = _state.asStateFlow()

    private suspend fun updatePendingCount() {
        val pending = storage.getPending()
        val conflicts = storage.getConflicts()
        _state.update { it.copy(
            pendingCount = pending.size,
            conflictCount = conflicts.size
        ) }
    }

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
        updatePendingCount()
    }

    suspend fun getLocal(entityId: String): T? = storage.get(entityId)?.data

    suspend fun getAllLocal(): List<T> = storage.getAll().map { it.data }
}

/**
 * In-memory implementation of LocalStorage for testing.
 */
class InMemoryStorage<T : SyncableEntity> : LocalStorage<T> {
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
 * Mock API for testing.
 */
class MockSyncApi<T : SyncableEntity>(
    private val data: MutableMap<String, T> = mutableMapOf()
) : SyncApi<T> {
    var fetchCount = 0
        private set
    var pushCount = 0
        private set

    override suspend fun fetch(entityId: String): T? {
        fetchCount++
        return data[entityId]
    }

    override suspend fun fetchAll(since: Long?): List<T> {
        fetchCount++
        return if (since != null) {
            data.values.filter { it.lastModified > since }
        } else {
            data.values.toList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun push(entity: T): T {
        pushCount++
        val updated = when (entity) {
            is Note -> entity.copy(version = entity.version + 1) as T
            else -> entity
        }
        data[entity.id] = updated
        return updated
    }

    override suspend fun delete(entityId: String): Boolean {
        return data.remove(entityId) != null
    }

    fun setRemoteData(entity: T) {
        data[entity.id] = entity
    }
}
