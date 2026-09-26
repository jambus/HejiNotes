package com.jambus.heji

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates atomic lifecycle between sync cancellation and baseline commit.
 * Uses CAS to guarantee mutual exclusion: either cancellation aborts before commit,
 * or commit proceeds and prevents cancellation from revoking an already-committed baseline.
 */
class SyncCancellationCoordinator(
    private val streamCancellation: AtomicBoolean = AtomicBoolean(false)
) {
    enum class State {
        RUNNING,
        CANCELLED,
        COMMITTING,
        COMMITTED
    }

    private val state = AtomicReference(State.RUNNING)

    val isCancelled: Boolean
        get() = state.get() == State.CANCELLED || streamCancellation.get()

    val streamSignal: AtomicBoolean
        get() = streamCancellation

    val currentState: State
        get() = state.get()

    /**
     * Attempts to cancel the sync run.
     * Returns true if cancellation won via CAS (transitioning from RUNNING to CANCELLED).
     * Returns false if commit has already won (state is COMMITTING or COMMITTED) or already cancelled.
     */
    fun cancel(): Boolean {
        streamCancellation.set(true)
        while (true) {
            val current = state.get()
            if (current == State.COMMITTING || current == State.COMMITTED) return false
            if (current == State.CANCELLED) return true
            if (state.compareAndSet(State.RUNNING, State.CANCELLED)) return true
        }
    }

    /**
     * Atomically attempts to begin committing the sync baseline.
     * Returns true if commit won via CAS (transitioning from RUNNING to COMMITTING).
     * Returns false if cancellation won (state is CANCELLED) or already committing.
     */
    fun tryBeginCommit(): Boolean {
        if (streamCancellation.get()) {
            state.compareAndSet(State.RUNNING, State.CANCELLED)
            return false
        }
        return state.compareAndSet(State.RUNNING, State.COMMITTING)
    }

    /**
     * Marks the baseline as successfully committed.
     */
    fun markCommitted() {
        state.set(State.COMMITTED)
    }

    /**
     * Aborts an in-flight commit attempt if baseline persistence failed.
     */
    fun abortCommit() {
        state.compareAndSet(State.COMMITTING, State.RUNNING)
    }

    /**
     * Resets state for a new sync run.
     */
    fun reset() {
        streamCancellation.set(false)
        state.set(State.RUNNING)
    }
}
