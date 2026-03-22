/**
 * Common interfaces and data classes for the Offline-First Sync Engine.
 *
 * This module demonstrates system design for offline-first synchronization,
 * focusing on:
 * - Conflict resolution strategies
 * - Dirty state tracking
 * - Sync queue management
 * - Retry scheduling
 */
package com.systemdesign.syncengine.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Represents a syncable entity with version tracking.
 */
interface SyncableEntity {
    val id: String
    val version: Long
    val lastModified: Long
    val isDeleted: Boolean
}

/**
 * Generic sync record wrapping any entity type.
 */
data class SyncRecord<T>(
    val id: String = UUID.randomUUID().toString(),
    val entityId: String,
    val entityType: String,
    val data: T,
    val localVersion: Long,
    val serverVersion: Long?,
    val lastModified: Long,
    val syncState: SyncState,
    val conflictData: T? = null
)

/**
 * State of a sync record.
 */
enum class SyncState {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    CONFLICT,
    ERROR
}

/**
 * Result of a sync operation.
 */
sealed class SyncResult<T> {
    data class Success<T>(val data: T, val version: Long) : SyncResult<T>()
    data class Conflict<T>(val local: T, val remote: T, val resolved: T?) : SyncResult<T>()
    data class Error<T>(val exception: Throwable) : SyncResult<T>()
}

/**
 * Sync operation types.
 */
enum class SyncOperation {
    CREATE,
    UPDATE,
    DELETE
}

/**
 * Change event representing a local modification.
 */
data class ChangeEvent<T>(
    val entityId: String,
    val entityType: String,
    val operation: SyncOperation,
    val data: T?,
    val timestamp: Long,
    val localVersion: Long
)

/**
 * Core sync engine interface.
 */
interface SyncEngine<T> {
    suspend fun sync(): SyncResult<List<T>>
    suspend fun push(entity: T): SyncResult<T>
    suspend fun pull(entityId: String): SyncResult<T>
    suspend fun resolveConflict(entityId: String, resolution: ConflictResolution): SyncResult<T>
    fun getPendingChanges(): Flow<List<ChangeEvent<T>>>
    fun getSyncState(): StateFlow<SyncEngineState>
}

/**
 * Overall sync engine state.
 */
data class SyncEngineState(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val conflictCount: Int = 0,
    val lastSyncTime: Long? = null,
    val lastError: Throwable? = null
)

/**
 * Conflict resolution choice.
 */
enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
    MERGE,
    MANUAL
}

/**
 * Conflict resolver interface for custom resolution logic.
 */
interface ConflictResolver<T> {
    fun resolve(local: T, remote: T): T
    fun canAutoResolve(local: T, remote: T): Boolean
}

/**
 * Remote API interface for sync operations.
 */
interface SyncApi<T> {
    suspend fun fetch(entityId: String): T?
    suspend fun fetchAll(since: Long? = null): List<T>
    suspend fun push(entity: T): T
    suspend fun delete(entityId: String): Boolean
}

/**
 * Local storage interface.
 */
interface LocalStorage<T> {
    suspend fun get(entityId: String): SyncRecord<T>?
    suspend fun getAll(): List<SyncRecord<T>>
    suspend fun getPending(): List<SyncRecord<T>>
    suspend fun getConflicts(): List<SyncRecord<T>>
    suspend fun save(record: SyncRecord<T>)
    suspend fun delete(entityId: String)
    suspend fun clear()
}

/**
 * Simple note entity for testing.
 */
data class Note(
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    override val version: Long = 1,
    override val lastModified: Long = System.currentTimeMillis(),
    override val isDeleted: Boolean = false
) : SyncableEntity

/**
 * Clock interface for testability.
 */
interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FakeClock(private var time: Long = 0) : Clock {
    override fun now(): Long = time
    fun advance(millis: Long) { time += millis }
    fun set(millis: Long) { time = millis }
}
