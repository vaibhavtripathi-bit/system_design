package com.systemdesign.syncengine

import com.systemdesign.syncengine.approach_01_lww.*
import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class Approach01LwwTest {

    private lateinit var api: MockSyncApi<Note>
    private lateinit var storage: InMemoryStorage<Note>
    private lateinit var clock: FakeClock
    private lateinit var engine: LastWriteWinsSyncEngine<Note>

    @BeforeEach
    fun setup() {
        api = MockSyncApi()
        storage = InMemoryStorage()
        clock = FakeClock(1000)
        engine = LastWriteWinsSyncEngine(api, storage, clock)
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
    fun `syncs local changes to server`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        engine.saveLocal(note)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        assertEquals(1, api.pushCount)
    }

    @Test
    fun `pulls remote changes`() = runTest {
        val remoteNote = Note(id = "remote-1", title = "Remote", content = "From Server", lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        val result = engine.pull("remote-1")
        
        assertTrue(result is SyncResult.Success)
        val pulled = (result as SyncResult.Success).data
        assertEquals("Remote", pulled.title)
    }

    @Test
    fun `lww - local wins when local is newer`() = runTest {
        clock.set(2000)
        val localNote = Note(id = "note-1", title = "Local", content = "Local Content", lastModified = 2000)
        engine.saveLocal(localNote)
        
        val remoteNote = Note(id = "note-1", title = "Remote", content = "Remote Content", 
            version = 2, lastModified = 1500)
        api.setRemoteData(remoteNote)
        
        val result = engine.push(localNote)
        
        assertTrue(result is SyncResult.Conflict || result is SyncResult.Success)
        if (result is SyncResult.Conflict) {
            assertEquals("Local", result.resolved?.title)
        }
    }

    @Test
    fun `lww - remote wins when remote is newer`() = runTest {
        clock.set(1000)
        val localNote = Note(id = "note-1", title = "Local", content = "Local Content", lastModified = 1000)
        engine.saveLocal(localNote)
        
        val remoteNote = Note(id = "note-1", title = "Remote", content = "Remote Content", 
            version = 2, lastModified = 2000)
        api.setRemoteData(remoteNote)
        
        val result = engine.push(localNote)
        
        assertTrue(result is SyncResult.Conflict)
        assertEquals("Remote", (result as SyncResult.Conflict).resolved?.title)
    }

    @Test
    fun `sync state tracks pending count`() = runTest {
        val note1 = Note(title = "Note 1", content = "Content 1", lastModified = 1000)
        val note2 = Note(title = "Note 2", content = "Content 2", lastModified = 1000)
        
        engine.saveLocal(note1)
        engine.saveLocal(note2)
        
        val state = engine.getSyncState().value
        assertEquals(2, state.pendingCount)
    }

    @Test
    fun `sync updates last sync time`() = runTest {
        clock.set(5000)
        
        engine.sync()
        
        val state = engine.getSyncState().value
        assertEquals(5000, state.lastSyncTime)
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
    fun `handles sync error gracefully`() = runTest {
        val note = Note(title = "Test", content = "Content", lastModified = 1000)
        engine.saveLocal(note)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success || result is SyncResult.Error)
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
            syncState = SyncState.CONFLICT,
            conflictData = Note(id = "note-1", title = "Remote", content = "Remote", lastModified = 1500)
        ))
        
        val result = engine.resolveConflict(localNote.id, ConflictResolution.KEEP_LOCAL)
        
        assertTrue(result is SyncResult.Success)
        assertEquals("Local", (result as SyncResult.Success).data.title)
    }

    @Test
    fun `resolve conflict with KEEP_REMOTE`() = runTest {
        val localNote = Note(id = "note-1", title = "Local", content = "Content", lastModified = 1000)
        val remoteNote = Note(id = "note-1", title = "Remote", content = "Remote", lastModified = 1500)
        storage.save(SyncRecord(
            entityId = localNote.id,
            entityType = "Note",
            data = localNote,
            localVersion = 1,
            serverVersion = 1,
            lastModified = 1000,
            syncState = SyncState.CONFLICT,
            conflictData = remoteNote
        ))
        
        val result = engine.resolveConflict(localNote.id, ConflictResolution.KEEP_REMOTE)
        
        assertTrue(result is SyncResult.Success)
        assertEquals("Remote", (result as SyncResult.Success).data.title)
    }
}
