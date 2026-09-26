package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncTaskSnapshotTest {
    @Test fun `running task stores a locale-neutral progress state`() {
        val snapshot = SyncTaskSnapshot(
            "test", "测试提供方", "目标", SyncTaskStatus.RUNNING, 2, 5,
            SyncMessageCode.PROGRESS, 1L, 0L
        )
        assertEquals(SyncMessageCode.PROGRESS, snapshot.messageCode)
    }

    @Test fun `interrupted task is never stored as completed`() {
        val snapshot = SyncTaskSnapshot(
            "test", "测试提供方", "目标", SyncTaskStatus.INTERRUPTED, 0, 0,
            SyncMessageCode.INTERRUPTED, 1L, 2L
        )
        assertEquals(SyncTaskStatus.INTERRUPTED, snapshot.status)
    }

    @Test fun `task Vault key is one way and does not contain the SAF URI`() {
        val uri = "content://com.example.documents/tree/private%3AVault"
        val key = SyncTaskVaultKey.fromVaultId(uri)
        assertFalse(key.contains("content"))
        assertFalse(key.contains("Vault"))
        assertEquals(key, SyncTaskVaultKey.fromVaultId(uri))
    }

    @Test fun `safePath preserves Unicode and spaces in Vault relative paths`() {
        val error = "00 MOC/Engineering Center 组织调整 MOC.md: OneDrive request failed (412)"
        assertEquals("00 MOC/Engineering Center 组织调整 MOC.md", SyncTaskStateStore.safePath(error))
    }

    @Test fun `safePath rejects path traversal and control characters`() {
        assertEquals("", SyncTaskStateStore.safePath("../secret.md: error"))
        assertEquals("", SyncTaskStateStore.safePath("/absolute/path.md: error"))
    }
}
