package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class OneDriveCopyRetryTest {

    @Test
    fun `retry reuses copy when upload succeeded but verification failed on first attempt`() {
        var remoteItem: DriveItem? = null
        var uploadCalls = 0
        var injectVerificationFault = true
        val expectedDigest = "md5-copy-content"

        val findTarget = { remoteItem }
        val computeDigest: (DriveItem) -> String = { item ->
            if (injectVerificationFault && uploadCalls == 1) {
                throw DriveApiException("Network connection lost during verification")
            }
            expectedDigest
        }
        val performUpload = {
            uploadCalls++
            remoteItem = DriveItem("item-copied-1", "note (OneDrive conflict 20260901).md", "text/markdown")
        }

        // Attempt 1: Upload succeeds on remote, but verification fails with network exception
        try {
            OneDriveCopyPolicy.executeCopy(
                sourceDigest = expectedDigest,
                findTarget = findTarget,
                computeDigest = computeDigest,
                performUpload = performUpload
            )
            fail("Attempt 1 should have failed due to verification fault")
        } catch (ex: DriveApiException) {
            assertEquals("Network connection lost during verification", ex.message)
        }

        // Verify state after attempt 1: item was uploaded once, but copy failed
        assertEquals(1, uploadCalls)
        assertNotNull(remoteItem)

        // Attempt 2 (Retry): Target now exists remotely; executeCopy reuses it without re-uploading
        injectVerificationFault = false
        val result = OneDriveCopyPolicy.executeCopy(
            sourceDigest = expectedDigest,
            findTarget = findTarget,
            computeDigest = computeDigest,
            performUpload = performUpload
        )

        assertEquals("item-copied-1", result.id)
        assertEquals(1, uploadCalls) // No duplicate upload performed on retry
    }

    @Test
    fun `recovers via fallback when upload creates item but throws DriveApiException`() {
        var remoteItem: DriveItem? = null
        var uploadCalls = 0
        val expectedDigest = "md5-fallback-content"

        val result = OneDriveCopyPolicy.executeCopy(
            sourceDigest = expectedDigest,
            findTarget = { remoteItem },
            computeDigest = { expectedDigest },
            performUpload = {
                uploadCalls++
                remoteItem = DriveItem("item-fallback-2", "backup.md", "text/markdown")
                throw DriveApiException("412 Precondition Failed - item already exists")
            }
        )

        assertEquals(1, uploadCalls)
        assertEquals("item-fallback-2", result.id)
    }

    @Test
    fun `rejects copy when target already exists with different content`() {
        val existing = DriveItem("existing-diff", "conflict.md", "text/markdown")
        var uploadCalls = 0

        try {
            OneDriveCopyPolicy.executeCopy(
                sourceDigest = "digest-source",
                findTarget = { existing },
                computeDigest = { "digest-target-different" },
                performUpload = { uploadCalls++ }
            )
            fail("Should fail due to content difference")
        } catch (ex: DriveApiException) {
            assertEquals("OneDrive copy target already exists with different content", ex.message)
        }

        assertEquals(0, uploadCalls)
    }

    @Test
    fun `executes normal copy workflow successfully when target does not exist`() {
        var remoteItem: DriveItem? = null
        var uploadCalls = 0
        val expectedDigest = "digest-normal"

        val result = OneDriveCopyPolicy.executeCopy(
            sourceDigest = expectedDigest,
            findTarget = { remoteItem },
            computeDigest = { expectedDigest },
            performUpload = {
                uploadCalls++
                remoteItem = DriveItem("item-normal", "copied.md", "text/markdown")
            }
        )

        assertEquals(1, uploadCalls)
        assertEquals("item-normal", result.id)
    }
}
