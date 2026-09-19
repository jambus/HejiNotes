package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionSaveCoordinatorTest {
    @Test
    fun olderSaveCannotConfirmNewerInput() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        val first = requireNotNull(coordinator.beginSave())
        coordinator.markEdited()

        assertEquals(RevisionSaveCoordinator.State.DIRTY, coordinator.state)
        assertEquals(RevisionSaveCoordinator.State.DIRTY, coordinator.complete(first, true))
        assertEquals(1L, coordinator.persistedRevision)
        assertEquals(2L, coordinator.revision)
        assertTrue(coordinator.hasUnsavedChanges)

        val second = requireNotNull(coordinator.beginSave())
        assertEquals(2L, second.revision)
        assertEquals(RevisionSaveCoordinator.State.SAVING, coordinator.state)
        assertEquals(RevisionSaveCoordinator.State.SAVED, coordinator.complete(second, true))
        assertFalse(coordinator.hasUnsavedChanges)
    }

    @Test
    fun failedSaveRemainsDirtyAndCanRetrySameRevision() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        val failed = requireNotNull(coordinator.beginSave())

        assertEquals(RevisionSaveCoordinator.State.FAILED, coordinator.complete(failed, false))
        assertTrue(coordinator.hasUnsavedChanges)

        val retry = requireNotNull(coordinator.beginSave())
        assertEquals(failed.revision, retry.revision)
        assertEquals(RevisionSaveCoordinator.State.SAVED, coordinator.complete(retry, true))
    }

    @Test
    fun backgroundSaveWithNoChangesDoesNothing() {
        val coordinator = RevisionSaveCoordinator()
        assertNull(coordinator.beginSave())
        assertEquals(RevisionSaveCoordinator.State.SAVED, coordinator.state)
    }

    @Test
    fun backgroundSaveCapturesCurrentDirtyRevision() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        coordinator.markEdited()

        val request = requireNotNull(coordinator.beginSave())

        assertEquals(2L, request.revision)
        assertEquals(RevisionSaveCoordinator.State.SAVING, coordinator.state)
        assertNull(coordinator.beginSave())
    }

    @Test
    fun editingAfterFailureClearsFailureButKeepsAllChangesDirty() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        val failed = requireNotNull(coordinator.beginSave())
        coordinator.complete(failed, false)

        coordinator.markEdited()

        assertEquals(RevisionSaveCoordinator.State.DIRTY, coordinator.state)
        assertEquals(2L, requireNotNull(coordinator.beginSave()).revision)
    }

    @Test
    fun lateCompletionIsIgnored() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        val request = requireNotNull(coordinator.beginSave())
        coordinator.complete(request, true)
        coordinator.markEdited()

        assertEquals(RevisionSaveCoordinator.State.DIRTY, coordinator.complete(request, true))
        assertEquals(1L, coordinator.persistedRevision)
        assertEquals(2L, coordinator.revision)
    }

    @Test
    fun resetClearsPendingAndFailureState() {
        val coordinator = RevisionSaveCoordinator()
        coordinator.markEdited()
        val request = requireNotNull(coordinator.beginSave())
        coordinator.complete(request, false)

        coordinator.reset()

        assertEquals(RevisionSaveCoordinator.State.SAVED, coordinator.state)
        assertFalse(coordinator.hasInFlightSave)
        assertFalse(coordinator.hasUnsavedChanges)
    }

    @Test
    fun missingDirtySnapshotRequiresExplicitRecoveryError() {
        assertTrue(EditorRecoveryPolicy.requiresExplicitError(
            restoredNoteMatches = true,
            dirtyRevision = 7L,
            savedRevision = 6L,
            processRevision = 6L,
            operationExpired = false
        ))
        assertFalse(EditorRecoveryPolicy.requiresExplicitError(
            restoredNoteMatches = true,
            dirtyRevision = 7L,
            savedRevision = 6L,
            processRevision = 7L,
            operationExpired = false
        ))
        assertTrue(EditorRecoveryPolicy.requiresExplicitError(
            restoredNoteMatches = false,
            dirtyRevision = 0L,
            savedRevision = 0L,
            processRevision = 0L,
            operationExpired = true
        ))
    }
}
