package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultTrashPolicyTest {
    @Test
    fun `note fallback keeps name when trash has no collision`() {
        assertEquals("meeting.md", VaultTrashPolicy.uniqueNoteName("meeting.md", emptyList(), "20260830-120000"))
    }

    @Test
    fun `note fallback uses unique name while folders never use this policy`() {
        assertEquals(
            "meeting-20260830-120000-2.md",
            VaultTrashPolicy.uniqueNoteName("meeting.md", listOf("MEETING.md", "meeting-20260830-120000.md"), "20260830-120000")
        )
    }

    @Test
    fun `uppercase Markdown extension becomes one lowercase fallback extension`() {
        assertEquals(
            "meeting-20260830-120000.md",
            VaultTrashPolicy.uniqueNoteName("meeting.MD", listOf("meeting.md"), "20260830-120000")
        )
    }

    @Test
    fun `image asset fallback preserves non-markdown extension`() {
        assertEquals(
            "photo-20260830-120000.png",
            VaultTrashPolicy.uniqueTrashName("photo.png", listOf("photo.png"), "20260830-120000")
        )
    }

}
