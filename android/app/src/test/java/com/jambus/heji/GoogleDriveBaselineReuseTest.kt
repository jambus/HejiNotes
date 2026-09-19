package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GoogleDriveBaselineReuseTest {

    @Test
    fun `always invokes fallback computation for attachments to prevent missed syncs`() {
        val cached = DriveBaselineFile(
            path = "notes/large_attachment.jpg",
            localSha256 = "sha256-cached-val",
            remoteId = "drive-id-123",
            remoteMd5 = "remote-md5-val",
            remoteVersion = 42L,
            localMd5 = "local-md5-val",
            localLastModified = 1700000000000L,
            localSize = 1048576L
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(cached, 1700000000000L, 1048576L) {
            fallbackInvoked.set(true)
            LocalFileDigests("fallback-md5", "fallback-sha256")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked for attachments to guarantee full content sync", fallbackInvoked.get())
        assertEquals("fallback-md5", digest!!.md5)
        assertEquals("fallback-sha256", digest.sha256)
    }

    @Test
    fun `invokes fallback when localLastModified is newer`() {
        val cached = DriveBaselineFile(
            path = "notes/document.md",
            localSha256 = "old-sha",
            remoteId = "drive-id-456",
            remoteMd5 = "old-md5",
            remoteVersion = 1L,
            localMd5 = "old-local-md5",
            localLastModified = 1700000000000L
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(cached, 1700000009999L) {
            fallbackInvoked.set(true)
            LocalFileDigests("new-md5", "new-sha")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked when file is modified", fallbackInvoked.get())
        assertEquals("new-md5", digest!!.md5)
        assertEquals("new-sha", digest.sha256)
    }

    @Test
    fun `invokes fallback when file is not in baseline`() {
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(null, 1700000000000L) {
            fallbackInvoked.set(true)
            LocalFileDigests("fresh-md5", "fresh-sha")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked for new files", fallbackInvoked.get())
        assertEquals("fresh-md5", digest!!.md5)
        assertEquals("fresh-sha", digest.sha256)
    }

    @Test
    fun `invokes fallback when cached localMd5 is missing`() {
        val legacyCached = DriveBaselineFile(
            path = "notes/legacy.md",
            localSha256 = "sha-val",
            remoteId = "drive-id-789",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = null,
            localLastModified = 1700000000000L
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(legacyCached, 1700000000000L) {
            fallbackInvoked.set(true)
            LocalFileDigests("computed-md5", "computed-sha")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked if cached localMd5 is null", fallbackInvoked.get())
        assertEquals("computed-md5", digest!!.md5)
    }

    @Test
    fun `invokes fallback when document localLastModified is null`() {
        val cached = DriveBaselineFile(
            path = "notes/unknown_time.md",
            localSha256 = "sha-val",
            remoteId = "drive-id-000",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = "md5-val",
            localLastModified = 1700000000000L
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(cached, null) {
            fallbackInvoked.set(true)
            LocalFileDigests("computed-md5", "computed-sha")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked if document timestamp is unknown", fallbackInvoked.get())
    }

    @Test
    fun `invokes fallback when cached localLastModified is null`() {
        val cached = DriveBaselineFile(
            path = "notes/no_baseline_time.md",
            localSha256 = "sha-val",
            remoteId = "drive-id-000",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = "md5-val",
            localLastModified = null
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(cached, 1700000000000L) {
            fallbackInvoked.set(true)
            LocalFileDigests("computed-md5", "computed-sha")
        }

        assertNotNull(digest)
        assertTrue("Fallback computation must be invoked if baseline timestamp is unknown", fallbackInvoked.get())
    }

    @Test
    fun `canReuseBaselineDigest always returns false to prevent false positives on Android storage`() {
        val cached = DriveBaselineFile(
            path = "assets/note_1/size_test.jpg",
            localSha256 = "sha256-val",
            remoteId = "drive-id-size",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = "local-md5",
            localLastModified = 1700000000000L,
            localSize = 4096L
        )
        assertFalse(DriveSyncDigestPolicy.canReuseBaselineDigest(cached, 1700000000000L, 4096L))
        assertFalse(DriveSyncDigestPolicy.canReuseBaselineDigest("assets/note_1/size_test.jpg", cached, 1700000000000L, 4096L))
    }

    @Test
    fun `never reuses cached digest for markdown notes even if time and size match`() {
        val cached = DriveBaselineFile(
            path = "notes/size_test.md",
            localSha256 = "sha256-val",
            remoteId = "drive-id-size",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = "local-md5",
            localLastModified = 1700000000000L,
            localSize = 4096L
        )
        val fallbackInvoked = AtomicBoolean(false)

        val digest = DriveSyncDigestPolicy.resolveDigest(cached, 1700000000000L, 4096L) {
            fallbackInvoked.set(true)
            LocalFileDigests("recomputed-note-md5", "recomputed-note-sha256")
        }

        assertNotNull(digest)
        assertTrue("Markdown notes must always invoke fallback computation to prevent missing edits", fallbackInvoked.get())
        assertEquals("recomputed-note-md5", digest!!.md5)
        assertEquals("recomputed-note-sha256", digest.sha256)
    }

    @Test
    fun `invokes fallback when timestamp matches but size differs`() {
        val cached = DriveBaselineFile(
            path = "assets/note_1/size_mismatch.jpg",
            localSha256 = "sha256-val",
            remoteId = "drive-id-size",
            remoteMd5 = "remote-md5",
            remoteVersion = 1L,
            localMd5 = "local-md5",
            localLastModified = 1700000000000L,
            localSize = 4096L
        )
        val fallbackInvoked = AtomicBoolean(false)

        // Same timestamp, but size increased to 4150 bytes
        val digest = DriveSyncDigestPolicy.resolveDigest(cached, 1700000000000L, 4150L) {
            fallbackInvoked.set(true)
            LocalFileDigests("recomputed-md5", "recomputed-sha256")
        }

        assertNotNull(digest)
        assertTrue("Fallback must be invoked when size differs even if timestamp matches", fallbackInvoked.get())
        assertEquals("recomputed-md5", digest!!.md5)
    }
}
