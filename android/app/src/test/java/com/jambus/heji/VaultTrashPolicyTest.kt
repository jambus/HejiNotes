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

    @Test
    fun `isCopyVerified returns true when size and SHA-256 match case-insensitively`() {
        assertEquals(true, VaultTrashCopyPolicy.isCopyVerified(1234L, "A1B2C3D4", 1234L, "a1b2c3d4"))
    }

    @Test
    fun `isCopyVerified returns false when size or SHA-256 mismatch`() {
        assertEquals(false, VaultTrashCopyPolicy.isCopyVerified(1234L, "a1b2c3d4", 1235L, "a1b2c3d4"))
        assertEquals(false, VaultTrashCopyPolicy.isCopyVerified(1234L, "a1b2c3d4", 1234L, "different"))
    }

    @Test
    fun `TrashCopyTransaction serialize and parse roundtrip preserves all fields`() {
        val original = TrashCopyTransaction(
            id = "tx-123",
            sourcePath = "sub/note.md",
            sourceUri = "content://vault/sub/note.md",
            sourceSha256 = "hash123",
            sourceSize = 512L,
            targetTrashName = "note-20260928-120000.md",
            targetUri = "content://vault/.trash/note-20260928-120000.md",
            stage = TrashCopyTransaction.Stage.COPIED_VERIFIED
        )
        val serialized = original.serialize()
        val parsed = TrashCopyTransaction.parse(serialized)
        assertEquals(original, parsed)
    }

    @Test
    fun `TrashCopyTransaction roundtrip with null targetUri`() {
        val original = TrashCopyTransaction(
            id = "tx-456",
            sourcePath = "note.md",
            sourceUri = "content://vault/note.md",
            sourceSha256 = "hash456",
            sourceSize = 256L,
            targetTrashName = "note.md",
            targetUri = null,
            stage = TrashCopyTransaction.Stage.PREPARED
        )
        val serialized = original.serialize()
        val parsed = TrashCopyTransaction.parse(serialized)
        assertEquals(original, parsed)
    }

    @Test
    fun `TrashCopyTransaction roundtrip with TARGET_CREATED stage and targetUri`() {
        val original = TrashCopyTransaction(
            id = "tx-789",
            sourcePath = "folder/doc.md",
            sourceUri = "content://vault/folder/doc.md",
            sourceSha256 = "hash789",
            sourceSize = 1024L,
            targetTrashName = "doc-trash.md",
            targetUri = "content://vault/.trash/doc-trash.md",
            stage = TrashCopyTransaction.Stage.TARGET_CREATED
        )
        val serialized = original.serialize()
        val parsed = TrashCopyTransaction.parse(serialized)
        assertEquals(original, parsed)
    }

    @Test
    fun `TrashCopyTransaction parse returns null on invalid input`() {
        assertEquals(null, TrashCopyTransaction.parse("not a valid base64 payload"))
        assertEquals(null, TrashCopyTransaction.parse(""))
    }

    @Test
    fun `VaultStreamUtils computeDigest calculates correct size and SHA-256`() {
        val content = "Hello streaming world! ".repeat(1000).toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val expectedSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val stream = java.io.ByteArrayInputStream(content)
        val digest = VaultStreamUtils.computeDigest(stream, bufferSize = 128)
        assertEquals(content.size.toLong(), digest.size)
        assertEquals(expectedSha256, digest.sha256)
    }

    @Test
    fun `VaultStreamUtils copyAndDigest streams bytes and calculates SHA-256`() {
        val content = "Testing copy and digest streaming ".repeat(500).toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val expectedSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val input = java.io.ByteArrayInputStream(content)
        val output = java.io.ByteArrayOutputStream()
        val digest = VaultStreamUtils.copyAndDigest(input, output, bufferSize = 64)
        assertEquals(content.size.toLong(), digest.size)
        assertEquals(expectedSha256, digest.sha256)
        org.junit.Assert.assertArrayEquals(content, output.toByteArray())
    }

    @Test
    fun `VaultSaveLock provides mutual exclusion between concurrent blocks`() {
        val executionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(2)

        val t1 = Thread {
            VaultSaveLock.withLock {
                executionOrder += "t1-start"
                latch.countDown()
                Thread.sleep(100)
                executionOrder += "t1-end"
            }
            doneLatch.countDown()
        }

        val t2 = Thread {
            latch.await()
            VaultSaveLock.withLock {
                executionOrder += "t2-start"
                executionOrder += "t2-end"
            }
            doneLatch.countDown()
        }

        t1.start()
        t2.start()
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(listOf("t1-start", "t1-end", "t2-start", "t2-end"), executionOrder)
    }

    @Test
    fun `recovery policy in PREPARED stage cleans target and marker`() {
        assertEquals(
            TrashRecoveryAction.DELETE_TARGET_AND_MARKER,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.PREPARED, targetVerified = false, sourceExists = true, sourceMatches = true)
        )
    }

    @Test
    fun `recovery policy in TARGET_CREATED stage cleans target and marker`() {
        assertEquals(
            TrashRecoveryAction.DELETE_TARGET_AND_MARKER,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.TARGET_CREATED, targetVerified = false, sourceExists = true, sourceMatches = true)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage protects source if target is unverified or corrupted and source is intact`() {
        assertEquals(
            TrashRecoveryAction.DELETE_TARGET_AND_MARKER,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceExists = true, sourceMatches = true)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage retains transaction if target is unverified and source is missing`() {
        assertEquals(
            TrashRecoveryAction.RETAIN_TRANSACTION,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceExists = false, sourceMatches = false)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage retains transaction if target is unverified and source does not match`() {
        assertEquals(
            TrashRecoveryAction.RETAIN_TRANSACTION,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceExists = true, sourceMatches = false)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage protects modified source`() {
        assertEquals(
            TrashRecoveryAction.DELETE_TARGET_AND_MARKER,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = true, sourceExists = true, sourceMatches = false)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage finalizes source delete when target is verified and source is unmodified`() {
        assertEquals(
            TrashRecoveryAction.FINALIZE_SOURCE_DELETE,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = true, sourceExists = true, sourceMatches = true)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage cleans marker only if source was already deleted`() {
        assertEquals(
            TrashRecoveryAction.DELETE_MARKER_ONLY,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = true, sourceExists = false, sourceMatches = false)
        )
    }

    @Test
    fun `recovery policy in SOURCE_DELETED stage cleans marker only`() {
        assertEquals(
            TrashRecoveryAction.DELETE_MARKER_ONLY,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.SOURCE_DELETED, targetVerified = true, sourceExists = false, sourceMatches = false)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage retains transaction if source state is UNKNOWN even when target is verified`() {
        assertEquals(
            TrashRecoveryAction.RETAIN_TRANSACTION,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = true, sourceState = SourceState.UNKNOWN)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage retains transaction if source state is UNKNOWN and target is unverified`() {
        assertEquals(
            TrashRecoveryAction.RETAIN_TRANSACTION,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceState = SourceState.UNKNOWN)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage with SourceState CONFIRMED_INTACT and unverified target cleans target and marker`() {
        assertEquals(
            TrashRecoveryAction.DELETE_TARGET_AND_MARKER,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceState = SourceState.CONFIRMED_INTACT)
        )
    }

    @Test
    fun `recovery policy in COPIED_VERIFIED stage with SourceState CONFIRMED_DELETED and unverified target retains transaction`() {
        assertEquals(
            TrashRecoveryAction.RETAIN_TRANSACTION,
            VaultTrashRecoveryPolicy.decide(TrashCopyTransaction.Stage.COPIED_VERIFIED, targetVerified = false, sourceState = SourceState.CONFIRMED_DELETED)
        )
    }
}
