package com.jambus.heji

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConfirmationPolicyTest {
    private fun snapshot(provider: String, status: SyncTaskStatus, startedAt: Long = 1L) =
        SyncTaskSnapshot(provider, provider, "Remote", status, 0, 0,
            SyncMessageCode.PREPARING, startedAt, 0L, vaultId = "vault-key")

    @Test fun `old result and absent state keep confirmation visible`() {
        val previous = snapshot("onedrive", SyncTaskStatus.SUCCEEDED)
        assertFalse(SyncConfirmationPolicy.shouldShowResult(previous, previous.copy()))
        assertFalse(SyncConfirmationPolicy.shouldShowResult(null, null))
        assertFalse(SyncConfirmationPolicy.shouldShowResult(previous, null))
    }

    @Test fun `both providers leave confirmation on accepted or immediately rejected start`() {
        for (provider in listOf("google_drive", "onedrive")) {
            val previous = snapshot(provider, SyncTaskStatus.SUCCEEDED)
            for (status in SyncTaskStatus.values()) {
                assertTrue(SyncConfirmationPolicy.shouldShowResult(previous, snapshot(provider, status, 2L)))
            }
        }
    }

    @Test fun `an existing running task routes to its status instead of another start`() {
        val running = snapshot("google_drive", SyncTaskStatus.RUNNING)
        assertTrue(SyncConfirmationPolicy.shouldShowResult(running, running))
    }
}
