package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAttachmentDeleteTest {
    @Test fun `transaction round trips every destructive identity field`() {
        val value = InlineAttachmentDeleteTransaction(
            "id", "项目/笔记.md", "<../assets/笔记/a (1).jpg>", "assets/笔记/a (1).jpg",
            "content://provider/a", "abc123", 42L, "old", "saved", InlineAttachmentDeleteTransaction.Stage.DELETE_INTENT
        )
        assertEquals(value, InlineAttachmentDeleteTransaction.parse(value.serialize()))
        assertNull(InlineAttachmentDeleteTransaction.parse("broken"))
    }

    @Test fun `prepared transaction round trips empty saved note hash`() {
        val value = InlineAttachmentDeleteTransaction(
            "id", "note.md", "assets/note/photo.jpg", "assets/note/photo.jpg",
            "content://provider/photo", "asset-hash", 42L, "old-note-hash", "",
            InlineAttachmentDeleteTransaction.Stage.PREPARED
        )
        assertEquals(value, InlineAttachmentDeleteTransaction.parse(value.serialize()))
    }

    @Test fun `exact references resolve across markdown forms and encoding`() {
        val target = "项目/assets/笔记/a (1).jpg"
        listOf(
            "![图](<assets/笔记/a (1).jpg>)",
            "[图]: assets/笔记/a%20%281%29.jpg",
            "![[assets/笔记/a (1).jpg|图]]",
            "<img src=\"assets/笔记/a (1).jpg\">"
        ).forEach { markdown ->
            assertEquals(
                InlineAttachmentReferencePolicy.Status.REFERENCED,
                InlineAttachmentReferencePolicy.referenceStatus(markdown, "项目", target)
            )
        }
    }

    @Test fun `external and different files do not match while suspicious raw text is ambiguous`() {
        val target = "assets/note/photo.jpg"
        assertEquals(InlineAttachmentReferencePolicy.Status.NONE,
            InlineAttachmentReferencePolicy.referenceStatus("![x](https://example.com/assets/note/photo.jpg)", "", target))
        assertEquals(InlineAttachmentReferencePolicy.Status.NONE,
            InlineAttachmentReferencePolicy.referenceStatus("![x](assets/note/other.jpg)", "", target))
        assertEquals(InlineAttachmentReferencePolicy.Status.AMBIGUOUS,
            InlineAttachmentReferencePolicy.referenceStatus("`assets/note/photo.jpg`", "", target))
    }

    @Test fun `vault escape and absolute paths are rejected`() {
        assertNull(InlineAttachmentReferencePolicy.resolve("", "../assets/note/photo.jpg"))
        assertNull(InlineAttachmentReferencePolicy.resolve("项目", "/assets/note/photo.jpg"))
        assertNull(InlineAttachmentReferencePolicy.resolve("项目", "https://example.com/photo.jpg"))
    }

    @Test fun `recovery never deletes from a prepared marker`() {
        assertFalse(InlineAttachmentDeleteRecoveryPolicy.mayDelete(InlineAttachmentDeleteTransaction.Stage.PREPARED))
        assertFalse(InlineAttachmentDeleteRecoveryPolicy.mayDelete(InlineAttachmentDeleteTransaction.Stage.NOTE_WRITE_INTENT))
        assertTrue(InlineAttachmentDeleteRecoveryPolicy.mayDelete(InlineAttachmentDeleteTransaction.Stage.NOTE_COMMITTED))
        assertTrue(InlineAttachmentDeleteRecoveryPolicy.mayDelete(InlineAttachmentDeleteTransaction.Stage.DELETE_INTENT))
    }

    @Test fun `strict scan includes trash and attachment roots`() {
        assertTrue(InlineAttachmentDeleteRecoveryPolicy.shouldScanRootDirectory(".trash"))
        assertTrue(InlineAttachmentDeleteRecoveryPolicy.shouldScanRootDirectory("assets"))
        assertTrue(InlineAttachmentDeleteRecoveryPolicy.shouldScanRootDirectory("attachments"))
        assertFalse(InlineAttachmentDeleteRecoveryPolicy.shouldScanRootDirectory(".markbook"))
        assertFalse(InlineAttachmentDeleteRecoveryPolicy.shouldScanRootDirectory(".obsidian"))
    }
}
