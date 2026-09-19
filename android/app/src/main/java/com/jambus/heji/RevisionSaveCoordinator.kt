package com.jambus.heji

/**
 * Tracks which editor revision is visible, being written, and durably stored.
 *
 * The coordinator deliberately contains no Android or filesystem dependencies so delayed and
 * out-of-order completion behaviour can be covered by local unit tests.
 */
class RevisionSaveCoordinator {
    data class Request(val revision: Long)

    enum class State {
        SAVED,
        DIRTY,
        SAVING,
        FAILED
    }

    private var currentRevision = 0L
    private var savedRevision = 0L
    private var inFlightRevision: Long? = null
    private var lastAttemptFailed = false

    val revision: Long
        get() = currentRevision

    val persistedRevision: Long
        get() = savedRevision

    val hasInFlightSave: Boolean
        get() = inFlightRevision != null

    val hasUnsavedChanges: Boolean
        get() = currentRevision > savedRevision

    val state: State
        get() = when {
            lastAttemptFailed -> State.FAILED
            currentRevision <= savedRevision -> State.SAVED
            inFlightRevision == currentRevision -> State.SAVING
            else -> State.DIRTY
        }

    fun reset(initialRevision: Long = 0L) {
        currentRevision = initialRevision
        savedRevision = initialRevision
        inFlightRevision = null
        lastAttemptFailed = false
    }

    fun markEdited(): Long {
        currentRevision += 1L
        lastAttemptFailed = false
        return currentRevision
    }

    fun beginSave(): Request? {
        if (inFlightRevision != null || !hasUnsavedChanges) return null
        return Request(currentRevision).also {
            inFlightRevision = it.revision
            lastAttemptFailed = false
        }
    }

    /**
     * Completes only the currently active request. A late callback from an abandoned request is
     * ignored and can never move the visible state to SAVED.
     */
    fun complete(request: Request, success: Boolean): State {
        if (inFlightRevision != request.revision) return state
        inFlightRevision = null
        if (success) {
            savedRevision = maxOf(savedRevision, request.revision)
            lastAttemptFailed = false
        } else {
            lastAttemptFailed = true
        }
        return state
    }

    fun restore(current: Long, saved: Long, failed: Boolean = false) {
        currentRevision = current
        savedRevision = saved
        inFlightRevision = null
        lastAttemptFailed = failed
    }
}

object EditorRecoveryPolicy {
    fun requiresExplicitError(
        restoredNoteMatches: Boolean,
        dirtyRevision: Long,
        savedRevision: Long,
        processRevision: Long,
        operationExpired: Boolean
    ): Boolean = operationExpired ||
        (restoredNoteMatches && dirtyRevision > maxOf(savedRevision, processRevision))
}
