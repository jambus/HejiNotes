package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SyncCancellationCoordinatorTest {
    @Test
    fun `initial state is running and not cancelled`() {
        val coordinator = SyncCancellationCoordinator()
        assertEquals(SyncCancellationCoordinator.State.RUNNING, coordinator.currentState)
        assertFalse(coordinator.isCancelled)
        assertFalse(coordinator.streamSignal.get())
    }

    @Test
    fun `cancel transitions RUNNING to CANCELLED and wins over subsequent commit attempt`() {
        val coordinator = SyncCancellationCoordinator()
        assertTrue(coordinator.cancel())
        assertEquals(SyncCancellationCoordinator.State.CANCELLED, coordinator.currentState)
        assertTrue(coordinator.isCancelled)
        assertTrue(coordinator.streamSignal.get())

        // Subsequent commit attempt must fail and cannot commit
        assertFalse(coordinator.tryBeginCommit())
        assertEquals(SyncCancellationCoordinator.State.CANCELLED, coordinator.currentState)
    }

    @Test
    fun `tryBeginCommit transitions RUNNING to COMMITTING and wins over subsequent cancel attempt`() {
        val coordinator = SyncCancellationCoordinator()
        assertTrue(coordinator.tryBeginCommit())
        assertEquals(SyncCancellationCoordinator.State.COMMITTING, coordinator.currentState)

        // Cancellation attempt after commit begins must fail
        assertFalse(coordinator.cancel())
        assertEquals(SyncCancellationCoordinator.State.COMMITTING, coordinator.currentState)

        coordinator.markCommitted()
        assertEquals(SyncCancellationCoordinator.State.COMMITTED, coordinator.currentState)
        assertFalse(coordinator.cancel())
    }

    @Test
    fun `streamSignal cancellation causes tryBeginCommit to fail`() {
        val signal = AtomicBoolean(false)
        val coordinator = SyncCancellationCoordinator(signal)
        signal.set(true)
        assertTrue(coordinator.isCancelled)

        // tryBeginCommit detects the signal and transitions to CANCELLED
        assertFalse(coordinator.tryBeginCommit())
        assertEquals(SyncCancellationCoordinator.State.CANCELLED, coordinator.currentState)
    }

    @Test
    fun `abortCommit transitions COMMITTING back to RUNNING`() {
        val coordinator = SyncCancellationCoordinator()
        assertTrue(coordinator.tryBeginCommit())
        assertEquals(SyncCancellationCoordinator.State.COMMITTING, coordinator.currentState)

        coordinator.abortCommit()
        assertEquals(SyncCancellationCoordinator.State.RUNNING, coordinator.currentState)
        assertFalse(coordinator.isCancelled)

        // Now cancel can succeed
        assertTrue(coordinator.cancel())
        assertEquals(SyncCancellationCoordinator.State.CANCELLED, coordinator.currentState)
    }

    @Test
    fun `reset restores RUNNING state and streamSignal`() {
        val coordinator = SyncCancellationCoordinator()
        coordinator.cancel()
        assertTrue(coordinator.isCancelled)

        coordinator.reset()
        assertEquals(SyncCancellationCoordinator.State.RUNNING, coordinator.currentState)
        assertFalse(coordinator.isCancelled)
        assertFalse(coordinator.streamSignal.get())
    }

    @Test
    fun `concurrent cancel and commit attempts result in exactly one winner`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(200) {
                val coordinator = SyncCancellationCoordinator()
                val startGate = CountDownLatch(1)
                val doneGate = CountDownLatch(2)
                var cancelWon = false
                var commitWon = false

                executor.execute {
                    startGate.await()
                    cancelWon = coordinator.cancel()
                    doneGate.countDown()
                }
                executor.execute {
                    startGate.await()
                    commitWon = coordinator.tryBeginCommit()
                    doneGate.countDown()
                }

                startGate.countDown()
                doneGate.await()

                // Exactly one operation must win the CAS
                assertTrue(
                    "Expected exactly one winner (cancel=$cancelWon, commit=$commitWon)",
                    (cancelWon && !commitWon) || (!cancelWon && commitWon)
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
