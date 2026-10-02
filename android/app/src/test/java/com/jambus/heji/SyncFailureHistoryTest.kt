package com.jambus.heji

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SyncFailureHistoryTest {
    private fun snapshot(provider: String, vault: String, status: SyncTaskStatus = SyncTaskStatus.FAILED) = SyncTaskSnapshot(
        provider, provider, "folder", status, 0, 0, SyncMessageCode.PARTIAL_FAILURE, 1L, 2L,
        errors = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, "笔记/文档.md", SyncErrorReason.NETWORK)),
        errorCount = 1, vaultId = SyncTaskVaultKey.fromVaultId(vault)
    )

    @Test fun `provider and Vault results remain separate and bounded`() {
        var history = emptyList<SyncTaskSnapshot>()
        history = SyncTaskHistory.upsert(history, snapshot("google_drive", "A"))
        history = SyncTaskHistory.upsert(history, snapshot("onedrive", "A", SyncTaskStatus.CANCELLED))
        history = SyncTaskHistory.upsert(history, snapshot("google_drive", "B", SyncTaskStatus.INTERRUPTED))
        assertEquals(3, history.size)
        assertEquals(SyncTaskStatus.FAILED, history.last().status)
        history = SyncTaskHistory.upsert(history, snapshot("google_drive", "A", SyncTaskStatus.SUCCEEDED))
        assertEquals(3, history.size)
        assertEquals(SyncTaskStatus.SUCCEEDED, history.first().status)
        for (i in 1..40) history = SyncTaskHistory.upsert(history, snapshot("google_drive", "vault-$i"))
        assertEquals(SyncTaskHistory.LIMIT, history.size)
        assertEquals(SyncTaskVaultKey.fromVaultId("vault-40"), history.first().vaultId)
    }

    @Test fun `running guard does not evict another provider completed result`() {
        val completed = listOf(snapshot("google_drive", "A"))
        assertEquals(completed, SyncTaskHistory.upsert(completed, snapshot("onedrive", "A", SyncTaskStatus.RUNNING)))
    }

    @Test fun `secret bearing exceptions never become error messages or paths`() {
        val secret = "https://cloud.example/private/doc.md?token=secret-account@example.com"
        val detail = SyncFailurePolicy.fromException(IOException(secret))
        assertEquals(SyncErrorReason.NETWORK, detail.reason)
        assertEquals("", detail.path)
        assertFalse(detail.toString().contains("secret"))
        assertEquals("", SyncTaskStateStore.safePath(secret))
        assertEquals("", SyncTaskStateStore.safePath("notes/a.md: $secret"))
        assertEquals("notes/文档.md", SyncFailurePolicy.safePath("notes/文档.md"))
        assertEquals("", SyncFailurePolicy.safePath("notes/../private.md"))
        assertEquals("", SyncFailurePolicy.safePath("content://private/tree"))
    }

    @Test fun `typed reasons and HTTP failures remain locale neutral`() {
        assertEquals(SyncErrorReason.AUTH_REQUIRED, SyncFailurePolicy.http(401))
        assertEquals(SyncErrorReason.PERMISSION_DENIED, SyncFailurePolicy.http(403))
        assertEquals(SyncErrorReason.REMOTE_CHANGED, SyncFailurePolicy.http(412))
        assertEquals(SyncErrorReason.RATE_LIMITED, SyncFailurePolicy.http(429))
        assertEquals(SyncErrorReason.CONTENT_MISMATCH,
            SyncFailurePolicy.fromException(SyncOperationException(SyncErrorReason.CONTENT_MISMATCH), "notes/a.md").reason)
        val result = SyncRunResult(0, 0, 0, 0, emptyList(), false,
            errorDetails = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, reason = SyncErrorReason.NETWORK)))
        assertFalse(result.isSuccessful)
    }
}
