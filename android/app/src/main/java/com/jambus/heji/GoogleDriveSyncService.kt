package com.jambus.heji

import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class DriveSyncProgress(val completed: Int, val total: Int, val message: String)

data class DriveSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val unchanged: Int,
    val conflicts: Int,
    val errors: List<String>,
    val cancelled: Boolean
) {
    val isSuccessful: Boolean get() = errors.isEmpty() && !cancelled
}

private data class LocalFileSnapshot(
    val digests: LocalFileDigests,
    val lastModified: Long?,
    val size: Long?
)

class GoogleDriveSyncService(
    private val repository: VaultRepository,
    private val api: DriveGateway,
    private val vaultId: String = "",
    private val accountId: String = "",
    private val changeStore: MoveChangeStore? = null,
    private val baselineStore: DriveBaselineStore? = null,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
    private val onProgress: (DriveSyncProgress) -> Unit = {}
) {
    fun sync(root: DriveVaultRoot): DriveSyncResult {
        val errors = mutableListOf<String>()
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val remoteFolders = linkedMapOf<String, String>()
        remoteFolders[""] = root.id
        try {
            scanRemote(root.id, "", remoteFiles, remoteFolders)
        } catch (failure: Exception) {
            return DriveSyncResult(0, 0, 0, 0, listOf(userMessage(failure)), false)
        }
        if (cancelled.get()) return DriveSyncResult(0, 0, 0, 0, emptyList(), true)

        val baseline = when (val loaded = baselineStore?.load(vaultId, root.id, accountId)) {
            null, DriveBaselineLoad.Missing -> emptyMap()
            is DriveBaselineLoad.Present -> loaded.baseline.files
            DriveBaselineLoad.Corrupt -> return DriveSyncResult(0, 0, 0, 0, listOf("同步基线损坏，本次同步未修改远端"), false)
        }

        val localFiles = try { repository.syncFilesStrict().associateBy { it.relativePath } } catch (_: Exception) {
            return DriveSyncResult(0, 0, 0, 0, listOf("无法完整读取本地 Vault，本次同步未修改远端"), false)
        }
        val localSnapshots = mutableMapOf<String, LocalFileSnapshot>()
        for ((path, file) in localFiles) {
            val digest = LocalFileDigestsCalculator.computeDigests(repository.openSyncInput(file))
                ?: return DriveSyncResult(0, 0, 0, 0, listOf("无法读取本地同步文件"), false)
            localSnapshots[path] = LocalFileSnapshot(digest, file.document.lastModified, file.document.size)
        }
        val localDigests = localSnapshots.mapValues { it.value.digests }.toMutableMap()
        val localMd5 = localDigests.mapValues { it.value.md5 }
        val localSha256 = localDigests.mapValues { it.value.sha256 }
        val moveChanges = when (val loaded = changeStore?.changes(vaultId)) {
            null -> if (changeStore == null) emptyList() else return DriveSyncResult(0, 0, 0, 0, listOf("本地变化历史损坏，本次同步未修改远端"), false)
            else -> loaded
        }
        if (moveChanges.any { it.state != LocalChangeState.COMMITTED }) {
            return DriveSyncResult(0, 0, 0, 0, listOf("本地移动仍在恢复中，请重启应用后重试同步"), false)
        }
        val moveSources = moveChanges.flatMap { it.sourceToTarget.keys }.toSet()
        val allMoveMappings = moveChanges.flatMap { it.sourceToTarget.entries }.associate { it.key to it.value }
        var uploaded = 0
        var downloaded = 0
        var unchanged = 0
        var conflicts = 0
        val completedChangeIds = mutableListOf<String>()
        val total = localFiles.size + remoteFiles.keys.minus(localFiles.keys).size
        var completed = 0

        localFiles.toSortedMap().forEach { (path, local) ->
            if (cancelled.get()) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            val remote = remoteFiles[path]
            try {
                when {
                    remote == null -> {
                        report(completed, total, "正在上传 $path")
                        upload(path, local, remoteFolders)
                        uploaded++
                    }
                    remote.md5 != null && remote.md5.equals(localMd5[path], true) -> {
                        unchanged++
                    }
                    else -> {
                        val base = baseline[path]
                        val remoteHash = remote.md5 ?: md5(api.download(remote))
                        val localChanged = base == null || !base.localSha256.equals(localSha256[path], true)
                        val remoteChanged = base == null || base.remoteId != remote.id || !base.remoteMd5.equals(remoteHash, true)
                        when {
                            localChanged && !remoteChanged -> {
                                report(completed, total, "正在上传本地更新：$path")
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: md5(api.download(current))
                                if (current.version != remote.version || !currentHash.equals(remoteHash, true)) {
                                    throw IllegalStateException("Google Drive file changed during sync")
                                }
                                repository.openSyncInput(local)?.use { api.replace(remote.id, revision.etag, local.document.mimeType ?: "application/octet-stream", it) }
                                    ?: throw IllegalStateException("Unable to read local file")
                                uploaded++
                            }
                            !localChanged && remoteChanged -> {
                                report(completed, total, "正在下载远端更新：$path")
                                if (!localMd5[path].equals(repository.syncMd5(path), true)) {
                                    val conflict = conflictPath(path, "Google Drive", conflictStamp())
                                    api.download(remote).use { input ->
                                        if (!repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)) throw IllegalStateException("Unable to preserve concurrent local edit")
                                    }
                                    conflicts++
                                } else {
                                    val written = api.download(remote).use { input -> repository.writeSyncFile(path, remote.mimeType, input, localMd5[path]) }
                                    if (written) {
                                        localDigests.remove(path)
                                        localSnapshots.remove(path)
                                        downloaded++
                                    } else {
                                        val conflict = conflictPath(path, "Google Drive", conflictStamp())
                                        api.download(remote).use { input -> if (!repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)) throw IllegalStateException("Unable to preserve concurrent local edit") }
                                        conflicts++
                                    }
                                }
                            }
                            else -> {
                                report(completed, total, "发现冲突：$path")
                                val stamp = conflictStamp()
                                val remoteConflictPath = conflictPath(path, "Google Drive", stamp)
                                api.download(remote).use { input ->
                                    val saved = repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                                    if (!saved) throw IllegalStateException("Unable to preserve Drive conflict copy")
                                }
                                val parentId = ensureRemoteFolder(path.substringBeforeLast('/', ""), remoteFolders)
                                api.copy(remote.id, parentId, remoteConflictPath.substringAfterLast('/'))
                                val input = repository.openSyncInput(local) ?: throw IllegalStateException("Unable to read local file")
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: md5(api.download(current))
                                if (current.version != remote.version || !currentHash.equals(remoteHash, true)) throw IllegalStateException("Google Drive file changed during sync")
                                input.use { api.replace(remote.id, revision.etag, local.document.mimeType ?: "application/octet-stream", it) }
                                conflicts++
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                errors += "$path: ${userMessage(failure)}"
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }

        moveChanges.forEach { change ->
            if (cancelled.get()) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            try {
                if (applyCommittedMove(change, allMoveMappings, baseline, root, localFiles, remoteFolders) == MoveApplyResult.COMPLETED) {
                    completedChangeIds += change.id
                }
            } catch (failure: Exception) {
                errors += "本地搬运 ${change.id.take(8)}: ${userMessage(failure)}"
            }
        }

        remoteFiles.toSortedMap().forEach { (path, remote) ->
            if (localFiles.containsKey(path)) return@forEach
            if (path in moveSources) return@forEach
            if (cancelled.get()) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            try {
                report(completed, total, "正在下载 $path")
                val downloadedToOriginalPath = api.download(remote).use { input ->
                    repository.writeSyncFileIfAbsent(path, remote.mimeType, input)
                }
                if (downloadedToOriginalPath) {
                    downloaded++
                } else {
                    val remoteConflictPath = conflictPath(path, "Google Drive", conflictStamp())
                    val preserved = api.download(remote).use { input ->
                        repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                    }
                    if (!preserved) throw IllegalStateException("Unable to preserve remote conflict copy")
                    conflicts++
                    report(completed, total, "本地文件在同步期间发生变化，已保留冲突副本：$path")
                }
            } catch (failure: Exception) {
                errors += "$path: ${userMessage(failure)}"
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }
        val outcome = result(uploaded, downloaded, unchanged, conflicts, errors, false)
        if (outcome.isSuccessful && baselineStore != null) {
            val baselineSaved = runCatching { saveBaseline(root, localSnapshots) }.getOrDefault(false)
            if (!baselineSaved) return DriveSyncResult(uploaded, downloaded, unchanged, conflicts, listOf("无法保存同步基线"), false)
            completedChangeIds.forEach { id ->
                if (changeStore?.acknowledge(id) != true) return DriveSyncResult(uploaded, downloaded, unchanged, conflicts, listOf("无法确认本地搬运历史"), false)
            }
        }
        return outcome
    }

    private fun saveBaseline(root: DriveVaultRoot, localSnapshots: Map<String, LocalFileSnapshot>): Boolean {
        val local = repository.syncFilesStrict().associateBy { it.relativePath }
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val folders = linkedMapOf("" to root.id)
        scanRemote(root.id, "", remoteFiles, folders)
        val files = buildMap {
            local.forEach { (path, file) ->
                val remote = remoteFiles[path] ?: return@forEach
                val snapshot = localSnapshots[path]
                val (digests, mtime, size) = if (snapshot != null &&
                    snapshot.lastModified == file.document.lastModified &&
                    snapshot.size == file.document.size
                ) {
                    Triple(snapshot.digests, snapshot.lastModified, snapshot.size)
                } else {
                    val fresh = LocalFileDigestsCalculator.computeDigests(repository.openSyncInput(file))
                        ?: return@forEach
                    Triple(fresh, file.document.lastModified, file.document.size)
                }
                val sha = digests.sha256
                val localMd5 = digests.md5
                put(path, DriveBaselineFile(
                    path = path,
                    localSha256 = sha,
                    remoteId = remote.id,
                    remoteMd5 = remote.md5 ?: localMd5,
                    remoteVersion = remote.version,
                    localMd5 = localMd5,
                    localLastModified = mtime,
                    localSize = size
                ))
            }
        }
        return baselineStore?.save(DriveSyncBaseline(vaultId, root.id, accountId, files, System.currentTimeMillis())) ?: true
    }

    private fun applyCommittedMove(
        change: MoveBundleChange,
        allMappings: Map<String, String>,
        baseline: Map<String, DriveBaselineFile>,
        root: DriveVaultRoot,
        localFiles: Map<String, VaultSyncFile>,
        folders: MutableMap<String, String>
    ): MoveApplyResult {
        val refreshedFiles = linkedMapOf<String, DriveItem>()
        val refreshedFolders = linkedMapOf("" to root.id)
        scanRemote(root.id, "", refreshedFiles, refreshedFolders)
        folders.putAll(refreshedFolders)
        fun finalTarget(path: String): String {
            var current = path
            val seen = mutableSetOf<String>()
            while (seen.add(current)) current = allMappings[current] ?: return current
            throw IllegalStateException("Move history contains a cycle")
        }
        change.sourceToTarget.values.map(::finalTarget).distinct().forEach { target ->
            val local = localFiles[target] ?: throw IllegalStateException("Move target is missing locally")
            val remote = refreshedFiles[target] ?: throw IllegalStateException("Move target was not uploaded")
            val localHash = md5(repository.openSyncInput(local)) ?: throw IllegalStateException("Move target is unreadable")
            val remoteHash = remote.md5 ?: md5(api.download(remote))
            if (!localHash.equals(remoteHash, true)) throw IllegalStateException("Move target verification failed")
        }
        change.sourceToTarget.keys.forEach { source ->
            val remote = refreshedFiles[source] ?: return@forEach
            val actual = remote.md5 ?: md5(api.download(remote))
            val base = baseline[source]
            if (base == null) return MoveApplyResult.DEFERRED
            val baselineMatches = base.remoteId == remote.id &&
                base.remoteMd5.equals(actual, true) && (base.remoteVersion == null || base.remoteVersion == remote.version)
            if (!baselineMatches) {
                val conflict = conflictPath(source, "Google Drive", change.id.take(8))
                val parent = ensureRemoteFolder(conflict.substringBeforeLast('/', ""), folders)
                if (refreshedFiles[conflict] == null) api.copy(remote.id, parent, conflict.substringAfterLast('/'))
                throw IllegalStateException("Move source changed remotely or has no successful baseline")
            }
            val revision = api.revision(remote.id)
            val current = revision.item
            val currentHash = current.md5 ?: md5(api.download(current))
            if (current.version != remote.version || !currentHash.equals(actual, true)) throw IllegalStateException("Google Drive source changed during sync")
            api.trash(remote.id, revision.etag)
        }
        val afterFiles = linkedMapOf<String, DriveItem>()
        val afterFolders = linkedMapOf("" to root.id)
        scanRemote(root.id, "", afterFiles, afterFolders)
        if (change.sourceToTarget.keys.any(afterFiles::containsKey)) throw IllegalStateException("Old Drive paths are still present")
        return MoveApplyResult.COMPLETED
    }

    private enum class MoveApplyResult { COMPLETED, DEFERRED }

    private fun scanRemote(
        parentId: String,
        prefix: String,
        files: MutableMap<String, DriveItem>,
        folders: MutableMap<String, String>
    ) {
        if (cancelled.get()) return
        api.listChildren(parentId).forEach { item ->
            val path = join(prefix, item.name)
            if (!remotePathAllowed(path, item.mimeType == GoogleDriveApi.FOLDER_MIME_TYPE)) return@forEach
            if (item.mimeType == GoogleDriveApi.FOLDER_MIME_TYPE) {
                folders[path] = item.id
                scanRemote(item.id, path, files, folders)
            } else if (!item.mimeType.startsWith("application/vnd.google-apps.")) {
                if (files.put(path, item) != null) {
                    throw DriveApiException("Google Drive contains duplicate paths; rename one before syncing")
                }
            }
        }
    }

    private fun upload(
        path: String,
        local: VaultSyncFile,
        folders: MutableMap<String, String>
    ) {
        val parentPath = path.substringBeforeLast('/', "")
        val parentId = ensureRemoteFolder(parentPath, folders)
        val input = repository.openSyncInput(local) ?: throw IllegalStateException("Unable to read local file")
        input.use { api.upload(parentId, path.substringAfterLast('/'), local.document.mimeType ?: "application/octet-stream", it) }
    }

    private fun ensureRemoteFolder(path: String, folders: MutableMap<String, String>): String {
        folders[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = ensureRemoteFolder(parentPath, folders)
        val name = path.substringAfterLast('/')
        val existing = api.listChildren(parent).firstOrNull {
            it.name == name && it.mimeType == GoogleDriveApi.FOLDER_MIME_TYPE
        }
        val id = (existing ?: api.createFolder(parent, name)).id
        folders[path] = id
        return id
    }

    private fun md5(input: InputStream?): String? {
        if (input == null) return null
        return input.use {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }

    private fun sha256(input: InputStream?): String? {
        if (input == null) return null
        return input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }

    private fun conflictPath(path: String, source: String, stamp: String): String {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return join(parent, "$stem ($source conflict $stamp)$extension")
    }

    private fun remotePathAllowed(path: String, directory: Boolean): Boolean {
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) return false
        return SyncPathPolicy.isAllowed(path, directory)
    }

    private fun join(parent: String, name: String): String = if (parent.isBlank()) name else "$parent/$name"

    private fun conflictStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())

    private fun report(completed: Int, total: Int, message: String) = onProgress(DriveSyncProgress(completed, total, message))

    private fun result(
        uploaded: Int, downloaded: Int, unchanged: Int, conflicts: Int, errors: List<String>, cancelled: Boolean
    ) = DriveSyncResult(uploaded, downloaded, unchanged, conflicts, errors, cancelled)

    private fun userMessage(failure: Exception): String = when (failure) {
        is DriveApiException -> failure.message ?: "Google Drive request failed"
        else -> "无法完成此文件"
    }
}
