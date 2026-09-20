package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultBrowserPolicyTest {
    @Test
    fun `internal metadata stays hidden while attachment paths remain visible`() {
        assertTrue(VaultBrowserPolicy.isHidden(".markbook/moves/change.json"))
        assertTrue(VaultBrowserPolicy.isHidden("Projects/.trash/note.md"))
        assertTrue(VaultBrowserPolicy.isHidden("assets/note/.markbook-copy.tmp"))
        assertFalse(VaultBrowserPolicy.isHidden("assets/note/photo.jpg"))
        assertFalse(VaultBrowserPolicy.isHidden("attachments/clip.mp4"))
    }

    @Test
    fun `attachment paths are read only at every depth`() {
        assertTrue(VaultBrowserPolicy.isReadOnlyAttachmentPath("assets"))
        assertTrue(VaultBrowserPolicy.isReadOnlyAttachmentPath("Projects/assets/note/photo.jpg"))
        assertTrue(VaultBrowserPolicy.isReadOnlyAttachmentPath("attachments/file.pdf"))
        assertFalse(VaultBrowserPolicy.isReadOnlyAttachmentPath("Projects/2026"))
    }

    @Test
    fun `files are classified by mime or extension`() {
        assertEquals(VaultBrowserPolicy.FileKind.MARKDOWN, VaultBrowserPolicy.fileKind("note.MD", null))
        assertEquals(VaultBrowserPolicy.FileKind.IMAGE, VaultBrowserPolicy.fileKind("capture", "image/jpeg"))
        assertEquals(VaultBrowserPolicy.FileKind.IMAGE, VaultBrowserPolicy.fileKind("scan.HEIC", null))
        assertEquals(VaultBrowserPolicy.FileKind.VIDEO, VaultBrowserPolicy.fileKind("clip.MP4", null))
        assertEquals(VaultBrowserPolicy.FileKind.VIDEO, VaultBrowserPolicy.fileKind("capture", "video/3gpp"))
        assertEquals(VaultBrowserPolicy.FileKind.OTHER, VaultBrowserPolicy.fileKind("manual.pdf", "application/pdf"))
    }

    @Test
    fun `only image and video files inside attachment trees are directly deletable`() {
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachment("assets/note/photo.jpg", "photo.jpg", "image/jpeg", false))
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachment("Projects/attachments/clip.mp4", "clip.mp4", "video/mp4", false))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachment("Projects/photo.jpg", "photo.jpg", "image/jpeg", false))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachment("assets/note/manual.pdf", "manual.pdf", "application/pdf", false))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachment("assets/note/folder", "folder", null, true))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachment(".markbook/assets/photo.jpg", "photo.jpg", "image/jpeg", false))
    }
}
