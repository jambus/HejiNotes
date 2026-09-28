package com.jambus.heji

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class RemoteDriveSyncEngineIntegrationTest {

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun md5(str: String): String = md5Hex(str.toByteArray(StandardCharsets.UTF_8))
    private fun sha256(str: String): String = sha256Hex(str.toByteArray(StandardCharsets.UTF_8))

    private class FakeSyncVaultAccessor : SyncVaultAccessor {
        val files = mutableMapOf<String, ByteArray>()
        var failWriteIfAbsent = false
        val trashedFiles = mutableListOf<String>()

        fun setFile(path: String, content: String) {
            files[path] = content.toByteArray(StandardCharsets.UTF_8)
        }

        override fun syncFilesStrict(): List<VaultSyncFile> {
            return files.map { (path, bytes) ->
                VaultSyncFile(
                    relativePath = path,
                    document = VaultDocument(
                        uri = TestUri("content://test/$path"),
                        name = path.substringAfterLast('/'),
                        mimeType = "text/markdown",
                        relativePath = path,
                        lastModified = 1000L,
                        size = bytes.size.toLong()
                    )
                )
            }
        }

        var onBeforeOpenSyncInput: ((String) -> Unit)? = null

        override fun openSyncInput(file: VaultSyncFile): InputStream? {
            onBeforeOpenSyncInput?.invoke(file.relativePath)
            val bytes = files[file.relativePath] ?: return null
            return ByteArrayInputStream(bytes)
        }

        override fun moveSyncFileToTrash(file: VaultSyncFile): VaultMutationResult {
            files.remove(file.relativePath)
            trashedFiles += file.relativePath
            return VaultMutationResult.Success(file.document, file.document.name, file.document.name)
        }

        var onBeforeTrashIfUnchanged: ((String) -> Unit)? = null

        override fun moveSyncFileToTrashIfUnchanged(file: VaultSyncFile, expectedSha256: String): VaultMutationResult {
            onBeforeTrashIfUnchanged?.invoke(file.relativePath)
            val bytes = files[file.relativePath]
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.READ_FAILED)
            val currentSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!currentSha256.equals(expectedSha256, ignoreCase = true)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.PRECONDITION_FAILED)
            }
            return moveSyncFileToTrash(file)
        }

        override fun syncMd5(relativePath: String): String? {
            val bytes = files[relativePath] ?: return null
            return MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        override fun writeSyncFile(relativePath: String, mimeType: String?, input: InputStream, expectedCurrentMd5: String?): Boolean {
            files[relativePath] = input.readBytes()
            return true
        }

        override fun writeSyncFileIfAbsent(relativePath: String, mimeType: String?, input: InputStream): Boolean {
            if (failWriteIfAbsent) return false
            if (files.containsKey(relativePath)) return false
            files[relativePath] = input.readBytes()
            return true
        }
    }

    private class FakeDriveGateway(
        override val providerId: String = "fake_drive",
        override val providerName: String = "Fake Drive"
    ) : DriveGateway {
        val items = mutableMapOf<String, FakeItem>()
        val trashedIds = mutableListOf<String>()
        val trashedEtags = mutableMapOf<String, String?>()
        val copiedCalls = mutableListOf<Triple<String, String, String>>()
        val eventOrder = mutableListOf<String>()
        var throwOnScan: Exception? = null
        var throwOnDownload: Exception? = null

        data class FakeItem(
            val id: String,
            val parentId: String,
            val name: String,
            val mimeType: String,
            val content: ByteArray,
            val revision: String = "rev-1",
            var trashed: Boolean = false
        ) {
            val md5: String
                get() = MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it) }

            fun toDriveItem(): DriveItem = DriveItem(
                id = id,
                name = name,
                mimeType = mimeType,
                md5 = if (mimeType == "application/vnd.google-apps.folder") null else md5,
                revision = revision
            )
        }

        fun addItem(
            id: String,
            parentId: String,
            name: String,
            content: String,
            mimeType: String = "text/markdown",
            revision: String = "rev-1"
        ): FakeItem {
            val item = FakeItem(id, parentId, name, mimeType, content.toByteArray(StandardCharsets.UTF_8), revision = revision)
            items[id] = item
            return item
        }

        override val folderMimeType: String = "application/vnd.google-apps.folder"
        override fun isFolder(item: DriveItem): Boolean = item.mimeType == folderMimeType
        override fun shouldSync(item: DriveItem): Boolean = !isFolder(item)

        override fun listChildren(parentId: String): List<DriveItem> {
            throwOnScan?.let { throw it }
            return items.values
                .filter { it.parentId == parentId && !it.trashed }
                .map { it.toDriveItem() }
        }

        override fun createFolder(parentId: String, name: String): DriveItem {
            val id = "folder_${items.size + 1}"
            val item = FakeItem(id, parentId, name, folderMimeType, ByteArray(0))
            items[id] = item
            return item.toDriveItem()
        }

        override fun upload(parentId: String, name: String, mimeType: String, input: InputStream) {
            val id = "file_${items.size + 1}"
            val item = FakeItem(id, parentId, name, mimeType, input.readBytes())
            items[id] = item
            eventOrder += "upload:$name"
        }

        override fun replace(id: String, expectedEtag: String?, mimeType: String, input: InputStream) {
            val existing = items[id] ?: throw DriveApiException("Not found")
            items[id] = existing.copy(content = input.readBytes(), revision = "rev-${System.currentTimeMillis()}")
            eventOrder += "replace:$id"
        }

        override fun copy(id: String, parentId: String, name: String): DriveItem {
            val source = items[id] ?: throw DriveApiException("Source not found")
            val copyId = "copy_${items.size + 1}"
            val copied = FakeItem(copyId, parentId, name, source.mimeType, source.content.copyOf(), revision = "rev-copy")
            items[copyId] = copied
            copiedCalls += Triple(id, parentId, name)
            eventOrder += "copy:$id->$name"
            return copied.toDriveItem()
        }

        override fun trash(id: String, expectedEtag: String?) {
            val item = items[id] ?: throw DriveApiException("Not found")
            item.trashed = true
            trashedIds += id
            trashedEtags[id] = expectedEtag
            eventOrder += "trash:$id"
        }

        override fun refresh(id: String): DriveItem {
            return (items[id] ?: throw DriveApiException("Not found")).toDriveItem()
        }

        var onRevision: ((String) -> Unit)? = null

        override fun revision(id: String): DriveRevision {
            onRevision?.invoke(id)
            val item = items[id] ?: throw DriveApiException("Not found")
            return DriveRevision(item.toDriveItem(), "etag-$id")
        }

        override fun download(item: DriveItem): InputStream {
            throwOnDownload?.let { throw it }
            val fake = items[item.id] ?: throw DriveApiException("Not found")
            return ByteArrayInputStream(fake.content)
        }
    }

    private class FakeMoveChangeStore : MoveChangeStore {
        val changesList = mutableListOf<MoveBundleChange>()
        val acknowledged = mutableListOf<Pair<String, String?>>()

        override fun changes(vaultId: String, providerId: String?): List<MoveBundleChange> =
            changesList.filter { it.vaultId == vaultId }

        override fun acknowledge(id: String, providerId: String?): Boolean {
            acknowledged += id to providerId
            return true
        }
    }

    private class FakeDriveBaselineStore : DriveBaselineStore {
        var currentBaseline: DriveSyncBaseline? = null
        var saveFails: Boolean = false
        var onSaveCallback: (() -> Unit)? = null

        override fun load(vaultId: String, rootId: String, accountId: String): DriveBaselineLoad {
            return currentBaseline?.let { DriveBaselineLoad.Present(it) } ?: DriveBaselineLoad.Missing
        }

        override fun save(baseline: DriveSyncBaseline): Boolean {
            onSaveCallback?.invoke()
            if (saveFails) return false
            currentBaseline = baseline
            return true
        }
    }

    @Test
    fun `MoveBundle is NOT acknowledged when baseline save fails`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("target.md", "note-content")

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-source", "root-1", "source.md", "note-content", revision = "rev-1")
        gateway.addItem("item-target", "root-1", "target.md", "note-content", revision = "rev-1")

        val baselineStore = FakeDriveBaselineStore()
        baselineStore.currentBaseline = DriveSyncBaseline(
            vaultId = "vault-1",
            rootId = "root-1",
            accountId = "acc-1",
            files = mapOf(
                "source.md" to DriveBaselineFile(
                    path = "source.md",
                    localSha256 = sha256("note-content"),
                    remoteId = "item-source",
                    remoteMd5 = md5("note-content"),
                    remoteVersion = null,
                    remoteRevision = "rev-1",
                    localMd5 = md5("note-content")
                )
            ),
            completedAt = 1000L
        )

        val changeStore = FakeMoveChangeStore()
        val change = MoveBundleChange(
            id = "move-fail-1",
            vaultId = "vault-1",
            sourceToTarget = mapOf("source.md" to "target.md"),
            beforeSha256 = mapOf("source.md" to sha256("note-content")),
            afterSha256 = mapOf("target.md" to sha256("note-content")),
            state = LocalChangeState.COMMITTED,
            committedAt = 2000L
        )
        changeStore.changesList.add(change)

        baselineStore.saveFails = true
        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            changeStore = changeStore,
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertFalse("Sync must fail when baseline cannot be saved", result.isSuccessful)
        assertEquals(listOf("无法保存同步基线"), result.errors)
        assertTrue("MoveBundle must NOT be acknowledged if baseline save fails", changeStore.acknowledged.isEmpty())
    }

    @Test
    fun `MoveBundle is acknowledged when baseline save succeeds`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("target.md", "note-content")

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-source", "root-1", "source.md", "note-content", revision = "rev-1")
        gateway.addItem("item-target", "root-1", "target.md", "note-content", revision = "rev-1")

        val baselineStore = FakeDriveBaselineStore()
        baselineStore.currentBaseline = DriveSyncBaseline(
            vaultId = "vault-1",
            rootId = "root-1",
            accountId = "acc-1",
            files = mapOf(
                "source.md" to DriveBaselineFile(
                    path = "source.md",
                    localSha256 = sha256("note-content"),
                    remoteId = "item-source",
                    remoteMd5 = md5("note-content"),
                    remoteVersion = null,
                    remoteRevision = "rev-1",
                    localMd5 = md5("note-content")
                )
            ),
            completedAt = 1000L
        )

        val changeStore = FakeMoveChangeStore()
        val change = MoveBundleChange(
            id = "move-success-1",
            vaultId = "vault-1",
            sourceToTarget = mapOf("source.md" to "target.md"),
            beforeSha256 = mapOf("source.md" to sha256("note-content")),
            afterSha256 = mapOf("target.md" to sha256("note-content")),
            state = LocalChangeState.COMMITTED,
            committedAt = 2000L
        )
        changeStore.changesList.add(change)

        baselineStore.saveFails = false
        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            changeStore = changeStore,
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertTrue("Sync should succeed: ${result.errors}", result.isSuccessful)
        assertEquals(1, changeStore.acknowledged.size)
        assertEquals("move-success-1" to gateway.providerId, changeStore.acknowledged.first())
        assertNotNull(baselineStore.currentBaseline)
    }

    @Test
    fun `new provider adopts MoveBundle and verifies conflict copy before trashing remote source`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("target.md", "target-body")

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-source", "root-1", "source.md", "modified-remotely-body")
        gateway.addItem("item-target", "root-1", "target.md", "target-body")

        // New provider has no baseline
        val baselineStore = FakeDriveBaselineStore()

        val changeStore = FakeMoveChangeStore()
        val change = MoveBundleChange(
            id = "move-adoption-123",
            vaultId = "vault-1",
            sourceToTarget = mapOf("source.md" to "target.md"),
            beforeSha256 = mapOf("source.md" to sha256("original-unmodified-body")),
            afterSha256 = mapOf("target.md" to sha256("target-body")),
            state = LocalChangeState.COMMITTED,
            committedAt = 2000L
        )
        changeStore.changesList.add(change)

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            changeStore = changeStore,
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertTrue("Sync should succeed with conflict preservation", result.isSuccessful)
        assertTrue("Conflict should be recorded", result.conflicts >= 1)

        // 1. Conflict copy is created locally
        val localConflictKey = vaultAccessor.files.keys.firstOrNull { it.contains("Fake Drive conflict move-ado") }
        assertTrue("Local conflict copy must exist", localConflictKey != null)
        assertEquals("modified-remotely-body", String(vaultAccessor.files[localConflictKey]!!, StandardCharsets.UTF_8))

        // 2. Conflict copy is created on remote gateway
        val remoteConflictCopy = gateway.copiedCalls.firstOrNull { it.first == "item-source" }
        assertTrue("Remote conflict copy must be invoked", remoteConflictCopy != null)

        // 3. Conflict copy is verified before old path is trashed
        val copyIndex = gateway.eventOrder.indexOfFirst { it.startsWith("copy:item-source") }
        val trashIndex = gateway.eventOrder.indexOfFirst { it == "trash:item-source" }
        assertTrue("Copy must happen before trash", copyIndex >= 0 && trashIndex >= 0 && copyIndex < trashIndex)

        // 4. Old path was trashed
        assertTrue("Old source must be trashed", gateway.trashedIds.contains("item-source"))
    }

    @Test
    fun `new provider aborts move without trashing source if conflict copy cannot be saved locally`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("target.md", "target-body")
        vaultAccessor.failWriteIfAbsent = true // Fault: cannot write local conflict file

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-source", "root-1", "source.md", "remote-modified")
        gateway.addItem("item-target", "root-1", "target.md", "target-body")

        val baselineStore = FakeDriveBaselineStore()
        val changeStore = FakeMoveChangeStore()
        changeStore.changesList.add(
            MoveBundleChange(
                id = "move-adoption-fail",
                vaultId = "vault-1",
                sourceToTarget = mapOf("source.md" to "target.md"),
                beforeSha256 = mapOf("source.md" to sha256("original-before")),
                afterSha256 = mapOf("target.md" to sha256("target-body")),
                state = LocalChangeState.COMMITTED
            )
        )

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            changeStore = changeStore,
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertFalse("Sync must fail when conflict file cannot be written", result.isSuccessful)
        assertFalse("Source must NOT be trashed if conflict preservation failed", gateway.trashedIds.contains("item-source"))
        assertTrue("MoveBundle must not be acknowledged", changeStore.acknowledged.isEmpty())
    }

    @Test
    fun `cancellation during sync before baseline commit preserves uncommitted state`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("doc.md", "doc-content")

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-doc", "root-1", "doc.md", "doc-content")

        val coordinator = SyncCancellationCoordinator()
        val baselineStore = FakeDriveBaselineStore()

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            baselineStore = baselineStore,
            coordinator = coordinator,
            onProgress = { progress ->
                if (progress.completed >= 1) {
                    coordinator.cancel()
                }
            }
        )

        val result = engine.sync(root)
        assertTrue("Result should report cancellation", result.cancelled)
        assertNull("Baseline must remain uncommitted upon cancellation", baselineStore.currentBaseline)
    }

    @Test
    fun `relogin required exception is rethrown directly out of sync engine`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("doc.md", "doc-content")

        val gateway = FakeDriveGateway()
        gateway.throwOnScan = OneDriveReloginRequired()
        val root = DriveVaultRoot("root-1", "Remote Vault")

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1"
        )

        try {
            engine.sync(root)
            fail("RemoteDriveSyncEngine must rethrow OneDriveReloginRequired")
        } catch (ex: OneDriveReloginRequired) {
            assertEquals("Microsoft sign-in interaction is required", ex.message)
        }
    }

    @Test
    fun `local file edited after sync scan is preserved and uploaded instead of moved to trash`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        vaultAccessor.setFile("doc.md", "original-content")

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")

        val baselineStore = FakeDriveBaselineStore()
        baselineStore.currentBaseline = DriveSyncBaseline(
            vaultId = "vault-1",
            rootId = "root-1",
            accountId = "acc-1",
            files = mapOf(
                "doc.md" to DriveBaselineFile(
                    path = "doc.md",
                    localSha256 = sha256("original-content"),
                    remoteId = "item-doc",
                    remoteMd5 = md5("original-content"),
                    remoteVersion = null,
                    remoteRevision = "rev-1",
                    localMd5 = md5("original-content")
                )
            ),
            completedAt = 1000L
        )

        vaultAccessor.onBeforeTrashIfUnchanged = { path ->
            if (path == "doc.md") {
                vaultAccessor.setFile("doc.md", "newly-edited-content")
            }
        }

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertTrue("Sync should succeed: ${result.errors}", result.isSuccessful)
        assertFalse("Locally modified file must not be moved to trash", vaultAccessor.trashedFiles.contains("doc.md"))
        assertTrue("Locally modified file must be uploaded", gateway.eventOrder.contains("upload:doc.md"))
        assertEquals(1, result.uploaded)
        assertEquals(0, result.deleted)
        val uploadedItem = gateway.items.values.firstOrNull { it.name == "doc.md" }
        assertNotNull(uploadedItem)
        assertEquals("newly-edited-content", String(uploadedItem!!.content, StandardCharsets.UTF_8))
    }

    @Test
    fun `remote deletion reconciliation uses revision etag for trash concurrency`() {
        val vaultAccessor = FakeSyncVaultAccessor()

        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-doc", "root-1", "doc.md", "content", revision = "rev-1")

        val baselineStore = FakeDriveBaselineStore()
        baselineStore.currentBaseline = DriveSyncBaseline(
            vaultId = "vault-1",
            rootId = "root-1",
            accountId = "acc-1",
            files = mapOf(
                "doc.md" to DriveBaselineFile(
                    path = "doc.md",
                    localSha256 = sha256("content"),
                    remoteId = "item-doc",
                    remoteMd5 = md5("content"),
                    remoteVersion = null,
                    remoteRevision = "rev-1",
                    localMd5 = md5("content")
                )
            ),
            completedAt = 1000L
        )

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertTrue("Sync should succeed: ${result.errors}", result.isSuccessful)
        assertEquals(1, result.deleted)
        assertTrue("Remote item must be trashed", gateway.trashedIds.contains("item-doc"))
        assertEquals("etag-item-doc", gateway.trashedEtags["item-doc"])
    }

    @Test
    fun `remote deletion fails if remote file changed during sync`() {
        val vaultAccessor = FakeSyncVaultAccessor()
        val gateway = FakeDriveGateway()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        val item = gateway.addItem("item-doc", "root-1", "doc.md", "content", revision = "rev-1")

        val baselineStore = FakeDriveBaselineStore()
        baselineStore.currentBaseline = DriveSyncBaseline(
            vaultId = "vault-1",
            rootId = "root-1",
            accountId = "acc-1",
            files = mapOf(
                "doc.md" to DriveBaselineFile(
                    path = "doc.md",
                    localSha256 = sha256("content"),
                    remoteId = "item-doc",
                    remoteMd5 = md5("content"),
                    remoteVersion = null,
                    remoteRevision = "rev-1",
                    localMd5 = md5("content")
                )
            ),
            completedAt = 1000L
        )

        // Simulate concurrent remote modification right before engine fetches revision to trash
        gateway.onRevision = { id ->
            if (id == "item-doc") {
                gateway.items["item-doc"] = item.copy(revision = "rev-2-concurrent")
            }
        }

        val engine = RemoteDriveSyncEngine(
            repository = vaultAccessor,
            api = gateway,
            vaultId = "vault-1",
            accountId = "acc-1",
            baselineStore = baselineStore
        )

        val result = engine.sync(root)
        assertFalse("Sync should fail due to concurrent remote modification", result.isSuccessful)
        assertFalse("Remote item must not be trashed when revision changed", gateway.trashedIds.contains("item-doc"))
    }
}
