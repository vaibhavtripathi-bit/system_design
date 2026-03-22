package com.systemdesign.syncengine

import com.systemdesign.syncengine.approach_02_crdt.*
import com.systemdesign.syncengine.common.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class Approach02CrdtTest {

    private lateinit var api: MockCrdtApi
    private lateinit var storage: InMemoryCrdtStorage
    private lateinit var clock: FakeClock
    private lateinit var engine: CrdtSyncEngine

    @BeforeEach
    fun setup() {
        api = MockCrdtApi()
        storage = InMemoryCrdtStorage()
        clock = FakeClock(1000)
        engine = CrdtSyncEngine(api, storage, clock)
    }

    @Test
    fun `creates crdt note with field timestamps`() {
        clock.set(1000)
        val note = CrdtNote.create("Title", "Content", clock)
        
        assertEquals("Title", note.title.value)
        assertEquals(1000, note.title.timestamp)
        assertEquals("Content", note.content.value)
        assertEquals(1000, note.content.timestamp)
    }

    @Test
    fun `updates field with new timestamp`() {
        clock.set(1000)
        val note = CrdtNote.create("Title", "Content", clock)
        
        clock.set(2000)
        val updated = note.withTitle("New Title", clock)
        
        assertEquals("New Title", updated.title.value)
        assertEquals(2000, updated.title.timestamp)
        assertEquals("Content", updated.content.value)
        assertEquals(1000, updated.content.timestamp)
    }

    @Test
    fun `saves note locally`() = runTest {
        val note = CrdtNote.create("Test", "Content", clock)
        
        engine.saveLocal(note)
        
        val saved = engine.getLocal(note.id)
        assertNotNull(saved)
        assertEquals("Test", saved?.title?.value)
    }

    @Test
    fun `merges notes with per-field lww`() {
        clock.set(1000)
        val local = CrdtNote.create("Local Title", "Local Content", clock)
        
        clock.set(1500)
        val localUpdated = local.withTitle("Updated Local Title", clock)
        
        clock.set(2000)
        val remote = CrdtNote(
            id = local.id,
            title = FieldValue("Remote Title", 1200),
            content = FieldValue("Remote Content", 2000),
            version = 2,
            lastModified = 2000
        )
        
        val merged = engine.merge(localUpdated, remote)
        
        assertEquals("Updated Local Title", merged.title.value)
        assertEquals("Remote Content", merged.content.value)
    }

    @Test
    fun `sync pushes local changes`() = runTest {
        val note = CrdtNote.create("Test", "Content", clock)
        engine.saveLocal(note)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        assertEquals(1, api.pushCount)
    }

    @Test
    fun `sync pulls remote changes`() = runTest {
        val remoteNote = CrdtNote(
            id = "remote-1",
            title = FieldValue("Remote", 1000),
            content = FieldValue("Content", 1000),
            version = 1,
            lastModified = 1000
        )
        api.setRemoteData(remoteNote)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        val local = engine.getLocal("remote-1")
        assertNotNull(local)
        assertEquals("Remote", local?.title?.value)
    }

    @Test
    fun `sync merges concurrent changes`() = runTest {
        val note = CrdtNote.create("Original", "Original Content", clock)
        engine.saveLocal(note)
        engine.sync()
        
        clock.set(2000)
        val localUpdated = note.withTitle("Local Title", clock)
        engine.saveLocal(localUpdated)
        
        clock.set(2500)
        val remoteUpdated = CrdtNote(
            id = note.id,
            title = FieldValue("Remote Title", 1500),
            content = FieldValue("Remote Content", 2500),
            version = 2,
            lastModified = 2500
        )
        api.setRemoteData(remoteUpdated)
        
        val result = engine.sync()
        
        assertTrue(result is SyncResult.Success)
        val synced = engine.getLocal(note.id)
        assertNotNull(synced)
        assertEquals("Local Title", synced?.title?.value)
        assertEquals("Remote Content", synced?.content?.value)
    }

    @Test
    fun `gets all local notes`() = runTest {
        val note1 = CrdtNote.create("Note 1", "Content 1", clock)
        val note2 = CrdtNote.create("Note 2", "Content 2", clock)
        
        engine.saveLocal(note1)
        engine.saveLocal(note2)
        
        val all = engine.getAllLocal()
        assertEquals(2, all.size)
    }

    @Test
    fun `sync state tracks pending count`() = runTest {
        val note1 = CrdtNote.create("Note 1", "Content 1", clock)
        val note2 = CrdtNote.create("Note 2", "Content 2", clock)
        
        engine.saveLocal(note1)
        engine.saveLocal(note2)
        
        val state = engine.getSyncState().value
        assertEquals(2, state.pendingCount)
    }

    @Test
    fun `merge handles deleted flag`() {
        val local = CrdtNote(
            id = "note-1",
            title = FieldValue("Title", 1000),
            content = FieldValue("Content", 1000),
            isDeleted = true,
            version = 1,
            lastModified = 1000
        )
        val remote = CrdtNote(
            id = "note-1",
            title = FieldValue("Title", 1000),
            content = FieldValue("Content", 1000),
            isDeleted = false,
            version = 1,
            lastModified = 1000
        )
        
        val merged = engine.merge(local, remote)
        
        assertTrue(merged.isDeleted)
    }

    @Test
    fun `merge takes higher version`() {
        val local = CrdtNote(
            id = "note-1",
            title = FieldValue("Title", 1000),
            content = FieldValue("Content", 1000),
            version = 3,
            lastModified = 1000
        )
        val remote = CrdtNote(
            id = "note-1",
            title = FieldValue("Title", 1000),
            content = FieldValue("Content", 1000),
            version = 5,
            lastModified = 1000
        )
        
        val merged = engine.merge(local, remote)
        
        assertEquals(6, merged.version)
    }
}
