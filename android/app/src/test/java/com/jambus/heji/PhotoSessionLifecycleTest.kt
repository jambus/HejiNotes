package com.jambus.heji

import android.net.TestUri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhotoSessionLifecycleTest {
    private val path = "/missing/cache/capture.jpg"
    private val note = VaultDocument(TestUri("content://test/note"), "note.md", "text/markdown")

    @Before fun reset() { AppNoteSaveCoordinator.resetForTests() }

    @Test fun `queued dead owner cannot consume terminal without cache and new owner receives it`() {
        val queue = mutableListOf<Runnable>()
        AppNoteSaveCoordinator.mainDispatcher = { queue.add(it) }
        val operation = AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString(), 12, 44, "hash")!!
        val dead = Any()
        var deadCalls = 0
        AppNoteSaveCoordinator.observePhotoSession(operation, dead) { deadCalls++ }
        AppNoteSaveCoordinator.completePhotoSession(operation, true, note, "assets/pic.jpg")
        AppNoteSaveCoordinator.finishPhotoWorker(operation)
        AppNoteSaveCoordinator.detachPhotoObserver(dead)
        val current = Any()
        var result: AppNoteSaveCoordinator.PhotoSessionResult? = null
        AppNoteSaveCoordinator.observePhotoSession(operation, current) { result = it }
        queue.toList().forEach { it.run() }
        assertEquals(0, deadCalls)
        assertTrue(result is AppNoteSaveCoordinator.PhotoSessionResult.Success)
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, dead))
        assertTrue(AppNoteSaveCoordinator.hasPhotoSession(path))
        assertTrue(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, current))
        assertEquals(12, operation.caretOffset)
        assertEquals(44, operation.scrollY)
    }

    @Test fun `duplicate rejected and stale worker cannot touch replacement`() {
        val operation = AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString())!!
        assertNull(AppNoteSaveCoordinator.startPhotoSession(path, "other"))
        val owner = Any()
        AppNoteSaveCoordinator.observePhotoSession(operation, owner) { }
        AppNoteSaveCoordinator.completePhotoSession(operation, false, null, null)
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        AppNoteSaveCoordinator.finishPhotoWorker(operation)
        assertTrue(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        val replacement = AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString())!!
        AppNoteSaveCoordinator.completePhotoSession(operation, true, note, "old.jpg")
        AppNoteSaveCoordinator.finishPhotoWorker(operation)
        assertFalse(AppNoteSaveCoordinator.transitionPhotoSession(operation,
            AppNoteSaveCoordinator.PhotoPhase.PROCESSING, AppNoteSaveCoordinator.PhotoPhase.COMMITTING))
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        assertEquals(replacement, AppNoteSaveCoordinator.photoOperation(path))
        assertFalse(AppNoteSaveCoordinator.isVaultMutationBarrierClear())
    }

    @Test fun `cancellation barrier survives rollback and unknown outcome cannot retry or acknowledge`() {
        val operation = AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString())!!
        val owner = Any()
        AppNoteSaveCoordinator.observePhotoSession(operation, owner) { }
        AppNoteSaveCoordinator.requestPhotoCancel(path)
        assertFalse(AppNoteSaveCoordinator.isVaultMutationBarrierClear())
        assertNull(AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString()))
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        AppNoteSaveCoordinator.finishPhotoWorker(operation)
        assertTrue(AppNoteSaveCoordinator.isVaultMutationBarrierClear())
        assertTrue(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        val retry = AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString())!!
        AppNoteSaveCoordinator.observePhotoSession(retry, owner) { }
        AppNoteSaveCoordinator.requirePhotoRecovery(retry, "unknown commit")
        AppNoteSaveCoordinator.finishPhotoWorker(retry)
        assertFalse(AppNoteSaveCoordinator.isVaultMutationBarrierClear())
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(retry, owner))
        assertNull(AppNoteSaveCoordinator.startPhotoSession(path, note.uri.toString()))
        assertEquals(AppNoteSaveCoordinator.PhotoCancelResult.TOO_LATE, AppNoteSaveCoordinator.requestPhotoCancel(path))
    }
}
