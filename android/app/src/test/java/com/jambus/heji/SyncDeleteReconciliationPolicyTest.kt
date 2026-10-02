package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDeleteReconciliationPolicyTest {

    private fun sampleBaseline(
        path: String = "notes/test.md",
        localSha256: String = "hash_sha256_base",
        remoteId: String = "remote_id_123",
        remoteMd5: String = "hash_md5_base",
        remoteVersion: Long? = 1L,
        remoteRevision: String? = "rev_1"
    ): DriveBaselineFile = DriveBaselineFile(
        path = path,
        localSha256 = localSha256,
        remoteId = remoteId,
        remoteMd5 = remoteMd5,
        remoteVersion = remoteVersion,
        remoteRevision = remoteRevision
    )

    @Test
    fun `new local file without baseline uploads`() {
        val action = SyncDeleteReconciliationPolicy.evaluateLocalOnly(
            localSha256 = "hash_new",
            baseline = null
        )
        assertEquals(SyncLocalOnlyAction.UPLOAD, action)
    }

    @Test
    fun `local file matches baseline SHA-256 when remote deleted moves to trash`() {
        val base = sampleBaseline()
        val action = SyncDeleteReconciliationPolicy.evaluateLocalOnly(
            localSha256 = "hash_sha256_base",
            baseline = base
        )
        assertEquals(SyncLocalOnlyAction.MOVE_TO_TRASH, action)
    }

    @Test
    fun `local file modified after baseline when remote deleted uploads to preserve edits`() {
        val base = sampleBaseline()
        val action = SyncDeleteReconciliationPolicy.evaluateLocalOnly(
            localSha256 = "hash_sha256_edited",
            baseline = base
        )
        assertEquals(SyncLocalOnlyAction.UPLOAD, action)
    }

    @Test
    fun `local file with null sha safely defaults to upload`() {
        val base = sampleBaseline()
        val action = SyncDeleteReconciliationPolicy.evaluateLocalOnly(
            localSha256 = null,
            baseline = base
        )
        assertEquals(SyncLocalOnlyAction.UPLOAD, action)
    }

    @Test
    fun `new remote file without baseline downloads`() {
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "new_remote_id",
            remoteRevision = "rev_1",
            remoteVersion = 1L,
            remoteMd5 = "hash_remote",
            baseline = null,
            remoteHashSupplier = { "hash_remote" }
        )
        assertEquals(SyncRemoteOnlyAction.DOWNLOAD, action)
    }

    @Test
    fun `metadata content change overrides matching revision and version`() {
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "remote_id_123", remoteRevision = "rev_1", remoteVersion = 1L,
            remoteMd5 = "hash_md5_edited", baseline = sampleBaseline(),
            remoteHashSupplier = { throw IllegalStateException("Metadata already supplied the hash") }
        )
        assertEquals(SyncRemoteOnlyAction.DOWNLOAD, action)
    }

    @Test
    fun `remote file matches baseline revision when local deleted moves to remote trash`() {
        val base = sampleBaseline()
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "remote_id_123",
            remoteRevision = "rev_1",
            remoteVersion = 1L,
            remoteMd5 = null,
            baseline = base,
            remoteHashSupplier = { throw IllegalStateException("Should not be called when revision matches") }
        )
        assertEquals(SyncRemoteOnlyAction.MOVE_TO_TRASH, action)
    }

    @Test
    fun `remote file matches baseline md5 when revision absent moves to remote trash`() {
        val base = sampleBaseline(remoteRevision = null, remoteVersion = null)
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "remote_id_123",
            remoteRevision = null,
            remoteVersion = null,
            remoteMd5 = "hash_md5_base",
            baseline = base,
            remoteHashSupplier = { "hash_md5_base" }
        )
        assertEquals(SyncRemoteOnlyAction.MOVE_TO_TRASH, action)
    }

    @Test
    fun `remote file modified after baseline when local deleted downloads to preserve edits`() {
        val base = sampleBaseline()
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "remote_id_123",
            remoteRevision = "rev_2_edited",
            remoteVersion = 2L,
            remoteMd5 = "hash_md5_edited",
            baseline = base,
            remoteHashSupplier = { "hash_md5_edited" }
        )
        assertEquals(SyncRemoteOnlyAction.DOWNLOAD, action)
    }

    @Test
    fun `remote item replaced with different remote id downloads to preserve new file`() {
        val base = sampleBaseline(remoteId = "original_id")
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "replaced_id",
            remoteRevision = "rev_1",
            remoteVersion = 1L,
            remoteMd5 = "hash_md5_base",
            baseline = base,
            remoteHashSupplier = { "hash_md5_base" }
        )
        assertEquals(SyncRemoteOnlyAction.DOWNLOAD, action)
    }

    @Test
    fun `remote item hash uncomputable safely defaults to download`() {
        val base = sampleBaseline(remoteRevision = null, remoteVersion = null)
        val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
            remoteId = "remote_id_123",
            remoteRevision = null,
            remoteVersion = null,
            remoteMd5 = null,
            baseline = base,
            remoteHashSupplier = { null }
        )
        assertEquals(SyncRemoteOnlyAction.DOWNLOAD, action)
    }
}
