package com.jambus.heji

import android.util.Log
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class SyncProgress(val completed: Int, val total: Int, val message: String)
typealias DriveSyncProgress = SyncProgress

data class SyncRunResult(
    val uploaded: Int,
    val downloaded: Int,
    val unchanged: Int,
    val conflicts: Int,
    val errors: List<String>,
    val cancelled: Boolean
) {
    val isSuccessful: Boolean get() = errors.isEmpty() && !cancelled
}
typealias DriveSyncResult = SyncRunResult

internal enum class MoveAdoptionDecision { UNCHANGED_SOURCE, PRESERVE_CONFLICT, MISSING_FINGERPRINT }

internal object MoveAdoptionPolicy {
    fun decide(expectedBeforeSha256: String?, actualRemoteSha256: String): MoveAdoptionDecision = when {
        expectedBeforeSha256 == null -> MoveAdoptionDecision.MISSING_FINGERPRINT
        expectedBeforeSha256.equals(actualRemoteSha256, true) -> MoveAdoptionDecision.UNCHANGED_SOURCE
        else -> MoveAdoptionDecision.PRESERVE_CONFLICT
    }
}

internal object MoveHistoryTargetPolicy {
    /**
     * Resolves the final destination path for a moved file by strictly traversing subsequent
     * changes in chronological order. Because time is strictly monotonic (i > changeIndex),
     * this avoids false cycle detection when notes are moved back and forth (e.g. A -> B -> A).
     */
    fun resolveFinalTarget(startPath: String, changeIndex: Int, allChanges: List<MoveBundleChange>): String {
        var current = startPath
        for (i in (changeIndex + 1) until allChanges.size) {
            val next = allChanges[i].sourceToTarget[current]
            if (next != null) {
                current = next
            }
        }
        return current
    }
}

internal object MoveTargetVerificationPolicy {
    fun targetsToVerify(
        sourceToTarget: Map<String, String>,
        changeIndex: Int,
        allChanges: List<MoveBundleChange>,
        localFiles: Map<String, *>,
        remoteFiles: Map<String, *>
    ): Set<String> {
        val targets = mutableSetOf<String>()
        sourceToTarget.forEach { (source, initialTarget) ->
            val finalTarget = MoveHistoryTargetPolicy.resolveFinalTarget(initialTarget, changeIndex, allChanges)
            val sourceNeedsTrash = remoteFiles.containsKey(source) && !localFiles.containsKey(source)
            val targetExistsLocally = localFiles.containsKey(finalTarget)
            if (sourceNeedsTrash || targetExistsLocally) {
                targets.add(finalTarget)
            }
        }
        return targets
    }
}

internal enum class SyncComparisonAction {
    UNCHANGED,
    UPLOAD_LOCAL_UPDATE,
    DOWNLOAD_REMOTE_UPDATE,
    CONFLICT
}

internal data class SyncItemComparisonResult(
    val action: SyncComparisonAction,
    val remoteHash: String?,
    val remoteRevisionMatches: Boolean
)

internal object SyncItemComparisonPolicy {
    fun evaluate(
        localMd5: String?,
        localSha256: String?,
        remoteMd5: String?,
        remoteId: String,
        remoteRevision: String?,
        baseline: DriveBaselineFile?,
        remoteHashSupplier: () -> String?
    ): SyncItemComparisonResult {
        if (remoteMd5 != null && remoteMd5.equals(localMd5, true)) {
            return SyncItemComparisonResult(SyncComparisonAction.UNCHANGED, remoteMd5, false)
        }
        val remoteRevisionMatches = baseline != null &&
            baseline.remoteId == remoteId &&
            (baseline.remoteRevision ?: baseline.remoteVersion?.toString()) != null &&
            (baseline.remoteRevision ?: baseline.remoteVersion?.toString()) == remoteRevision
        val localChanged = baseline == null || !baseline.localSha256.equals(localSha256, true)
        if (!localChanged && remoteRevisionMatches) {
            return SyncItemComparisonResult(SyncComparisonAction.UNCHANGED, baseline?.remoteMd5, true)
        }
        val remoteHash = if (remoteRevisionMatches) baseline?.remoteMd5 else (remoteMd5 ?: remoteHashSupplier())
        val remoteChanged = if (remoteRevisionMatches) false else {
            baseline == null || baseline.remoteId != remoteId || !baseline.remoteMd5.equals(remoteHash, true)
        }
        val action = when {
            remoteHash != null && remoteHash.equals(localMd5, true) -> SyncComparisonAction.UNCHANGED
            localChanged && !remoteChanged -> SyncComparisonAction.UPLOAD_LOCAL_UPDATE
            !localChanged && remoteChanged -> SyncComparisonAction.DOWNLOAD_REMOTE_UPDATE
            else -> SyncComparisonAction.CONFLICT
        }
        return SyncItemComparisonResult(action, remoteHash, remoteRevisionMatches)
    }
}

private data class LocalFileSnapshot(
    val digests: LocalFileDigests,
    val lastModified: Long?,
    val size: Long?
)

class RemoteDriveSyncEngine(
    private val repository: VaultRepository,
    private val api: DriveGateway,
    private val vaultId: String = "",
    private val accountId: String = "",
    private val changeStore: MoveChangeStore? = null,
    private val baselineStore: DriveBaselineStore? = null,
    private val coordinator: SyncCancellationCoordinator = SyncCancellationCoordinator(),
    private val onProgress: (SyncProgress) -> Unit = {}
) {
    constructor(
        repository: VaultRepository,
        api: DriveGateway,
        vaultId: String = "",
        accountId: String = "",
        changeStore: MoveChangeStore? = null,
        baselineStore: DriveBaselineStore? = null,
        cancelled: AtomicBoolean,
        onProgress: (SyncProgress) -> Unit = {}
    ) : this(repository, api, vaultId, accountId, changeStore, baselineStore, SyncCancellationCoordinator(cancelled), onProgress)

    private val providerName: String get() = api.providerName

    fun sync(root: DriveVaultRoot): SyncRunResult {
        val errors = mutableListOf<String>()
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val remoteFolders = linkedMapOf<String, String>()
        remoteFolders[""] = root.id
        try {
            if (!scanRemote(root.id, "", remoteFiles, remoteFolders)) {
                return SyncRunResult(0, 0, 0, 0, emptyList(), true)
            }
        } catch (failure: Exception) {
            if (failure is OneDriveReloginRequired) throw failure
            return SyncRunResult(0, 0, 0, 0, listOf(userMessage(failure)), false)
        }
        if (coordinator.isCancelled) return SyncRunResult(0, 0, 0, 0, emptyList(), true)

        val baseline = when (val loaded = baselineStore?.load(vaultId, root.id, accountId)) {
            null, DriveBaselineLoad.Missing -> emptyMap()
            is DriveBaselineLoad.Present -> loaded.baseline.files
            DriveBaselineLoad.Corrupt -> return SyncRunResult(0, 0, 0, 0, listOf("同步基线损坏，本次同步未修改远端"), false)
        }

        val localFiles = try { repository.syncFilesStrict().associateBy { it.relativePath } } catch (_: Exception) {
            return SyncRunResult(0, 0, 0, 0, listOf("无法完整读取本地 Vault，本次同步未修改远端"), false)
        }
        val localSnapshots = mutableMapOf<String, LocalFileSnapshot>()
        for ((path, file) in localFiles) {
            val digest = LocalFileDigestsCalculator.computeDigests(repository.openSyncInput(file))
                ?: return SyncRunResult(0, 0, 0, 0, listOf("无法读取本地同步文件"), false)
            localSnapshots[path] = LocalFileSnapshot(digest, file.document.lastModified, file.document.size)
        }
        val localDigests = localSnapshots.mapValues { it.value.digests }.toMutableMap()
        val localMd5 = localDigests.mapValues { it.value.md5 }
        val localSha256 = localDigests.mapValues { it.value.sha256 }
        val moveChanges = when (val loaded = changeStore?.changes(vaultId, api.providerId)) {
            null -> if (changeStore == null) emptyList() else return SyncRunResult(0, 0, 0, 0, listOf("本地变化历史损坏，本次同步未修改远端"), false)
            else -> loaded
        }
        if (moveChanges.any { it.state != LocalChangeState.COMMITTED }) {
            return SyncRunResult(0, 0, 0, 0, listOf("本地移动仍在恢复中，请重启应用后重试同步"), false)
        }
        val moveSources = moveChanges.flatMap { it.sourceToTarget.keys }.toSet()
        val sortedChanges = moveChanges.sortedBy { it.committedAt }
        var uploaded = 0
        var downloaded = 0
        var unchanged = 0
        var conflicts = 0
        val completedChangeIds = mutableListOf<String>()
        val total = localFiles.size + remoteFiles.keys.minus(localFiles.keys).size
        var completed = 0

        localFiles.toSortedMap().forEach { (path, local) ->
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
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
                        val decision = SyncItemComparisonPolicy.evaluate(
                            localMd5 = localMd5[path],
                            localSha256 = localSha256[path],
                            remoteMd5 = remote.md5,
                            remoteId = remote.id,
                            remoteRevision = remote.revision,
                            baseline = baseline[path],
                            remoteHashSupplier = { md5(api.download(remote)) }
                        )
                        val remoteHash = decision.remoteHash
                        val remoteRevisionMatches = decision.remoteRevisionMatches
                        when (decision.action) {
                            SyncComparisonAction.UNCHANGED -> unchanged++
                            SyncComparisonAction.UPLOAD_LOCAL_UPDATE -> {
                                report(completed, total, "正在上传本地更新：$path")
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: if (remoteRevisionMatches) remoteHash else md5(api.download(current))
                                if (current.revision != remote.revision || !currentHash.equals(remoteHash, true)) {
                                    throw IllegalStateException("$providerName file changed during sync")
                                }
                                repository.openSyncInput(local)?.use { api.replace(remote.id, revision.etag, local.document.mimeType ?: "application/octet-stream", it) }
                                    ?: throw IllegalStateException("Unable to read local file")
                                uploaded++
                            }
                            SyncComparisonAction.DOWNLOAD_REMOTE_UPDATE -> {
                                report(completed, total, "正在下载远端更新：$path")
                                if (!localMd5[path].equals(repository.syncMd5(path), true)) {
                                    val conflict = conflictPath(path, providerName, conflictStamp())
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
                                        val conflict = conflictPath(path, providerName, conflictStamp())
                                        api.download(remote).use { input -> if (!repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)) throw IllegalStateException("Unable to preserve concurrent local edit") }
                                        conflicts++
                                    }
                                }
                            }
                            SyncComparisonAction.CONFLICT -> {
                                report(completed, total, "发现冲突：$path")
                                val stamp = conflictStamp()
                                val remoteConflictPath = conflictPath(path, providerName, stamp)
                                api.download(remote).use { input ->
                                    val saved = repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                                    if (!saved) throw IllegalStateException("Unable to preserve Drive conflict copy")
                                }
                                val parentId = ensureRemoteFolder(path.substringBeforeLast('/', ""), remoteFolders)
                                api.copy(remote.id, parentId, remoteConflictPath.substringAfterLast('/'))
                                val input = repository.openSyncInput(local) ?: throw IllegalStateException("Unable to read local file")
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: if (remoteRevisionMatches) remoteHash else md5(api.download(current))
                                if (current.revision != remote.revision || !currentHash.equals(remoteHash, true)) throw IllegalStateException("$providerName file changed during sync")
                                input.use { api.replace(remote.id, revision.etag, local.document.mimeType ?: "application/octet-stream", it) }
                                conflicts++
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "File sync failed for $path: ${failure.message}", failure)
                if (failure is OneDriveReloginRequired) throw failure
                errors += "$path: ${userMessage(failure)}"
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)

        sortedChanges.forEachIndexed { changeIdx, change ->
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            try {
                when (applyCommittedMove(change, changeIdx, sortedChanges, baseline, root, localFiles, remoteFolders)) {
                    MoveApplyResult.COMPLETED -> completedChangeIds += change.id
                    MoveApplyResult.COMPLETED_WITH_CONFLICT -> {
                        completedChangeIds += change.id
                        conflicts++
                    }
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "Apply committed move failed for change ${change.id}: ${failure.message}", failure)
                if (failure is OneDriveReloginRequired) throw failure
                val representativePath = change.sourceToTarget.keys.firstOrNull { it.endsWith(".md", true) }
                    ?: change.sourceToTarget.keys.firstOrNull().orEmpty()
                errors += if (representativePath.isNotEmpty()) {
                    "$representativePath: 本地搬运失败: ${userMessage(failure)}"
                } else {
                    "本地搬运 ${change.id.take(8)}: ${userMessage(failure)}"
                }
            }
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)

        remoteFiles.toSortedMap().forEach { (path, remote) ->
            if (localFiles.containsKey(path)) return@forEach
            if (path in moveSources) return@forEach
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            try {
                report(completed, total, "正在下载 $path")
                val downloadedToOriginalPath = api.download(remote).use { input ->
                    repository.writeSyncFileIfAbsent(path, remote.mimeType, input)
                }
                if (downloadedToOriginalPath) {
                    downloaded++
                } else {
                    val remoteConflictPath = conflictPath(path, providerName, conflictStamp())
                    val preserved = api.download(remote).use { input ->
                        repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                    }
                    if (!preserved) throw IllegalStateException("Unable to preserve remote conflict copy")
                    conflicts++
                    report(completed, total, "本地文件在同步期间发生变化，已保留冲突副本：$path")
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "Remote file download failed for $path: ${failure.message}", failure)
                if (failure is OneDriveReloginRequired) throw failure
                errors += "$path: ${userMessage(failure)}"
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
        val outcome = result(uploaded, downloaded, unchanged, conflicts, errors, false)
        if (outcome.isSuccessful && baselineStore != null) {
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            val baselineSaved = try {
                saveBaseline(root, localSnapshots)
            } catch (failure: Exception) {
                if (failure is OneDriveReloginRequired) throw failure
                BaselineSaveResult.FAILED
            }
            if (baselineSaved == BaselineSaveResult.CANCELLED) {
                return result(uploaded, downloaded, unchanged, conflicts, errors, true)
            }
            if (baselineSaved == BaselineSaveResult.FAILED) return SyncRunResult(uploaded, downloaded, unchanged, conflicts, listOf("无法保存同步基线"), false)
            completedChangeIds.forEach { id ->
                if (changeStore?.acknowledge(id, api.providerId) != true) return SyncRunResult(uploaded, downloaded, unchanged, conflicts, listOf("无法确认本地搬运历史"), false)
            }
        }
        return outcome
    }

    private fun saveBaseline(root: DriveVaultRoot, localSnapshots: Map<String, LocalFileSnapshot>): BaselineSaveResult {
        if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
        val local = repository.syncFilesStrict().associateBy { it.relativePath }
        if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val folders = linkedMapOf("" to root.id)
        if (!scanRemote(root.id, "", remoteFiles, folders)) return BaselineSaveResult.CANCELLED
        val files = buildMap {
            local.forEach { (path, file) ->
                if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
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
                    localSize = size,
                    remoteRevision = remote.revision
                ))
            }
        }
        if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
        val baseline = DriveSyncBaseline(vaultId, root.id, accountId, files, System.currentTimeMillis())
        if (!coordinator.tryBeginCommit()) return BaselineSaveResult.CANCELLED
        val saved = try {
            baselineStore?.save(baseline) ?: true
        } catch (ex: Exception) {
            coordinator.abortCommit()
            throw ex
        }
        return if (saved) {
            coordinator.markCommitted()
            BaselineSaveResult.SAVED
        } else {
            coordinator.abortCommit()
            BaselineSaveResult.FAILED
        }
    }

    private enum class BaselineSaveResult { SAVED, CANCELLED, FAILED }

    private fun applyCommittedMove(
        change: MoveBundleChange,
        changeIndex: Int,
        allChanges: List<MoveBundleChange>,
        baseline: Map<String, DriveBaselineFile>,
        root: DriveVaultRoot,
        localFiles: Map<String, VaultSyncFile>,
        folders: MutableMap<String, String>
    ): MoveApplyResult {
        var preservedAdoptionConflict = false
        val refreshedFiles = linkedMapOf<String, DriveItem>()
        val refreshedFolders = linkedMapOf("" to root.id)
        if (!scanRemote(root.id, "", refreshedFiles, refreshedFolders)) throw SyncTransferCancelled()
        folders.putAll(refreshedFolders)

        val targetsToVerify = MoveTargetVerificationPolicy.targetsToVerify(
            change.sourceToTarget,
            changeIndex,
            allChanges,
            localFiles,
            refreshedFiles
        )
        targetsToVerify.forEach { target ->
            val local = localFiles[target] ?: throw IllegalStateException("Move target is missing locally")
            val remote = refreshedFiles[target] ?: throw IllegalStateException("Move target was not uploaded")
            val localHash = md5(repository.openSyncInput(local)) ?: throw IllegalStateException("Move target is unreadable")
            val remoteHash = remote.md5 ?: md5(api.download(remote))
            if (!localHash.equals(remoteHash, true)) throw IllegalStateException("Move target verification failed")
        }
        change.sourceToTarget.keys.forEach { source ->
            if (localFiles.containsKey(source)) return@forEach
            val remote = refreshedFiles[source] ?: return@forEach
            val actual = remote.md5 ?: md5(api.download(remote))
            val base = baseline[source]
            val baselineMatches = if (base == null) {
                val actualSha256 = sha256(api.download(remote))
                    ?: throw IllegalStateException("Move source is unreadable")
                when (MoveAdoptionPolicy.decide(change.beforeSha256[source], actualSha256)) {
                    MoveAdoptionDecision.MISSING_FINGERPRINT -> throw IllegalStateException("Move source has no adoption fingerprint")
                    MoveAdoptionDecision.UNCHANGED_SOURCE -> Unit
                    MoveAdoptionDecision.PRESERVE_CONFLICT -> {
                    val conflict = conflictPath(source, providerName, change.id.take(8))
                    val existingLocalConflict = localFiles[conflict]
                    if (existingLocalConflict == null) {
                        val saved = api.download(remote).use { input ->
                            repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)
                        }
                        if (!saved) throw IllegalStateException("Unable to preserve adopted move conflict locally")
                        if (!repository.syncMd5(conflict).equals(actual, true)) {
                            throw IllegalStateException("Adopted move local conflict verification failed")
                        }
                    } else {
                        val existingHash = md5(repository.openSyncInput(existingLocalConflict))
                        if (!existingHash.equals(actual, true)) throw IllegalStateException("Adopted move local conflict path is occupied")
                    }
                    val parent = ensureRemoteFolder(conflict.substringBeforeLast('/', ""), folders)
                    val existingRemoteConflict = refreshedFiles[conflict]
                    if (existingRemoteConflict == null) {
                        api.copy(remote.id, parent, conflict.substringAfterLast('/'))
                    } else {
                        val existingHash = existingRemoteConflict.md5 ?: md5(api.download(existingRemoteConflict))
                        if (!existingHash.equals(actual, true)) throw IllegalStateException("Adopted move remote conflict path is occupied")
                    }
                    preservedAdoptionConflict = true
                    }
                }
                true
            } else {
                base.remoteId == remote.id && base.remoteMd5.equals(actual, true) &&
                    ((base.remoteRevision ?: base.remoteVersion?.toString()) == null ||
                        (base.remoteRevision ?: base.remoteVersion?.toString()) == remote.revision)
            }
            if (!baselineMatches) {
                val conflict = conflictPath(source, providerName, change.id.take(8))
                val parent = ensureRemoteFolder(conflict.substringBeforeLast('/', ""), folders)
                if (refreshedFiles[conflict] == null) api.copy(remote.id, parent, conflict.substringAfterLast('/'))
                throw IllegalStateException("Move source changed remotely or has no successful baseline")
            }
            val revision = api.revision(remote.id)
            val current = revision.item
            val currentHash = current.md5 ?: md5(api.download(current))
            if (current.revision != remote.revision || !currentHash.equals(actual, true)) throw IllegalStateException("$providerName source changed during sync")
            api.trash(remote.id, revision.etag)
        }
        val afterFiles = linkedMapOf<String, DriveItem>()
        val afterFolders = linkedMapOf("" to root.id)
        if (!scanRemote(root.id, "", afterFiles, afterFolders)) throw SyncTransferCancelled()
        val trashedSources = change.sourceToTarget.keys.filter { !localFiles.containsKey(it) }
        if (trashedSources.any(afterFiles::containsKey)) throw IllegalStateException("Old $providerName paths are still present")
        return if (preservedAdoptionConflict) MoveApplyResult.COMPLETED_WITH_CONFLICT else MoveApplyResult.COMPLETED
    }

    private enum class MoveApplyResult { COMPLETED, COMPLETED_WITH_CONFLICT }

    private fun scanRemote(
        parentId: String,
        prefix: String,
        files: MutableMap<String, DriveItem>,
        folders: MutableMap<String, String>
    ): Boolean {
        if (coordinator.isCancelled) return false
        api.listChildren(parentId).forEach { item ->
            if (coordinator.isCancelled) return false
            val path = join(prefix, item.name)
            if (!remotePathAllowed(path, api.isFolder(item))) return@forEach
            if (api.isFolder(item)) {
                folders[path] = item.id
                if (!scanRemote(item.id, path, files, folders)) return false
            } else if (api.shouldSync(item)) {
                if (files.put(path, item) != null) {
                    throw DriveApiException("$providerName contains duplicate paths; rename one before syncing")
                }
            }
        }
        return !coordinator.isCancelled
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
            it.name == name && api.isFolder(it)
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

    private fun report(completed: Int, total: Int, message: String) = onProgress(SyncProgress(completed, total, message))

    private fun result(
        uploaded: Int, downloaded: Int, unchanged: Int, conflicts: Int, errors: List<String>, cancelled: Boolean
    ) = SyncRunResult(uploaded, downloaded, unchanged, conflicts, errors, cancelled)

    private fun userMessage(failure: Exception): String = when (failure) {
        is DriveApiException -> failure.message ?: "$providerName request failed"
        else -> failure.message?.takeIf { it.isNotBlank() } ?: "无法完成此文件"
    }
}

typealias GoogleDriveSyncService = RemoteDriveSyncEngine
