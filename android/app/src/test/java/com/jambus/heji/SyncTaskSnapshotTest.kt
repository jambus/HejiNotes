package com.jambus.heji

import org.junit.Assert.assertEquals
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
}
