package com.jambus.heji

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhotoRollbackPolicyTest {
    private val original = PhotoRollbackFile("o.jpg", "original-id", "original-hash")
    private val corrected = PhotoRollbackFile("c.jpg", "corrected-id", "corrected-hash")
    private val marker = PhotoRollbackFile(".markbook.txn", "marker-id", "marker-hash")
    private val identity = PhotoRollbackIdentity("vault", "directory", listOf(original, corrected), marker)
    private class Port(files: List<PhotoRollbackFile>) : PhotoRollbackPort {
        val entries = files.map { PhotoRollbackPort.Entry(it.name, it.identity) }.toMutableList()
        val hashes = files.associate { it.identity to it.sha256 }.toMutableMap()
        val deleted = mutableListOf<String>()
        var failedDelete: String? = null
        var throwDelete = false
        var nullList = false
        var throwList = false
        var keepDeleted = false
        override fun list(): List<PhotoRollbackPort.Entry>? {
            if (throwList) error("read failed")
            return if (nullList) null else entries.toList()
        }
        override fun sha256(entry: PhotoRollbackPort.Entry) = hashes[entry.identity]
        override fun delete(entry: PhotoRollbackPort.Entry): Boolean {
            if (throwDelete) error("delete uncertain")
            if (entry.identity == failedDelete) return false
            deleted.add(entry.identity)
            if (!keepDeleted) entries.remove(entry)
            return true
        }
    }

    @Before fun reset() { AppNoteSaveCoordinator.resetForTests() }

    @Test fun `verified cleanup removes only owned files then marker and tolerates proven absence`() {
        val port = Port(listOf(original, corrected, marker))
        port.entries.add(PhotoRollbackPort.Entry("unrelated.jpg", "unrelated-id"))
        assertTrue(PhotoRollbackPolicy.rollback(identity, port))
        assertEquals(listOf("original-id", "corrected-id", "marker-id"), port.deleted)
        assertEquals("unrelated-id", port.entries.single().identity)
        assertTrue(PhotoRollbackPolicy.rollback(identity, port))
    }

    @Test fun `unknown provider results never prove cleanup or delete marker`() {
        val ports = listOf(
            Port(listOf(original, corrected, marker)).apply { nullList = true },
            Port(listOf(original, corrected, marker)).apply { throwList = true },
            Port(listOf(original, corrected, marker)).apply { failedDelete = original.identity },
            Port(listOf(original, corrected, marker)).apply { throwDelete = true },
            Port(listOf(original, corrected, marker)).apply { keepDeleted = true },
            Port(listOf(original, corrected, marker)).apply { failedDelete = marker.identity }
        )
        ports.forEach { port ->
            assertFalse(PhotoRollbackPolicy.rollback(identity, port))
            assertTrue(port.entries.any { it.identity == marker.identity })
        }
    }

    @Test fun `ambiguous identity or replaced file is preserved including in place replacement`() {
        val ports = listOf(
            Port(listOf(original, corrected, marker)).apply {
                entries.add(PhotoRollbackPort.Entry(original.name, "other-id"))
            },
            Port(listOf(original, corrected, marker)).apply {
                entries[0] = PhotoRollbackPort.Entry(original.name, "replacement-id")
            },
            Port(listOf(original, corrected, marker)).apply { hashes[original.identity] = "replaced-in-place" },
            Port(listOf(original, corrected, marker)).apply { hashes[marker.identity] = "changed-marker" }
        )
        ports.forEach { port ->
            assertFalse(PhotoRollbackPolicy.rollback(identity, port))
            assertTrue(port.deleted.isEmpty())
        }
    }

    @Test fun `cancel cleanup uncertainty keeps barrier after worker done and disallows retry and acknowledgement`() {
        val operation = AppNoteSaveCoordinator.startPhotoSession("capture", "note")!!
        val owner = Any()
        AppNoteSaveCoordinator.observePhotoSession(operation, owner) { }
        AppNoteSaveCoordinator.requestPhotoCancel("capture")
        val port = Port(listOf(original, corrected, marker)).apply { failedDelete = corrected.identity }
        if (!PhotoRollbackPolicy.rollback(identity, port))
            AppNoteSaveCoordinator.requirePhotoRecovery(operation, "retained cleanup")
        AppNoteSaveCoordinator.finishPhotoWorker(operation)
        assertFalse(AppNoteSaveCoordinator.isVaultMutationBarrierClear())
        assertFalse(AppNoteSaveCoordinator.acknowledgePhotoSession(operation, owner))
        assertNull(AppNoteSaveCoordinator.startPhotoSession("capture", "note"))
        assertEquals(AppNoteSaveCoordinator.PhotoPhase.RECOVERY_REQUIRED, AppNoteSaveCoordinator.photoPhase("capture"))
    }
}
