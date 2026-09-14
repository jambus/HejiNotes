package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorReleaseCoordinatorTest {

    private class FakeEditor(val id: String)

    @Test
    fun releaseWhenIdleDestroysImmediately() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val editor = FakeEditor("editor-1")

        var destroyed = false
        coordinator.release(editor) {
            destroyed = true
        }

        assertTrue("Editor should be destroyed immediately when idle", destroyed)
        assertFalse(coordinator.hasPendingDestructions)
        assertFalse(coordinator.isPendingDestroy(editor))
    }

    @Test
    fun releaseWhileSerializingDefersDestructionUntilCompleted() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val editor = FakeEditor("editor-1")

        coordinator.onSerializationStarted(editor)
        assertTrue(coordinator.isSerializing(editor))

        var destroyedImmediately = false
        coordinator.release(editor) {
            destroyedImmediately = true
        }

        assertFalse("Editor must NOT be destroyed immediately while serialization is in flight", destroyedImmediately)
        assertTrue(coordinator.hasPendingDestructions)
        assertTrue(coordinator.isPendingDestroy(editor))

        var destroyedDeferred = false
        coordinator.onSerializationCompleted(editor) {
            destroyedDeferred = true
        }

        assertTrue("Editor must be destroyed once serialization completes", destroyedDeferred)
        assertFalse(coordinator.hasPendingDestructions)
        assertFalse(coordinator.isPendingDestroy(editor))
        assertFalse(coordinator.isSerializing(editor))
    }

    @Test
    fun noArbitraryTimeoutCanTruncateInFlightSave() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val editor = FakeEditor("editor-slow-save")

        coordinator.onSerializationStarted(editor)

        var destroyed = false
        coordinator.release(editor) {
            destroyed = true
        }

        assertFalse(destroyed)
        assertTrue(coordinator.isPendingDestroy(editor))

        // Simulate passage of arbitrary time (e.g. 3s, 5s, 10s) without invoking completion:
        // No timer or arbitrary timeout should trigger destruction.
        assertFalse("Destruction must not be triggered without serialization completing", destroyed)
        assertTrue("Editor must remain pending until serialization completes", coordinator.isPendingDestroy(editor))

        // When serialization finally finishes (e.g. large note serialization finishes):
        var destroyedOnCompletion = false
        coordinator.onSerializationCompleted(editor) {
            destroyedOnCompletion = true
        }

        assertTrue("Destruction must occur reliably upon serialization completion", destroyedOnCompletion)
        assertFalse(coordinator.hasPendingDestructions)
    }

    @Test
    fun serializationCompletingBeforeReleaseDestroysImmediatelyOnRelease() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val editor = FakeEditor("editor-1")

        coordinator.onSerializationStarted(editor)

        var destroyedDeferred = false
        coordinator.onSerializationCompleted(editor) {
            destroyedDeferred = true
        }

        assertFalse("No deferred destruction if not released yet", destroyedDeferred)
        assertFalse(coordinator.isSerializing(editor))

        var destroyedImmediate = false
        coordinator.release(editor) {
            destroyedImmediate = true
        }

        assertTrue("Subsequent release must destroy immediately", destroyedImmediate)
        assertFalse(coordinator.hasPendingDestructions)
    }

    @Test
    fun serializationFailureStillCleansUpAndDestroysEditor() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val editor = FakeEditor("editor-failed")

        coordinator.onSerializationStarted(editor)
        coordinator.release(editor) {}

        assertTrue(coordinator.isPendingDestroy(editor))

        // Simulate exception or failure during serialization, with finally block invoking onSerializationCompleted:
        var destroyedInFinally = false
        coordinator.onSerializationCompleted(editor) {
            destroyedInFinally = true
        }

        assertTrue("Editor must be cleaned up and destroyed even on serialization failure", destroyedInFinally)
        assertFalse(coordinator.hasPendingDestructions)
    }

    @Test
    fun multipleEditorsTrackDestructionIndependently() {
        val coordinator = EditorReleaseCoordinator<FakeEditor>()
        val oldEditor = FakeEditor("old-editor-recreated")
        val newEditor = FakeEditor("new-editor-active")

        coordinator.onSerializationStarted(oldEditor)
        var oldDestroyedImmediate = false
        coordinator.release(oldEditor) { oldDestroyedImmediate = true }

        assertFalse(oldDestroyedImmediate)
        assertTrue(coordinator.isPendingDestroy(oldEditor))

        // New editor created and serializing on new configuration
        coordinator.onSerializationStarted(newEditor)
        assertTrue(coordinator.isSerializing(newEditor))
        assertFalse(coordinator.isPendingDestroy(newEditor))

        // Old editor finishes serialization
        val destroyedList = mutableListOf<FakeEditor>()
        coordinator.onSerializationCompleted(oldEditor) { destroyedList.add(it) }

        assertEquals(listOf(oldEditor), destroyedList)
        assertFalse(coordinator.isPendingDestroy(oldEditor))
        assertTrue(coordinator.isSerializing(newEditor))
    }
}
