package com.systemdesign.syncengine

import com.systemdesign.syncengine.approach_03_server_wins.*
import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class Approach03ServerWinsTest {

    private lateinit var api: ServerWinsMockApi<Note>
    private lateinit var storage: ServerWinsStorage<Note>
    private lateinit var clock: FakeClock
    private lateinit var engine: ServerWinsSyncEngine<Note>

    @BeforeEach
    fun setup() {
        api = ServerWinsMockApi()
        storage = ServerWinsStorage()
        clock = FakeClock(1000)
        engine = ServerWinsSyncEngine(api, storage, clock)
    }

    @Test
    fun `saves note locally`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        
        engine.saveLocal(note)
        
        val saved = engine.getLocal(note.id)
        assertNotNull(saved)
        assertEquals("Test", saved?.title)
    }

    @Test
    fun `pushes note to server`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        
        val result = engine.push(note)
        
        assertTrue(result is SyncResult.Success)
        assertEquals(1, api.pushCount)
    }

    @Test
    fun `server wins on conflict`() = runTest {
        val localNote = Note(id = "note-1", title = "Local", content = "Local Content", 
            version = 1, lastModified = 1000)
        val remoteNote = Note(id = "note-1", title = "Server", content = "Server Content", 
            version = 5, lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        val result = engine.push(localNote)
        
        assertTrue(result is SyncResult.Conflict)
        val conflict = result as SyncResult.Conflict
        assertEquals("Server", conflict.resolved?.title)
    }

    @Test
    fun `sync pulls remote changes first`() = runTest {
        val remoteNote = Note(id = "remote-1", title = "Remote", content = "Content", 
            version = 1, lastModified = 1000)
        api.setRemoteData(remoteNote)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        val local = engine.getLocal("remote-1")
        assertNotNull(local)
        assertEquals("Remote", local?.title)
    }

    @Test
    fun `sync pushes local changes after pull`() = runTest {
        val note = Note(title = "Local", content = "Content", lastModified = 1000)
        engine.saveLocal(note)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        assertTrue(api.pushCount > 0)
    }

    @Test
    fun `server version overwrites local on sync`() = runTest {
        val localNote = Note(id = "note-1", title = "Local", content = "Local", 
            version = 1, lastModified = 1000)
        engine.saveLocal(localNote)
        
        val remoteNote = Note(id = "note-1", title = "Server", content = "Server", 
            version = 5, lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        engine.sync()
        
        val synced = engine.getLocal("note-1")
        assertEquals("Server", synced?.title)
    }

    @Test
    fun `forcePull clears local and fetches all remote`() = runTest {
        val localNote = Note(id = "local-1", title = "Local", content = "Content", lastModified = 1000)
        engine.saveLocal(localNote)
        
        val remoteNote = Note(id = "remote-1", title = "Remote", content = "Content", lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        val result = engine.forcePull()
        
        assertTrue(result is SyncResult.Success)
        assertNull(engine.getLocal("local-1"))
        assertNotNull(engine.getLocal("remote-1"))
    }

    @Test
    fun `handles network error on push`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        api.shouldFail = true
        
        val result = engine.push(note)
        
        assertTrue(result is SyncResult.Error)
        val state = engine.getSyncState().value
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `resolve conflict with KEEP_LOCAL`() = runTest {
        val localNote = Note(id = "note-1", title = "Local", content = "Content", lastModified = 1000)
        storage.save(SyncRecord(
            entityId = localNote.id,
            entityType = "Note",
            data = localNote,
            localVersion = 1,
            serverVersion = 1,
            lastModified = 1000,
            syncState = SyncState.CONFLICT
        ))
        
        val result = engine.resolveConflict(localNote.id, ConflictResolution.KEEP_LOCAL)
        
        assertTrue(result is SyncResult.Success)
        assertEquals("Local", (result as SyncResult.Success).data.title)
    }

    @Test
    fun `resolve conflict with KEEP_REMOTE`() = runTest {
        val localNote = Note(id = "note-1", title = "Local", content = "Content", lastModified = 1000)
        val remoteNote = Note(id = "note-1", title = "Remote", content = "Remote", lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        storage.save(SyncRecord(
            entityId = localNote.id,
            entityType = "Note",
            data = localNote,
            localVersion = 1,
            serverVersion = 1,
            lastModified = 1000,
            syncState = SyncState.CONFLICT
        ))
        
        val result = engine.resolveConflict(localNote.id, ConflictResolution.KEEP_REMOTE)
        
        assertTrue(result is SyncResult.Success)
        assertEquals("Remote", (result as SyncResult.Success).data.title)
    }

    @Test
    fun `sync state tracks pending and last sync time`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        engine.saveLocal(note)
        
        assertEquals(1, engine.getSyncState().value.pendingCount)
        assertNull(engine.getSyncState().value.lastSyncTime)
        
        clock.set(5000)
        engine.sync()
        
        assertEquals(5000, engine.getSyncState().value.lastSyncTime)
    }

    @Test
    fun `gets all local notes`() = runTest {
        val note1 = Note(title = "Note 1", content = "Content 1", lastModified = 1000)
        val note2 = Note(title = "Note 2", content = "Content 2", lastModified = 1000)
        
        engine.saveLocal(note1)
        engine.saveLocal(note2)
        
        val all = engine.getAllLocal()
        assertEquals(2, all.size)
    }

    @Test
    fun `exponential backoff retry policy`() {
        val policy = ExponentialBackoffRetry(
            initialDelayMs = 1000,
            maxDelayMs = 60000,
            maxRetries = 5
        )
        
        assertEquals(1000, policy.getNextRetryDelay(0))
        assertEquals(2000, policy.getNextRetryDelay(1))
        assertEquals(4000, policy.getNextRetryDelay(2))
        assertEquals(8000, policy.getNextRetryDelay(3))
        assertEquals(16000, policy.getNextRetryDelay(4))
        
        assertTrue(policy.shouldRetry(4))
        assertFalse(policy.shouldRetry(5))
    }
}
