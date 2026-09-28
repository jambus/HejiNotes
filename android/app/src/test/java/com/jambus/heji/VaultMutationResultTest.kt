package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VaultMutationResultTest {
    @Test
    fun `failure retains a specific provider diagnostic`() {
        val result = VaultMutationResult.Failure(
            VaultMutationFailureKind.TRASH_NAME_CONFLICT,
            "回收站已有同名项目"
        )

        assertEquals(VaultMutationFailureKind.TRASH_NAME_CONFLICT, result.kind)
        assertEquals("回收站已有同名项目", result.message)
    }

    @Test
    fun `trash collision remains distinct from current directory collision`() {
        assertNotEquals(VaultMutationFailureKind.NAME_CONFLICT, VaultMutationFailureKind.TRASH_NAME_CONFLICT)
    }

    @Test
    fun `success can represent provider mutation with name pending refresh`() {
        val result = VaultMutationResult.Success(null, "requested.md", null, DailyDirectoryChange.RESET_TO_ROOT)

        assertEquals(null, result.actualName)
        assertEquals(DailyDirectoryChange.RESET_TO_ROOT, result.dailyDirectoryChange)
    }

    @Test
    fun `precondition failure represents concurrent modification during mutation`() {
        val result = VaultMutationResult.Failure(
            VaultMutationFailureKind.PRECONDITION_FAILED,
            "File was modified locally"
        )
        assertEquals(VaultMutationFailureKind.PRECONDITION_FAILED, result.kind)
        assertNotEquals(VaultMutationFailureKind.MOVE_UNSUPPORTED, result.kind)
    }
}
