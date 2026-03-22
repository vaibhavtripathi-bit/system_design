/**
 * # Approach 02: CRDT-Inspired Sync Engine
 *
 * ## Pattern Used
 * Conflict-free Replicated Data Type (CRDT) inspired approach where changes
 * are designed to be mergeable without conflicts.
 *
 * ## How It Works
 * 1. Each field change is tracked separately with its own timestamp
 * 2. Merging takes the latest value for each field
 * 3. No data loss - all changes are preserved at field level
 * 4. Changes from different devices can be merged seamlessly
 *
 * ## Trade-offs
 * - **Pros:**
 *   - No data loss - preserves all concurrent changes
 *   - Mathematically proven to converge
 *   - Works well for collaborative editing
 *   - No central coordinator needed
 *
 * - **Cons:**
 *   - More complex implementation
 *   - Higher storage overhead (per-field timestamps)
 *   - May produce unexpected merged results
 *   - Not all data types are naturally CRDT-compatible
 *
 * ## When to Prefer This Approach
 * - Collaborative editing scenarios
 * - When data loss is unacceptable
 * - Multi-device sync with frequent conflicts
 * - When changes to different fields should be preserved
 *
 * ## Comparison with Other Approaches
 * - **vs LWW (Approach 01):** CRDT preserves all changes; LWW discards losing changes
 * - **vs Server-wins (Approach 03):** CRDT merges intelligently; Server-wins is simpler but less flexible
 */
package com.systemdesign.syncengine.approach_02_crdt

import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CRDT-friendly entity with per-field timestamps.
 */
data class CrdtNote(
    override val id: String = UUID.randomUUID().toString(),
    val title: FieldValue<String>,
    val content: FieldValue<String>,
    override val version: Long = 1,
    override val lastModified: Long = System.currentTimeMillis(),
    override val isDeleted: Boolean = false
) : SyncableEntity {
    
    companion object {
        fun create(title: String, content: String, clock: Clock = SystemClock): CrdtNote {
            val now = clock.now()
            return CrdtNote(
                title = FieldValue(title, now),
                content = FieldValue(content, now),
                lastModified = now
            )
        }
    }
    
    fun withTitle(newTitle: String, clock: Clock = SystemClock): CrdtNote {
        val now = clock.now()
        return copy(
            title = FieldValue(newTitle, now),
            version = version + 1,
            lastModified = now
        )
    }
    
    fun withContent(newContent: String, clock: Clock = SystemClock): CrdtNote {
        val now = clock.now()
        return copy(
            content = FieldValue(newContent, now),
            version = version + 1,
            lastModified = now
        )
    }
}

/**
 * Wrapper for a field value with its own timestamp.
 */
data class FieldValue<T>(
    val value: T,
    val timestamp: Long
)

/**
 * CRDT-based Sync Engine implementation.
 */
class CrdtSyncEngine(
    private val api: CrdtSyncApi,
    private val storage: CrdtLocalStorage,
    private val clock: Clock = SystemClock
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncEngineState())

    suspend fun sync(): SyncResult<List<CrdtNote>> = mutex.withLock {
        _state.update { it.copy(isSyncing = true, lastError = null) }
        
        try {
            val pendingUploads = storage.getPending()
            val results = mutableListOf<CrdtNote>()
            
            for (record in pendingUploads) {
                val remote = api.fetch(record.entityId)
                
                if (remote == null) {
                    val pushed = api.push(record.data)
                    storage.save(record.copy(
                        data = pushed,
                        serverVersion = pushed.version,
                        syncState = SyncState.SYNCED
                    ))
                    results.add(pushed)
                } else {
                    val merged = merge(record.data, remote)
                    val pushed = api.push(merged)
                    storage.save(record.copy(
                        data = pushed,
                        serverVersion = pushed.version,
                        syncState = SyncState.SYNCED
                    ))
                    results.add(pushed)
                }
            }
            
            val lastSync = _state.value.lastSyncTime
            val remoteEntities = api.fetchAll(lastSync)
            
            for (remote in remoteEntities) {
                val local = storage.get(remote.id)
                
                if (local == null) {
                    storage.save(CrdtSyncRecord(
                        entityId = remote.id,
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        lastModified = remote.lastModified,
                        syncState = SyncState.SYNCED
                    ))
                    results.add(remote)
                } else if (local.syncState == SyncState.SYNCED) {
                    storage.save(local.copy(
                        data = remote,
                        localVersion = remote.version,
                        serverVersion = remote.version,
                        lastModified = remote.lastModified
                    ))
                    results.add(remote)
                } else {
                    val merged = merge(local.data, remote)
                    storage.save(local.copy(
                        data = merged,
                        syncState = SyncState.PENDING_UPLOAD
                    ))
                    results.add(merged)
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

    fun merge(local: CrdtNote, remote: CrdtNote): CrdtNote {
        return CrdtNote(
            id = local.id,
            title = mergeField(local.title, remote.title),
            content = mergeField(local.content, remote.content),
            version = maxOf(local.version, remote.version) + 1,
            lastModified = maxOf(local.lastModified, remote.lastModified),
            isDeleted = local.isDeleted || remote.isDeleted
        )
    }

    private fun <T> mergeField(local: FieldValue<T>, remote: FieldValue<T>): FieldValue<T> {
        return if (local.timestamp >= remote.timestamp) local else remote
    }

    suspend fun saveLocal(note: CrdtNote) {
        val existing = storage.get(note.id)
        val record = if (existing != null) {
            existing.copy(
                data = note,
                localVersion = note.version,
                lastModified = clock.now(),
                syncState = SyncState.PENDING_UPLOAD
            )
        } else {
            CrdtSyncRecord(
                entityId = note.id,
                data = note,
                localVersion = note.version,
                serverVersion = null,
                lastModified = clock.now(),
                syncState = SyncState.PENDING_UPLOAD
            )
        }
        storage.save(record)
        _state.update { it.copy(pendingCount = storage.getPending().size) }
    }

    suspend fun getLocal(entityId: String): CrdtNote? = storage.get(entityId)?.data

    suspend fun getAllLocal(): List<CrdtNote> = storage.getAll().map { it.data }

    fun getSyncState(): StateFlow<SyncEngineState> = _state.asStateFlow()
}

/**
 * Sync record for CRDT entities.
 */
data class CrdtSyncRecord(
    val id: String = UUID.randomUUID().toString(),
    val entityId: String,
    val data: CrdtNote,
    val localVersion: Long,
    val serverVersion: Long?,
    val lastModified: Long,
    val syncState: SyncState
)

/**
 * Local storage for CRDT entities.
 */
interface CrdtLocalStorage {
    suspend fun get(entityId: String): CrdtSyncRecord?
    suspend fun getAll(): List<CrdtSyncRecord>
    suspend fun getPending(): List<CrdtSyncRecord>
    suspend fun save(record: CrdtSyncRecord)
    suspend fun delete(entityId: String)
    suspend fun clear()
}

/**
 * API for CRDT sync operations.
 */
interface CrdtSyncApi {
    suspend fun fetch(entityId: String): CrdtNote?
    suspend fun fetchAll(since: Long? = null): List<CrdtNote>
    suspend fun push(entity: CrdtNote): CrdtNote
}

/**
 * In-memory implementation of CRDT storage.
 */
class InMemoryCrdtStorage : CrdtLocalStorage {
    private val records = ConcurrentHashMap<String, CrdtSyncRecord>()

    override suspend fun get(entityId: String): CrdtSyncRecord? = records[entityId]

    override suspend fun getAll(): List<CrdtSyncRecord> = records.values.toList()

    override suspend fun getPending(): List<CrdtSyncRecord> = 
        records.values.filter { it.syncState == SyncState.PENDING_UPLOAD }

    override suspend fun save(record: CrdtSyncRecord) {
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
 * Mock CRDT API for testing.
 */
class MockCrdtApi(
    private val data: MutableMap<String, CrdtNote> = mutableMapOf()
) : CrdtSyncApi {
    var fetchCount = 0
        private set
    var pushCount = 0
        private set

    override suspend fun fetch(entityId: String): CrdtNote? {
        fetchCount++
        return data[entityId]
    }

    override suspend fun fetchAll(since: Long?): List<CrdtNote> {
        fetchCount++
        return if (since != null) {
            data.values.filter { it.lastModified > since }
        } else {
            data.values.toList()
        }
    }

    override suspend fun push(entity: CrdtNote): CrdtNote {
        pushCount++
        val updated = entity.copy(version = entity.version + 1)
        data[entity.id] = updated
        return updated
    }

    fun setRemoteData(note: CrdtNote) {
        data[note.id] = note
    }
}
