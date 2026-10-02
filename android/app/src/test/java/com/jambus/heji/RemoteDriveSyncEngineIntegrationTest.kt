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
        var afterWrite: ((String) -> Unit)? = null

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
            afterWrite?.invoke(relativePath)
            return true
        }
    }

    private class FakeDriveGateway(
        override val providerId: String = "fake_drive",
        override val providerName: String = "Fake Drive",
        val metadataHashes: Boolean = true
    ) : DriveGateway {
        val items = mutableMapOf<String, FakeItem>()
        val trashedIds = mutableListOf<String>()
        val trashedEtags = mutableMapOf<String, String?>()
        val copiedCalls = mutableListOf<Triple<String, String, String>>()
        val eventOrder = mutableListOf<String>()
        var throwOnScan: Exception? = null
        var throwOnDownload: Exception? = null
        var mutationEtag: String? = "\"valid-etag\""
        var revisionMd5: String? = null
        var downloadBody: ((String, Int) -> String?)? = null
        var onScan: ((String) -> Unit)? = null
        var onCreate: ((FakeItem) -> FakeItem)? = null
        val downloadedIds = mutableListOf<String>()

        init { addItem("root-1", "", "Remote Vault", "", mimeType = "application/vnd.google-apps.folder") }

        private fun snapshot(item: FakeItem): DriveItem = item.toDriveItem().let {
            if (metadataHashes) it else it.copy(md5 = null)
        }

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
            onScan?.invoke(parentId)
            return items.values
                .filter { it.parentId == parentId && !it.trashed }
                .map(::snapshot)
        }

        override fun createFolder(parentId: String, name: String): DriveItem {
            val id = "folder_${items.size + 1}"
            val item = FakeItem(id, parentId, name, folderMimeType, ByteArray(0))
            items[id] = item
            return (onCreate?.invoke(item) ?: item).toDriveItem()
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
            return snapshot(items[id] ?: throw DriveApiException("Not found"))
        }

        var onRevision: ((String) -> Unit)? = null

        override fun revision(id: String): DriveRevision {
            onRevision?.invoke(id)
            val item = items[id] ?: throw DriveApiException("Not found")
            return DriveRevision(snapshot(item).let { if (revisionMd5 != null && !isFolder(it)) it.copy(md5 = revisionMd5) else it }, mutationEtag)
        }

        override fun download(item: DriveItem): InputStream {
            throwOnDownload?.let { throw it }
            downloadedIds += item.id
            val fake = items[item.id] ?: throw DriveApiException("Not found")
            return ByteArrayInputStream(downloadBody?.invoke(item.id, downloadedIds.size)?.toByteArray() ?: fake.content)
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

    private fun forEachProvider(block: (FakeDriveGateway) -> Unit) {
        block(FakeDriveGateway("google_drive", "Google Drive", metadataHashes = true))
        block(FakeDriveGateway("onedrive", "OneDrive", metadataHashes = false))
    }

    @Test
    fun `missing precondition refuses replacement and recycle before any cloud mutation`() = forEachProvider { gateway ->
        for (missingLocal in listOf(false, true)) {
            val vault = FakeSyncVaultAccessor()
            if (!missingLocal) vault.setFile("doc.md", "local-edit")
            val old = baselineFor("original")
            val store = FakeDriveBaselineStore().apply { currentBaseline = old }
            gateway.addItem("item-doc", "root-1", "doc.md", "original")
            gateway.mutationEtag = null
            gateway.eventOrder.clear()
            val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
            assertFalse(result.isSuccessful)
            assertEquals(SyncErrorReason.PRECONDITION_UNAVAILABLE, result.errorDetails.single().reason)
            assertTrue(gateway.eventOrder.isEmpty())
            assertEquals(old, store.currentBaseline)
            assertEquals("original", String(gateway.items.getValue("item-doc").content))
        }
    }

    @Test
    fun `wrong EOF body or missing hash disagreement does not replace original`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "cloud-update", revision = "rev-2")
        gateway.downloadBody = { _, count -> if (gateway.metadataHashes || count >= 2) "wrong-short" else null }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.CONTENT_MISMATCH, result.errorDetails.single().reason)
        assertEquals("original", String(vault.files.getValue("doc.md")))
        assertEquals(old, store.currentBaseline)
        assertTrue(gateway.eventOrder.isEmpty())
    }

    @Test
    fun `revision changes during actual download fail before local replacement`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        val item = gateway.addItem("item-doc", "root-1", "doc.md", "cloud-update", revision = "rev-2")
        gateway.downloadBody = { id, count ->
            if (id == "item-doc" && count == if (gateway.metadataHashes) 1 else 2) {
                gateway.items[id] = item.copy(revision = "rev-3")
            }
            null
        }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.REMOTE_CHANGED, result.errorDetails.single().reason)
        assertEquals("original", String(vault.files.getValue("doc.md")))
        assertEquals(old, store.currentBaseline)
    }

    @Test
    fun `cached folder rename move or replacement refuses uploading to the old identity`() = forEachProvider { gateway ->
        for (change in listOf("rename", "move", "replace")) {
            val vault = FakeSyncVaultAccessor().apply { setFile("notes/new.md", "local") }
            val store = FakeDriveBaselineStore()
            val folder = gateway.addItem("notes-folder", "root-1", "notes", "", mimeType = gateway.folderMimeType)
            var reads = 0
            gateway.onScan = { parent ->
                if (parent == "root-1" && ++reads == 2) {
                    gateway.items[folder.id] = when (change) {
                        "rename" -> folder.copy(name = "renamed")
                        "move" -> folder.copy(parentId = "outside")
                        else -> folder.copy(trashed = true)
                    }
                    if (change == "replace") gateway.addItem("replacement-folder", "root-1", "notes", "", mimeType = gateway.folderMimeType)
                }
            }
            gateway.eventOrder.clear()
            val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
            assertFalse(result.isSuccessful)
            assertTrue(gateway.eventOrder.isEmpty())
            assertNull(store.currentBaseline)
            gateway.onScan = null
            gateway.items.remove(folder.id)
            gateway.items.remove("replacement-folder")
        }
    }

    @Test
    fun `incorrect or ambiguous folder creation response refuses file upload`() = forEachProvider { gateway ->
        for (ambiguous in listOf(false, true)) {
            val vault = FakeSyncVaultAccessor().apply { setFile("new-folder/new.md", "local") }
            val store = FakeDriveBaselineStore()
            gateway.onCreate = { item ->
                if (ambiguous) {
                    gateway.addItem("duplicate-folder", item.parentId, item.name, "", mimeType = gateway.folderMimeType)
                    item
                } else item.copy(name = "provider-renamed")
            }
            gateway.eventOrder.clear()
            val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
            assertFalse(result.isSuccessful)
            assertEquals(SyncErrorReason.PATH_AMBIGUOUS, result.errorDetails.single().reason)
            assertTrue(gateway.eventOrder.isEmpty())
            assertNull(store.currentBaseline)
            gateway.items.entries.removeAll { it.value.name == "new-folder" }
        }
    }

    @Test
    fun `directory moved after preserving local conflict refuses server copy`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("notes/doc.md", "local-edit") }
        val store = FakeDriveBaselineStore()
        val folder = gateway.addItem("notes-folder", "root-1", "notes", "", mimeType = gateway.folderMimeType)
        gateway.addItem("item-doc", folder.id, "doc.md", "cloud-edit", revision = "rev-2")
        vault.afterWrite = { path ->
            if (path.contains("conflict")) gateway.items[folder.id] = folder.copy(parentId = "outside")
        }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals("local-edit", String(vault.files.getValue("notes/doc.md")))
        assertTrue(vault.files.filterKeys { it.contains("conflict") }.values.any { String(it) == "cloud-edit" })
        assertTrue(gateway.copiedCalls.isEmpty())
        assertTrue(gateway.eventOrder.isEmpty())
        assertNull(store.currentBaseline)
    }

    @Test
    fun `checksum becoming available during independent read rejects a corrupt first body`() {
        val gateway = FakeDriveGateway("google_drive", "Google Drive", metadataHashes = false)
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "cloud-update", revision = "rev-2")
        gateway.revisionMd5 = md5("cloud-update")
        gateway.downloadBody = { _, _ -> "wrong-short" }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.CONTENT_MISMATCH, result.errorDetails.single().reason)
        assertEquals("original", String(vault.files.getValue("doc.md")))
        assertEquals(old, store.currentBaseline)
    }

    @Test
    fun `move rescan cannot replace original folder identity or recycle replacement content`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("notes/target.md", "note-content") }
        val store = FakeDriveBaselineStore()
        val journal = FakeMoveChangeStore().apply {
            changesList.add(MoveBundleChange(
                id = "move-parent-drift", vaultId = "vault-1",
                sourceToTarget = mapOf("notes/source.md" to "notes/target.md"),
                beforeSha256 = mapOf("notes/source.md" to sha256("note-content")),
                afterSha256 = mapOf("notes/target.md" to sha256("note-content")),
                state = LocalChangeState.COMMITTED, committedAt = 2000L
            ))
        }
        val folder = gateway.addItem("old-parent", "root-1", "notes", "", mimeType = gateway.folderMimeType)
        gateway.addItem("item-source", folder.id, "source.md", "note-content")
        gateway.addItem("item-target", folder.id, "target.md", "note-content")
        var replaced = false
        val engine = RemoteDriveSyncEngine(vault, gateway, "vault-1", "acc-1", journal, store) { progress ->
            if (!replaced && progress.completed == 1) {
                replaced = true
                gateway.items[folder.id] = folder.copy(trashed = true)
                gateway.addItem("new-parent", "root-1", "notes", "", mimeType = gateway.folderMimeType)
                gateway.addItem("new-source", "new-parent", "source.md", "external-edit")
                gateway.addItem("new-target", "new-parent", "target.md", "note-content")
            }
        }
        val result = engine.sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.REMOTE_CHANGED, result.errorDetails.single().reason)
        assertTrue(gateway.trashedIds.isEmpty())
        assertTrue(gateway.copiedCalls.isEmpty())
        assertTrue(journal.acknowledged.isEmpty())
        assertNull(store.currentBaseline)
        assertEquals("external-edit", String(gateway.items.getValue("new-source").content))
    }

    @Test
    fun `duplicate empty remote folders fail before any mutation`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("upload.md", "local") }
        val store = FakeDriveBaselineStore()
        gateway.addItem("folder-1", "root-1", "notes", "", mimeType = gateway.folderMimeType)
        gateway.addItem("folder-2", "root-1", "notes", "", mimeType = gateway.folderMimeType)
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.PATH_AMBIGUOUS, result.errorDetails.single().reason)
        assertTrue(gateway.eventOrder.isEmpty())
        assertEquals(setOf("upload.md"), vault.files.keys)
        assertNull(store.currentBaseline)
    }

    @Test
    fun `remote file folder collision fails before downloading either child`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore()
        gateway.addItem("folder", "root-1", "notes.md", "", mimeType = gateway.folderMimeType)
        gateway.addItem("file", "root-1", "notes.md", "cloud")
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(SyncErrorReason.PATH_AMBIGUOUS, result.errorDetails.single().reason)
        assertTrue(gateway.downloadedIds.isEmpty())
        assertTrue(gateway.eventOrder.isEmpty())
        assertTrue(vault.files.isEmpty())
    }

    private fun baselineFor(content: String, verified: Boolean = true, revision: String = "rev-1"): DriveSyncBaseline =
        DriveSyncBaseline("vault-1", "root-1", "acc-1", mapOf("doc.md" to DriveBaselineFile(
            path = "doc.md", localSha256 = sha256(content), remoteId = "item-doc",
            remoteMd5 = md5(content), remoteVersion = null, localMd5 = md5(content),
            remoteRevision = revision, contentVerified = verified
        )), 1000L)

    private fun engineFor(
        vault: FakeSyncVaultAccessor,
        gateway: FakeDriveGateway,
        store: FakeDriveBaselineStore,
        onProgress: (SyncProgress) -> Unit = {}
    ): RemoteDriveSyncEngine = RemoteDriveSyncEngine(
        repository = vault, api = gateway, vaultId = "vault-1", accountId = "acc-1",
        baselineStore = store, onProgress = onProgress
    )

    @Test
    fun `both providers download new remote content then propagate local deletion to recycle bin`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore()
        val root = DriveVaultRoot("root-1", "Remote Vault")
        gateway.addItem("item-doc", root.id, "doc.md", "cloud-content")
        val engine = engineFor(vault, gateway, store)

        val first = engine.sync(root)
        assertTrue(first.errors.toString(), first.isSuccessful)
        assertEquals(1, first.downloaded)
        assertEquals("cloud-content", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
        assertTrue(store.currentBaseline!!.files.getValue("doc.md").contentVerified)
        gateway.downloadedIds.clear()
        val repeat = engineFor(vault, gateway, store).sync(root)
        assertTrue(repeat.errors.toString(), repeat.isSuccessful)
        assertEquals(1, repeat.unchanged)
        assertEquals(0, gateway.downloadedIds.size)

        vault.files.remove("doc.md")
        val deleted = engineFor(vault, gateway, store).sync(root)
        assertTrue(deleted.errors.toString(), deleted.isSuccessful)
        assertEquals(1, deleted.deleted)
        assertEquals(0, deleted.downloaded)
        assertEquals(listOf("item-doc"), gateway.trashedIds)
        assertEquals("\"valid-etag\"", gateway.trashedEtags["item-doc"])
        assertTrue(store.currentBaseline!!.files.isEmpty())
    }

    @Test
    fun `both providers download remote update when local file was deleted`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("old-content") }
        gateway.addItem("item-doc", "root-1", "doc.md", "new-cloud-content", revision = "rev-2")

        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(result.errors.toString(), result.isSuccessful)
        assertEquals(1, result.downloaded)
        assertEquals(0, result.deleted)
        assertTrue(gateway.trashedIds.isEmpty())
        assertEquals("new-cloud-content", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
    }

    @Test
    fun `both providers propagate remote deletion but preserve later local edit`() = forEachProvider { gateway ->
        for (content in listOf("original", "edited")) {
            val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", content) }
            val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("original") }
            val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
            assertTrue(result.errors.toString(), result.isSuccessful)
            if (content == "original") {
                assertEquals(1, result.deleted)
                assertEquals(listOf("doc.md"), vault.trashedFiles)
            } else {
                assertEquals(1, result.uploaded)
                assertEquals(0, result.deleted)
                assertTrue(vault.trashedFiles.isEmpty())
            }
        }
    }

    @Test
    fun `remote update after comparison does not poison baseline and downloads on retry`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        val item = gateway.addItem("item-doc", "root-1", "doc.md", "original")
        var injected = false
        val engine = engineFor(vault, gateway, store) { progress ->
            if (!injected && progress.completed == 1) {
                injected = true
                gateway.items[item.id] = item.copy(content = "remote-later".toByteArray(), revision = "rev-2")
            }
        }
        val first = engine.sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(first.isSuccessful)
        assertEquals(old, store.currentBaseline)
        val retry = engine.sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(retry.errors.toString(), retry.isSuccessful)
        assertEquals(1, retry.downloaded)
        assertEquals("remote-later", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
    }

    @Test
    fun `same size and timestamp local save after comparison uploads on retry`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "before") }
        val old = baselineFor("before")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "before")
        var injected = false
        val engine = engineFor(vault, gateway, store) { progress ->
            if (!injected && progress.completed == 1) {
                injected = true
                vault.setFile("doc.md", "edited") // Same size; fake SAF mtime remains 1000.
            }
        }
        assertFalse(engine.sync(DriveVaultRoot("root-1", "Remote Vault")).isSuccessful)
        assertEquals(old, store.currentBaseline)
        val retry = engine.sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(retry.errors.toString(), retry.isSuccessful)
        assertEquals(1, retry.uploaded)
        assertEquals("edited", String(gateway.items.getValue("item-doc").content, StandardCharsets.UTF_8))
    }

    @Test
    fun `cloud path added during run prevents success and downloads on retry`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "original")
        var injected = false
        val engine = engineFor(vault, gateway, store) { progress ->
            if (!injected && progress.completed == 1) {
                injected = true
                gateway.addItem("new-item", "root-1", "new.md", "new-cloud-file")
            }
        }
        assertFalse(engine.sync(DriveVaultRoot("root-1", "Remote Vault")).isSuccessful)
        assertEquals(old, store.currentBaseline)
        val retry = engine.sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(retry.errors.toString(), retry.isSuccessful)
        assertEquals(1, retry.downloaded)
        assertTrue(vault.files.containsKey("new.md"))
    }

    @Test
    fun `legacy manufactured hash never recycles different cloud content`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("old-local", verified = false) }
        gateway.addItem("item-doc", "root-1", "doc.md", "real-cloud-content")
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(result.errors.toString(), result.isSuccessful)
        assertEquals(1, result.downloaded)
        assertTrue(gateway.trashedIds.isEmpty())
        assertEquals("real-cloud-content", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
    }

    @Test
    fun `legacy manufactured baseline with both files preserves conflict`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "old-local") }
        val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("old-local", verified = false) }
        gateway.addItem("item-doc", "root-1", "doc.md", "real-cloud-content")
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(result.errors.toString(), result.isSuccessful)
        assertEquals(1, result.conflicts)
        assertEquals("old-local", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
        assertTrue(vault.files.filterKeys { it != "doc.md" }.values.any {
            String(it, StandardCharsets.UTF_8) == "real-cloud-content"
        })
    }

    @Test
    fun `valid legacy baseline is verified once and stable OneDrive avoids repeat downloads`() {
        val gateway = FakeDriveGateway("onedrive", "OneDrive", metadataHashes = false)
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("original", verified = false) }
        gateway.addItem("item-doc", "root-1", "doc.md", "original")
        val engine = engineFor(vault, gateway, store)
        assertTrue(engine.sync(DriveVaultRoot("root-1", "Remote Vault")).isSuccessful)
        assertEquals(listOf("item-doc"), gateway.downloadedIds)
        assertTrue(store.currentBaseline!!.files.getValue("doc.md").contentVerified)
        gateway.downloadedIds.clear()
        assertTrue(engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault")).isSuccessful)
        assertTrue(gateway.downloadedIds.isEmpty())
    }

    @Test
    fun `unknown legacy verification leaves baseline untouched and never deletes`() {
        val gateway = FakeDriveGateway("onedrive", "OneDrive", metadataHashes = false)
        val vault = FakeSyncVaultAccessor()
        val old = baselineFor("original", verified = false)
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "original")
        gateway.throwOnDownload = DriveApiException("Simulated download failure")
        assertFalse(engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault")).isSuccessful)
        assertEquals(old, store.currentBaseline)
        assertTrue(gateway.trashedIds.isEmpty())
    }

    @Test
    fun `changed revision legacy remote only file downloads instead of trusting old hashes`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore().apply { currentBaseline = baselineFor("original", verified = false) }
        gateway.addItem("item-doc", "root-1", "doc.md", "updated", revision = "rev-2")
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(result.errors.toString(), result.isSuccessful)
        assertEquals(1, result.downloaded)
        assertTrue(gateway.trashedIds.isEmpty())
    }

    @Test
    fun `demonstrably divergent stored fingerprints never authorize cloud deletion`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor()
        val old = baselineFor("local-version")
        val divergent = old.copy(files = old.files.mapValues { (_, file) ->
            file.copy(remoteMd5 = md5("cloud-version"))
        })
        val store = FakeDriveBaselineStore().apply { currentBaseline = divergent }
        gateway.addItem("item-doc", "root-1", "doc.md", "cloud-version")
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(result.errors.toString(), result.isSuccessful)
        assertEquals(1, result.downloaded)
        assertTrue(gateway.trashedIds.isEmpty())
    }

    @Test
    fun `final OneDrive content read must keep the listed revision before committing`() {
        val gateway = FakeDriveGateway("onedrive", "OneDrive", metadataHashes = false)
        val vault = FakeSyncVaultAccessor()
        val store = FakeDriveBaselineStore()
        val item = gateway.addItem("item-doc", "root-1", "doc.md", "cloud-content")
        gateway.onRevision = { id ->
            if (id == "item-doc") gateway.items[id] = item.copy(revision = "changed-during-verification")
        }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertNull(store.currentBaseline)
        assertTrue(gateway.trashedIds.isEmpty())
    }

    @Test
    fun `unreadable final local file fails commit instead of silently omitting path`() = forEachProvider { gateway ->
        val vault = FakeSyncVaultAccessor().apply { setFile("doc.md", "original") }
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        gateway.addItem("item-doc", "root-1", "doc.md", "original")
        var opened = 0
        vault.onBeforeOpenSyncInput = { path ->
            opened++
            if (opened == 2) vault.files.remove(path)
        }
        val result = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(result.isSuccessful)
        assertEquals(old, store.currentBaseline)
        assertTrue(gateway.trashedIds.isEmpty())
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
                    localMd5 = md5("original-content"),
                    contentVerified = true
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
        assertEquals("\"valid-etag\"", gateway.trashedEtags["item-doc"])
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
                    localMd5 = md5("content"),
                    contentVerified = true
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

    @Test
    fun `fresh hash drift with same revision blocks cloud recycle and downloads on retry`() {
        val vault = FakeSyncVaultAccessor()
        val gateway = FakeDriveGateway("google_drive", "Google Drive")
        val old = baselineFor("original")
        val store = FakeDriveBaselineStore().apply { currentBaseline = old }
        val item = gateway.addItem("item-doc", "root-1", "doc.md", "original")
        gateway.onRevision = { id ->
            if (id == "item-doc") gateway.items[id] = item.copy(content = "changed-cloud-content".toByteArray())
        }
        val first = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertFalse(first.isSuccessful)
        assertTrue(gateway.trashedIds.isEmpty())
        assertEquals(old, store.currentBaseline)

        gateway.onRevision = null
        val retry = engineFor(vault, gateway, store).sync(DriveVaultRoot("root-1", "Remote Vault"))
        assertTrue(retry.errors.toString(), retry.isSuccessful)
        assertEquals(1, retry.downloaded)
        assertTrue(gateway.trashedIds.isEmpty())
        assertEquals("changed-cloud-content", String(vault.files.getValue("doc.md"), StandardCharsets.UTF_8))
    }
}
