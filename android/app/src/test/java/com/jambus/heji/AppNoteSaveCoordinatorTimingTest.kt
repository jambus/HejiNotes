package com.jambus.heji

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppNoteSaveCoordinatorTimingTest {

    private class InMemoryNoteStorage : NoteReadWriter {
        val storage = mutableMapOf<String, String>()
        val saveCalls = mutableListOf<Pair<String, String>>()

        override fun saveText(document: VaultDocument, content: String): Boolean {
            val key = document.uri.toString()
            saveCalls.add(key to content)
            storage[key] = content
            return true
        }

        override fun refreshDocument(document: VaultDocument): VaultDocument {
            return document
        }

        override fun readNote(document: VaultDocument): NoteReadResult {
            val content = storage[document.uri.toString()]
            return if (content != null) {
                NoteReadResult.Success(content)
            } else {
                NoteReadResult.Failure(VaultFailureKind.READ_FAILED)
            }
        }
    }

    @Before
    fun setup() {
        AppNoteSaveCoordinator.resetForTests()
    }

    @Test
    fun `rejects stale saves with lower revision number`() {
        val fakeStorage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/meeting.md"), "meeting.md", "text/markdown")

        val latch1 = CountDownLatch(1)
        val success1 = AtomicBoolean(false)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Version 5 content", 5L) { s, _ ->
            success1.set(s)
            latch1.countDown()
        }
        assertTrue("Save revision 5 should complete", latch1.await(2, TimeUnit.SECONDS))
        assertTrue("Save revision 5 should succeed", success1.get())
        assertEquals("Version 5 content", fakeStorage.storage[doc.uri.toString()])

        // Now attempt to write an older revision 3 (e.g. late callback from destroyed Activity)
        val latch2 = CountDownLatch(1)
        val success2 = AtomicBoolean(true)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Stale revision 3 content", 3L) { s, _ ->
            success2.set(s)
            latch2.countDown()
        }
        assertTrue("Save revision 3 should complete", latch2.await(2, TimeUnit.SECONDS))
        assertFalse("Stale save revision 3 must be rejected", success2.get())
        assertEquals("Storage content must remain at revision 5", "Version 5 content", fakeStorage.storage[doc.uri.toString()])
        assertEquals("Storage must only have received 1 save call", 1, fakeStorage.saveCalls.size)

        // Monotonically equal or higher revision should succeed
        val latch3 = CountDownLatch(1)
        val success3 = AtomicBoolean(false)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Version 6 content", 6L) { s, _ ->
            success3.set(s)
            latch3.countDown()
        }
        assertTrue("Save revision 6 should complete", latch3.await(2, TimeUnit.SECONDS))
        assertTrue("Save revision 6 should succeed", success3.get())
        assertEquals("Version 6 content", fakeStorage.storage[doc.uri.toString()])
    }

    @Test
    fun `submitRead waits for in-flight pending serialization before reading disk`() {
        val fakeStorage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/todo.md"), "todo.md", "text/markdown")
        fakeStorage.storage[doc.uri.toString()] = "Old initial text"

        // Register pending serialization for revision 2
        AppNoteSaveCoordinator.registerPendingSerialization(doc.uri.toString(), 2L)

        val readStarted = CountDownLatch(1)
        val readResult = AtomicReference<NoteReadResult>()
        val readCompleted = CountDownLatch(1)

        // Queue read on background
        Thread {
            readStarted.countDown()
            AppNoteSaveCoordinator.submitRead(fakeStorage, doc) { result ->
                readResult.set(result)
                readCompleted.countDown()
            }
        }.start()

        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        // Verify read has not completed immediately because pending serialization latch is held
        assertFalse("Read must not finish while serialization is pending", readCompleted.await(100, TimeUnit.MILLISECONDS))

        // Now async JS serialization finishes and submits save revision 2
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "New text after JS serialization", 2L) { _, _ -> }

        // Read must now complete with the freshly saved text
        assertTrue("Read should unblock after save", readCompleted.await(2, TimeUnit.SECONDS))
        val res = readResult.get()
        assertTrue(res is NoteReadResult.Success)
        assertEquals("New text after JS serialization", (res as NoteReadResult.Success).content)
    }

    @Test
    fun `cancelPendingSerialization unblocks submitRead immediately`() {
        val fakeStorage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/draft.md"), "draft.md", "text/markdown")
        fakeStorage.storage[doc.uri.toString()] = "Existing draft"

        AppNoteSaveCoordinator.registerPendingSerialization(doc.uri.toString(), 1L)

        val readCompleted = CountDownLatch(1)
        val readResult = AtomicReference<NoteReadResult>()
        AppNoteSaveCoordinator.submitRead(fakeStorage, doc) { result ->
            readResult.set(result)
            readCompleted.countDown()
        }

        // Cancel serialization (e.g. JS eval failed or editor was closed)
        AppNoteSaveCoordinator.cancelPendingSerialization(doc.uri.toString())

        assertTrue("Read should complete quickly after cancel", readCompleted.await(2, TimeUnit.SECONDS))
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
        val res = readResult.get()
        assertTrue(res is NoteReadResult.Success)
        assertEquals("Existing draft", (res as NoteReadResult.Success).content)
    }

    @Test
    fun `photo session coordinates across rotation and auto-clears on observation`() {
        val capturePath = "/data/user/0/cache/photo_capture_1.jpg"
        val noteUri = "content://heji/notes/photo_target.md"
        val targetDoc = VaultDocument(TestUri(noteUri), "photo_target.md", "text/markdown")

        // 1. Photo session begins
        AppNoteSaveCoordinator.startPhotoSession(capturePath, noteUri)
        assertTrue(AppNoteSaveCoordinator.hasActivePhotoSession(capturePath))
        assertTrue(AppNoteSaveCoordinator.hasPhotoSession(capturePath))

        // 2. Observer (e.g. new Activity after rotation) attaches while still InProgress
        val observedResult = AtomicReference<AppNoteSaveCoordinator.PhotoSessionResult>()
        val observerLatch = CountDownLatch(1)
        AppNoteSaveCoordinator.observePhotoSession(capturePath) { result ->
            observedResult.set(result)
            observerLatch.countDown()
        }

        // 3. Background media & IO worker completes
        val refreshedDoc = targetDoc.copy(relativePath = "notes/photo_target.md")
        AppNoteSaveCoordinator.completePhotoSession(
            capturePath,
            true,
            refreshedDoc,
            "assets/photo_target/123-photo.jpg"
        )

        assertTrue(observerLatch.await(2, TimeUnit.SECONDS))
        val result = observedResult.get()
        assertTrue("Expected Success result", result is AppNoteSaveCoordinator.PhotoSessionResult.Success)
        val success = result as AppNoteSaveCoordinator.PhotoSessionResult.Success
        assertEquals(refreshedDoc, success.refreshedNote)
        assertEquals("assets/photo_target/123-photo.jpg", success.attachmentName)

        // Session should be automatically cleared from active sessions
        assertFalse("Active session must be cleared after observation", AppNoteSaveCoordinator.hasPhotoSession(capturePath))
    }

    @Test
    fun `photo session late observation receives completed result and auto-clears`() {
        val capturePath = "/data/user/0/cache/photo_capture_late.jpg"
        val noteUri = "content://heji/notes/late.md"
        val targetDoc = VaultDocument(TestUri(noteUri), "late.md", "text/markdown")

        AppNoteSaveCoordinator.startPhotoSession(capturePath, noteUri)

        // Background worker completes BEFORE the newly rotated Activity attaches observer
        AppNoteSaveCoordinator.completePhotoSession(
            capturePath,
            true,
            targetDoc,
            "assets/late/pic.jpg"
        )

        // hasActivePhotoSession is false because it's no longer InProgress, but hasPhotoSession is true
        assertFalse(AppNoteSaveCoordinator.hasActivePhotoSession(capturePath))
        assertTrue(AppNoteSaveCoordinator.hasPhotoSession(capturePath))

        // New Activity arrives and attaches observer
        val observedResult = AtomicReference<AppNoteSaveCoordinator.PhotoSessionResult>()
        AppNoteSaveCoordinator.observePhotoSession(capturePath) { result ->
            observedResult.set(result)
        }

        val res = observedResult.get()
        assertTrue("Late observer must receive completed Success result", res is AppNoteSaveCoordinator.PhotoSessionResult.Success)
        assertFalse("Session must be cleared after being consumed", AppNoteSaveCoordinator.hasPhotoSession(capturePath))
    }

    @Test
    fun `photo session failure reports error message and clears`() {
        val capturePath = "/data/user/0/cache/photo_capture_fail.jpg"
        val noteUri = "content://heji/notes/fail.md"

        AppNoteSaveCoordinator.startPhotoSession(capturePath, noteUri)
        AppNoteSaveCoordinator.completePhotoSession(capturePath, false, null, null, "Vault 写入空间不足")

        val observedResult = AtomicReference<AppNoteSaveCoordinator.PhotoSessionResult>()
        AppNoteSaveCoordinator.observePhotoSession(capturePath) { result ->
            observedResult.set(result)
        }

        val res = observedResult.get()
        assertTrue(res is AppNoteSaveCoordinator.PhotoSessionResult.Failure)
        assertEquals("Vault 写入空间不足", (res as AppNoteSaveCoordinator.PhotoSessionResult.Failure).message)
        assertFalse(AppNoteSaveCoordinator.hasPhotoSession(capturePath))
    }

    @Test
    fun `reopening note with base revision saves successfully on subsequent edits`() {
        val fakeStorage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/reopen.md"), "reopen.md", "text/markdown")
        val coordinator = RevisionSaveCoordinator()

        // 1. Initial session: save up to revision 5
        val latch1 = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Rev 5 content", 5L) { _, _ -> latch1.countDown() }
        assertTrue(latch1.await(2, TimeUnit.SECONDS))
        assertEquals(5L, AppNoteSaveCoordinator.getRevision(doc.uri.toString()))

        // 2. User exits to browser and reopens note: reset with baseRev = 5
        val baseRev = maxOf(coordinator.revision, AppNoteSaveCoordinator.getRevision(doc.uri.toString()))
        coordinator.reset(baseRev)
        assertEquals(5L, coordinator.revision)

        // 3. User edits note in new session -> revision 6
        val rev6 = coordinator.markEdited()
        assertEquals(6L, rev6)

        // 4. Save should succeed and NOT be rejected as stale
        val latch2 = CountDownLatch(1)
        val success2 = AtomicBoolean(false)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Rev 6 content", rev6) { s, _ ->
            success2.set(s)
            latch2.countDown()
        }
        assertTrue(latch2.await(2, TimeUnit.SECONDS))
        assertTrue("Subsequent edit after reopening must succeed", success2.get())
        assertEquals("Rev 6 content", fakeStorage.storage[doc.uri.toString()])
    }

    @Test
    fun `advanceRevision increments revision for media attachments`() {
        val noteUri = "content://heji/notes/media.md"
        val doc = VaultDocument(TestUri(noteUri), "media.md", "text/markdown")
        val fakeStorage = InMemoryNoteStorage()

        // Initial save at rev 3
        val latch1 = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(fakeStorage, doc, "Rev 3", 3L) { _, _ -> latch1.countDown() }
        assertTrue(latch1.await(2, TimeUnit.SECONDS))

        // Commit photo/video advances revision
        val advanced = AppNoteSaveCoordinator.advanceRevision(noteUri)
        assertEquals(4L, advanced)
        assertEquals(4L, AppNoteSaveCoordinator.getRevision(noteUri))

        // Next editor session starts at 4, next edit is 5
        val coordinator = RevisionSaveCoordinator()
        coordinator.reset(AppNoteSaveCoordinator.getRevision(noteUri))
        val nextEdit = coordinator.markEdited()
        assertEquals(5L, nextEdit)
    }

    @Test
    fun `cancelPhotoSession aborts in-flight processing and notifies observer`() {
        val capturePath = "/data/user/0/cache/photo_cancel.jpg"
        val noteUri = "content://heji/notes/cancel.md"

        AppNoteSaveCoordinator.startPhotoSession(capturePath, noteUri)
        assertFalse(AppNoteSaveCoordinator.isPhotoSessionCancelled(capturePath))

        val observedResult = AtomicReference<AppNoteSaveCoordinator.PhotoSessionResult>()
        val observerLatch = CountDownLatch(1)
        AppNoteSaveCoordinator.observePhotoSession(capturePath) { result ->
            observedResult.set(result)
            observerLatch.countDown()
        }

        // User cancels while saving
        AppNoteSaveCoordinator.cancelPhotoSession(capturePath)
        assertTrue(AppNoteSaveCoordinator.isPhotoSessionCancelled(capturePath))

        assertTrue("Observer should receive cancellation notice", observerLatch.await(2, TimeUnit.SECONDS))
        val res = observedResult.get()
        assertTrue(res is AppNoteSaveCoordinator.PhotoSessionResult.Failure)
        assertEquals("照片处理已取消", (res as AppNoteSaveCoordinator.PhotoSessionResult.Failure).message)
    }

    @Test
    fun `interleaved complete and observe photo session never drops notification`() {
        for (i in 0 until 50) {
            val capturePath = "/data/user/0/cache/photo_race_$i.jpg"
            val noteUri = "content://heji/notes/race_$i.md"
            val doc = VaultDocument(TestUri(noteUri), "race.md", "text/markdown")

            AppNoteSaveCoordinator.startPhotoSession(capturePath, noteUri)
            val resultReceived = AtomicReference<AppNoteSaveCoordinator.PhotoSessionResult>()
            val latch = CountDownLatch(1)

            val t1 = Thread {
                AppNoteSaveCoordinator.completePhotoSession(capturePath, true, doc, "photo_$i.jpg")
            }
            val t2 = Thread {
                AppNoteSaveCoordinator.observePhotoSession(capturePath) { res ->
                    resultReceived.set(res)
                    latch.countDown()
                }
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join()

            assertTrue("Observer must always receive result in iteration $i", latch.await(2, TimeUnit.SECONDS))
            assertTrue(resultReceived.get() is AppNoteSaveCoordinator.PhotoSessionResult.Success)
        }
    }

    @Test
    fun `submitSave does not advance noteRevisions when disk write fails`() {
        val failingStorage = object : NoteReadWriter {
            override fun saveText(document: VaultDocument, content: String): Boolean = false
            override fun refreshDocument(document: VaultDocument): VaultDocument = document
            override fun readNote(document: VaultDocument): NoteReadResult = NoteReadResult.Success("existing")
        }
        val doc = VaultDocument(TestUri("content://heji/notes/fail.md"), "fail.md", "text/markdown")

        val latch = CountDownLatch(1)
        val successRef = AtomicBoolean(true)
        AppNoteSaveCoordinator.submitSave(failingStorage, doc, "failed text", 10L) { success, _ ->
            successRef.set(success)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(successRef.get())
        assertEquals("noteRevisions must NOT be advanced on save failure", 0L, AppNoteSaveCoordinator.getRevision(doc.uri.toString()))
    }

    @Test
    fun `expired serialization rejects late javascript callback and releases once`() {
        val noteUri = "content://heji/notes/expired.md"
        var releases = 0
        val operation = AppNoteSaveCoordinator.beginSerialization(noteUri, 1L) { releases++ }

        AppNoteSaveCoordinator.expireSerializationForTests(operation)
        AppNoteSaveCoordinator.expireSerializationForTests(operation)

        assertEquals(1, releases)
        assertFalse(AppNoteSaveCoordinator.submitSnapshot(operation, "late body"))
        assertTrue(AppNoteSaveCoordinator.noteRecovery(noteUri) is AppNoteSaveCoordinator.NoteRecovery.Expired)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
    }

    @Test
    fun `superseded operation cannot affect newer save`() {
        val storage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/newer.md"), "newer.md", "text/markdown")
        val old = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 1L)
        val current = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 2L)

        assertFalse(AppNoteSaveCoordinator.submitSnapshot(old, "old"))
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(current, "new"))
        val completed = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(storage, doc, "new", 2L, current) { success, _ ->
            assertTrue(success)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("new", storage.storage[doc.uri.toString()])
    }

    @Test
    fun `expired operation followed by successful new save leaves no stale ids`() {
        val storage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/timeout-new.md"), "timeout-new.md", "text/markdown")
        val expired = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 1L)
        AppNoteSaveCoordinator.expireSerializationForTests(expired)
        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.Expired)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())

        val current = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 2L)
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(current, "current"))
        val completed = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(storage, doc, "current", 2L, current) { success, _ ->
            assertTrue(success)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.None)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
        assertFalse(AppNoteSaveCoordinator.hasRetainedSnapshotForTests(doc.uri.toString()))
    }

    @Test
    fun `failed write retains exact in-process snapshot for rotation`() {
        val storage = object : NoteReadWriter {
            override fun saveText(document: VaultDocument, content: String): Boolean = false
            override fun refreshDocument(document: VaultDocument): VaultDocument = document
            override fun readNote(document: VaultDocument): NoteReadResult = NoteReadResult.Success("saved vault body")
        }
        val doc = VaultDocument(TestUri("content://heji/notes/recover.md"), "recover.md", "text/markdown")
        val operation = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 4L)
        val exact = "unsaved\nbody & symbols"
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(operation, exact))
        val completed = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(storage, doc, exact, 4L, operation) { _, _ -> completed.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))

        val recovery = AppNoteSaveCoordinator.noteRecovery(doc.uri.toString())
        assertTrue(recovery is AppNoteSaveCoordinator.NoteRecovery.Failed)
        assertEquals(exact, (recovery as AppNoteSaveCoordinator.NoteRecovery.Failed).snapshot.content)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
    }

    @Test
    fun `successful save releases operation and snapshot`() {
        val storage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://heji/notes/clean.md"), "clean.md", "text/markdown")
        val operation = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 1L)
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(operation, "saved body"))
        val completed = CountDownLatch(1)

        AppNoteSaveCoordinator.submitSave(storage, doc, "saved body", 1L, operation) { success, _ ->
            assertTrue(success)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.None)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
        assertFalse(AppNoteSaveCoordinator.hasRetainedSnapshotForTests(doc.uri.toString()))
    }

    @Test
    fun `older successful write cleanup cannot remove newer operation`() {
        val saveEntered = CountDownLatch(1)
        val allowSave = CountDownLatch(1)
        val storage = object : NoteReadWriter {
            override fun saveText(document: VaultDocument, content: String): Boolean {
                saveEntered.countDown()
                assertTrue(allowSave.await(2, TimeUnit.SECONDS))
                return true
            }
            override fun refreshDocument(document: VaultDocument): VaultDocument = document
            override fun readNote(document: VaultDocument): NoteReadResult = NoteReadResult.Success("saved")
        }
        val doc = VaultDocument(TestUri("content://heji/notes/identity.md"), "identity.md", "text/markdown")
        val older = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 1L)
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(older, "older"))
        val olderCompleted = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(storage, doc, "older", 1L, older) { _, _ -> olderCompleted.countDown() }
        assertTrue(saveEntered.await(2, TimeUnit.SECONDS))

        val newer = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 2L)
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(newer, "newer"))
        allowSave.countDown()
        assertTrue(olderCompleted.await(2, TimeUnit.SECONDS))

        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.Active)
        assertEquals(1, AppNoteSaveCoordinator.pendingSaveCountForTests())
        assertTrue(AppNoteSaveCoordinator.hasRetainedSnapshotForTests(doc.uri.toString()))
        AppNoteSaveCoordinator.discardNoteRecovery(doc.uri.toString())
    }

    @Test
    fun `vault switch clears snapshots and rejects old operation callbacks`() {
        val storage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://old-vault/notes/draft.md"), "draft.md", "text/markdown")
        val operation = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 3L)
        assertTrue(AppNoteSaveCoordinator.submitSnapshot(operation, "old vault unsaved body"))

        AppNoteSaveCoordinator.clearVaultScopedState()

        assertFalse(AppNoteSaveCoordinator.submitSnapshot(operation, "late callback"))
        val completed = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        AppNoteSaveCoordinator.submitSave(storage, doc, "late callback", 3L, operation) { success, _ ->
            accepted.set(success)
            completed.countDown()
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertFalse(accepted.get())
        assertTrue(storage.saveCalls.isEmpty())
        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.None)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
        assertFalse(AppNoteSaveCoordinator.hasRetainedSnapshotForTests(doc.uri.toString()))
    }

    @Test
    fun `vault clear releases serialization once and rejects every late path`() {
        val storage = InMemoryNoteStorage()
        val doc = VaultDocument(TestUri("content://old-vault/notes/serializing.md"), "serializing.md", "text/markdown")
        var releases = 0
        val operation = AppNoteSaveCoordinator.beginSerialization(doc.uri.toString(), 5L) { releases++ }

        AppNoteSaveCoordinator.clearVaultScopedState()
        AppNoteSaveCoordinator.clearVaultScopedState()
        AppNoteSaveCoordinator.expireSerializationForTests(operation)
        assertFalse(AppNoteSaveCoordinator.submitSnapshot(operation, "late body"))
        val completed = CountDownLatch(1)
        AppNoteSaveCoordinator.submitSave(storage, doc, "late body", 5L, operation) { success, _ ->
            assertFalse(success)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, releases)
        assertTrue(storage.saveCalls.isEmpty())
        assertTrue(AppNoteSaveCoordinator.noteRecovery(doc.uri.toString()) is AppNoteSaveCoordinator.NoteRecovery.None)
        assertEquals(0, AppNoteSaveCoordinator.pendingSaveCountForTests())
        assertFalse(AppNoteSaveCoordinator.hasRetainedSnapshotForTests(doc.uri.toString()))
    }

    @Test
    fun `cancel and commit boundary is atomic`() {
        repeat(100) { index ->
            val path = "/tmp/photo-boundary-$index"
            AppNoteSaveCoordinator.startPhotoSession(path, "content://heji/notes/photo.md")
            assertTrue(AppNoteSaveCoordinator.transitionPhotoSession(path,
                AppNoteSaveCoordinator.PhotoPhase.PROCESSING,
                AppNoteSaveCoordinator.PhotoPhase.ATTACHMENTS_WRITING))
            assertTrue(AppNoteSaveCoordinator.transitionPhotoSession(path,
                AppNoteSaveCoordinator.PhotoPhase.ATTACHMENTS_WRITING,
                AppNoteSaveCoordinator.PhotoPhase.READY_TO_COMMIT))
            val cancelResult = AtomicReference<AppNoteSaveCoordinator.PhotoCancelResult>()
            val commitWon = AtomicBoolean(false)
            val cancelThread = Thread { cancelResult.set(AppNoteSaveCoordinator.requestPhotoCancel(path)) }
            val commitThread = Thread { commitWon.set(AppNoteSaveCoordinator.transitionPhotoSession(path,
                AppNoteSaveCoordinator.PhotoPhase.READY_TO_COMMIT,
                AppNoteSaveCoordinator.PhotoPhase.COMMITTING)) }
            cancelThread.start(); commitThread.start(); cancelThread.join(); commitThread.join()

            if (commitWon.get()) {
                assertEquals(AppNoteSaveCoordinator.PhotoCancelResult.TOO_LATE, cancelResult.get())
            } else {
                assertEquals(AppNoteSaveCoordinator.PhotoCancelResult.ACCEPTED, cancelResult.get())
                assertEquals(AppNoteSaveCoordinator.PhotoPhase.CANCELLED, AppNoteSaveCoordinator.photoPhase(path))
            }
            AppNoteSaveCoordinator.clearPhotoSession(path)
        }
    }
}
