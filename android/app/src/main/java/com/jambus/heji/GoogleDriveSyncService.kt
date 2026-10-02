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
    val cancelled: Boolean,
    val deleted: Int = 0,
    val errorDetails: List<SyncErrorDetail> = emptyList()
) {
    val isSuccessful: Boolean get() = errors.isEmpty() && errorDetails.isEmpty() && !cancelled
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
            (baseline.remoteRevision ?: baseline.remoteVersion?.toString()) == remoteRevision &&
            (remoteMd5 == null || baseline.remoteMd5.equals(remoteMd5, true))
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

internal enum class SyncLocalOnlyAction {
    UPLOAD,
    MOVE_TO_TRASH
}

internal enum class SyncRemoteOnlyAction {
    DOWNLOAD,
    MOVE_TO_TRASH
}

internal object SyncDeleteReconciliationPolicy {
    fun evaluateLocalOnly(
        localSha256: String?,
        baseline: DriveBaselineFile?
    ): SyncLocalOnlyAction {
        if (baseline == null) return SyncLocalOnlyAction.UPLOAD
        val localUnchanged = localSha256 != null && baseline.localSha256.equals(localSha256, true)
        return if (localUnchanged) SyncLocalOnlyAction.MOVE_TO_TRASH else SyncLocalOnlyAction.UPLOAD
    }

    fun evaluateRemoteOnly(
        remoteId: String,
        remoteRevision: String?,
        remoteVersion: Long?,
        remoteMd5: String?,
        baseline: DriveBaselineFile?,
        remoteHashSupplier: () -> String?
    ): SyncRemoteOnlyAction {
        if (baseline == null) return SyncRemoteOnlyAction.DOWNLOAD
        val remoteRevisionMatches = baseline.remoteId == remoteId &&
            ((baseline.remoteRevision != null && baseline.remoteRevision == remoteRevision) ||
             (baseline.remoteVersion != null && baseline.remoteVersion == remoteVersion)) &&
            (remoteMd5 == null || baseline.remoteMd5.equals(remoteMd5, true))
        val remoteChanged = if (remoteRevisionMatches) {
            false
        } else {
            val remoteHash = remoteMd5 ?: remoteHashSupplier()
            baseline.remoteId != remoteId || baseline.remoteMd5 == null || remoteHash == null || !baseline.remoteMd5.equals(remoteHash, true)
        }
        return if (!remoteChanged) SyncRemoteOnlyAction.MOVE_TO_TRASH else SyncRemoteOnlyAction.DOWNLOAD
    }
}


private data class LocalFileSnapshot(
    val digests: LocalFileDigests,
    val lastModified: Long?,
    val size: Long?
)

class RemoteDriveSyncEngine(
    private val repository: SyncVaultAccessor,
    private val api: DriveGateway,
    private val vaultId: String = "",
    private val accountId: String = "",
    private val changeStore: MoveChangeStore? = null,
    private val baselineStore: DriveBaselineStore? = null,
    private val coordinator: SyncCancellationCoordinator = SyncCancellationCoordinator(),
    private val onProgress: (SyncProgress) -> Unit = {}
) {
    constructor(
        repository: SyncVaultAccessor,
        api: DriveGateway,
        vaultId: String = "",
        accountId: String = "",
        changeStore: MoveChangeStore? = null,
        baselineStore: DriveBaselineStore? = null,
        cancelled: AtomicBoolean,
        onProgress: (SyncProgress) -> Unit = {}
    ) : this(repository, api, vaultId, accountId, changeStore, baselineStore, SyncCancellationCoordinator(cancelled), onProgress)

    private val providerName: String get() = api.providerName
    private var selectedRootId = ""
    private var selectedRootIdentity = ""
    private var folderIdentities: MutableMap<String, String> = mutableMapOf()
    private val sourceDigests = mutableMapOf<String, LocalFileDigests>()

    fun sync(root: DriveVaultRoot): SyncRunResult {
        val errors = mutableListOf<String>()
        val errorDetails = mutableListOf<SyncErrorDetail>()
        fun recordFailure(failure: Exception, path: String = "") {
            val detail = SyncFailurePolicy.fromException(failure, path)
            errors += detail.reason.name
            errorDetails += detail
        }
        fun result(uploaded: Int, downloaded: Int, unchanged: Int, conflicts: Int, errors: List<String>, cancelled: Boolean, deleted: Int = 0) =
            SyncRunResult(uploaded, downloaded, unchanged, conflicts, errors, cancelled, deleted, errorDetails.toList())
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val remoteFolders = linkedMapOf<String, String>()
        selectedRootId = root.id
        selectedRootIdentity = ""
        sourceDigests.clear()
        folderIdentities = linkedMapOf()
        remoteFolders[""] = root.id
        try {
            val rootItem = api.revision(root.id).item
            if (!api.isFolder(rootItem)) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS)
            selectedRootIdentity = rootItem.id
            if (!scanRemote(root.id, "", remoteFiles, remoteFolders)) {
                return SyncRunResult(0, 0, 0, 0, emptyList(), true)
            }
            // Initial proof must never alias working caches that move reconciliation refreshes.
            folderIdentities.putAll(remoteFolders)
        } catch (failure: Exception) {
            if (failure is OneDriveReloginRequired) throw failure
            recordFailure(failure)
            return result(0, 0, 0, 0, errors, false)
        }
        if (coordinator.isCancelled) return SyncRunResult(0, 0, 0, 0, emptyList(), true)

        val storedBaseline = when (val loaded = baselineStore?.load(vaultId, root.id, accountId)) {
            null, DriveBaselineLoad.Missing -> emptyMap()
            is DriveBaselineLoad.Present -> loaded.baseline.files
            DriveBaselineLoad.Corrupt -> return SyncRunResult(0, 0, 0, 0, listOf("同步基线损坏，本次同步未修改远端"), false,
                errorDetails = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, reason = SyncErrorReason.BASELINE_FAILED)))
        }
        val baseline = try {
            validateBaseline(storedBaseline, remoteFiles)
        } catch (failure: Exception) {
            if (failure is OneDriveReloginRequired) throw failure
            recordFailure(failure)
            return result(0, 0, 0, 0, errors, coordinator.isCancelled)
        }
        if (coordinator.isCancelled) return SyncRunResult(0, 0, 0, 0, emptyList(), true)

        val localFiles = try { repository.syncFilesStrict().associateBy { it.relativePath } } catch (failure: Exception) {
            recordFailure(if (failure is SecurityException) failure else SyncOperationException(SyncErrorReason.LOCAL_READ_FAILED))
            return result(0, 0, 0, 0, errors, false)
        }
        val localSnapshots = mutableMapOf<String, LocalFileSnapshot>()
        for ((path, file) in localFiles) {
            val digest = LocalFileDigestsCalculator.computeDigests(repository.openSyncInput(file))
                ?: return SyncRunResult(0, 0, 0, 0, listOf("无法读取本地同步文件"), false,
                    errorDetails = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, SyncFailurePolicy.safePath(path), SyncErrorReason.LOCAL_READ_FAILED)))
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
        var deleted = 0
        val completedChangeIds = mutableListOf<String>()
        val total = localFiles.size + remoteFiles.keys.minus(localFiles.keys).size
        var completed = 0

        localFiles.toSortedMap().forEach { (path, local) ->
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
            val remote = remoteFiles[path]
            try {
                when {
                    remote == null -> {
                        val base = baseline[path]
                        val action = SyncDeleteReconciliationPolicy.evaluateLocalOnly(
                            localSha256 = localSha256[path],
                            baseline = base
                        )
                        when (action) {
                            SyncLocalOnlyAction.UPLOAD -> {
                                report(completed, total, "正在上传 $path")
                                upload(path, local, remoteFolders)
                                uploaded++
                            }
                            SyncLocalOnlyAction.MOVE_TO_TRASH -> {
                                val expectedSha256 = base?.localSha256.orEmpty()
                                val trashResult = repository.moveSyncFileToTrashIfUnchanged(local, expectedSha256)
                                when (trashResult) {
                                    is VaultMutationResult.Success -> {
                                        report(completed, total, "远端已删除，本地移入回收站：$path")
                                        localSnapshots.remove(path)
                                        localDigests.remove(path)
                                        deleted++
                                    }
                                    is VaultMutationResult.Failure -> {
                                        if (trashResult.kind == VaultMutationFailureKind.PRECONDITION_FAILED) {
                                            report(completed, total, "检测到本地修改，保留并上传：$path")
                                            upload(path, local, remoteFolders)
                                            val freshDigest = repository.openSyncInput(local)?.let(LocalFileDigestsCalculator::computeDigests)
                                            if (freshDigest != null) {
                                                localDigests[path] = freshDigest
                                                localSnapshots[path] = LocalFileSnapshot(freshDigest, local.document.lastModified, local.document.size)
                                            }
                                            uploaded++
                                        } else {
                                            recordFailure(SyncOperationException(SyncErrorReason.LOCAL_WRITE_FAILED), path)
                                        }
                                    }
                                }
                            }
                        }
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
                            remoteHashSupplier = { independentRemoteDigests(remote, path).md5 }
                        )
                        val remoteHash = decision.remoteHash
                        val remoteRevisionMatches = decision.remoteRevisionMatches
                        when (decision.action) {
                            SyncComparisonAction.UNCHANGED -> unchanged++
                            SyncComparisonAction.UPLOAD_LOCAL_UPDATE -> {
                                report(completed, total, "正在上传本地更新：$path")
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: if (remoteRevisionMatches) remoteHash else independentRemoteDigests(current, path).md5
                                if (current.revision != remote.revision || !currentHash.equals(remoteHash, true)) {
                                    throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
                                }
                                verifyRemoteFile(remote, path)
                                val precondition = DriveMutationPrecondition.requireStrong(revision.etag)
                                repository.openSyncInput(local)?.use { api.replace(remote.id, precondition, local.document.mimeType ?: "application/octet-stream", it) }
                                    ?: throw IllegalStateException("Unable to read local file")
                                uploaded++
                            }
                            SyncComparisonAction.DOWNLOAD_REMOTE_UPDATE -> {
                                report(completed, total, "正在下载远端更新：$path")
                                if (!localMd5[path].equals(repository.syncMd5(path), true)) {
                                    val conflict = conflictPath(path, providerName, conflictStamp())
                                    verifiedDownload(remote, path).use { input ->
                                        if (!repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)) throw IllegalStateException("Unable to preserve concurrent local edit")
                                    }
                                    conflicts++
                                } else {
                                    val written = verifiedDownload(remote, path).use { input -> repository.writeSyncFile(path, remote.mimeType, input, localMd5[path]) }
                                    if (written) {
                                        localDigests.remove(path)
                                        localSnapshots.remove(path)
                                        downloaded++
                                    } else {
                                        val conflict = conflictPath(path, providerName, conflictStamp())
                                        verifiedDownload(remote, path).use { input -> if (!repository.writeSyncFileIfAbsent(conflict, remote.mimeType, input)) throw IllegalStateException("Unable to preserve concurrent local edit") }
                                        conflicts++
                                    }
                                }
                            }
                            SyncComparisonAction.CONFLICT -> {
                                // Confirm destructive replacement can be conditional before creating any conflict copies.
                                DriveMutationPrecondition.requireStrong(api.revision(remote.id).etag)
                                report(completed, total, "发现冲突：$path")
                                val stamp = conflictStamp()
                                val remoteConflictPath = conflictPath(path, providerName, stamp)
                                verifiedDownload(remote, path).use { input ->
                                    val saved = repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                                    if (!saved) throw IllegalStateException("Unable to preserve Drive conflict copy")
                                }
                                val parentId = ensureRemoteFolder(path.substringBeforeLast('/', ""), remoteFolders)
                                verifyRemoteFile(remote, path)
                                ensureRemoteFolder(path.substringBeforeLast('/', ""), remoteFolders)
                                api.copy(remote.id, parentId, remoteConflictPath.substringAfterLast('/'))
                                val revision = api.revision(remote.id)
                                val current = revision.item
                                val currentHash = current.md5 ?: if (remoteRevisionMatches) remoteHash else independentRemoteDigests(current, path).md5
                                if (current.revision != remote.revision || !currentHash.equals(remoteHash, true)) throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
                                verifyRemoteFile(remote, path)
                                val precondition = DriveMutationPrecondition.requireStrong(revision.etag)
                                val input = repository.openSyncInput(local) ?: throw IllegalStateException("Unable to read local file")
                                input.use { api.replace(remote.id, precondition, local.document.mimeType ?: "application/octet-stream", it) }
                                conflicts++
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "File sync failed: ${failure.javaClass.simpleName}")
                if (failure is OneDriveReloginRequired) throw failure
                recordFailure(failure, path)
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)

        sortedChanges.forEachIndexed { changeIdx, change ->
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
            try {
                when (applyCommittedMove(change, changeIdx, sortedChanges, baseline, root, localFiles, remoteFolders)) {
                    MoveApplyResult.COMPLETED -> completedChangeIds += change.id
                    MoveApplyResult.COMPLETED_WITH_CONFLICT -> {
                        completedChangeIds += change.id
                        conflicts++
                    }
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "Apply committed move failed: ${failure.javaClass.simpleName}")
                if (failure is OneDriveReloginRequired) throw failure
                val representativePath = change.sourceToTarget.keys.firstOrNull { it.endsWith(".md", true) }
                    ?: change.sourceToTarget.keys.firstOrNull().orEmpty()
                recordFailure(failure, representativePath)
            }
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)

        remoteFiles.toSortedMap().forEach { (path, remote) ->
            if (localFiles.containsKey(path)) return@forEach
            if (path in moveSources) return@forEach
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
            try {
                val base = baseline[path]
                val action = SyncDeleteReconciliationPolicy.evaluateRemoteOnly(
                    remoteId = remote.id,
                    remoteRevision = remote.revision,
                    remoteVersion = remote.version,
                    remoteMd5 = remote.md5,
                    baseline = base,
                    remoteHashSupplier = { independentRemoteDigests(remote, path).md5 }
                )
                when (action) {
                    SyncRemoteOnlyAction.DOWNLOAD -> {
                        report(completed, total, "正在下载 $path")
                        val downloadedToOriginalPath = verifiedDownload(remote, path).use { input ->
                            repository.writeSyncFileIfAbsent(path, remote.mimeType, input)
                        }
                        if (downloadedToOriginalPath) {
                            downloaded++
                        } else {
                            val remoteConflictPath = conflictPath(path, providerName, conflictStamp())
                            val preserved = verifiedDownload(remote, path).use { input ->
                                repository.writeSyncFileIfAbsent(remoteConflictPath, remote.mimeType, input)
                            }
                            if (!preserved) throw IllegalStateException("Unable to preserve remote conflict copy")
                            conflicts++
                            report(completed, total, "本地文件在同步期间发生变化，已保留冲突副本：$path")
                        }
                    }
                    SyncRemoteOnlyAction.MOVE_TO_TRASH -> {
                        report(completed, total, "本地已删除，远端移入回收站：$path")
                        val revision = api.revision(remote.id)
                        val current = revision.item
                        val currentRevisionMatches = current.revision == remote.revision &&
                            current.id == remote.id &&
                            (current.version == null || remote.version == null || current.version == remote.version) &&
                            (current.md5 == null || current.md5.equals(remote.md5 ?: base?.remoteMd5, true))
                        if (!currentRevisionMatches) {
                            throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
                        }
                        verifyRemoteFile(remote, path)
                        val precondition = DriveMutationPrecondition.requireStrong(revision.etag)
                        api.trash(remote.id, precondition)
                        deleted++
                    }
                }
            } catch (failure: Exception) {
                Log.e("RemoteDriveSync", "Remote item processing failed: ${failure.javaClass.simpleName}")
                if (failure is OneDriveReloginRequired) throw failure
                recordFailure(failure, path)
            }
            completed++
            report(completed, total, "已比较 $completed / $total")
        }
        if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
        val outcome = result(uploaded, downloaded, unchanged, conflicts, errors, false, deleted)
        if (outcome.isSuccessful && baselineStore != null) {
            if (coordinator.isCancelled) return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
            val baselineSaved = try {
                saveBaseline(root, baseline)
            } catch (failure: Exception) {
                if (failure is OneDriveReloginRequired) throw failure
                BaselineSaveResult.FAILED
            }
            if (baselineSaved == BaselineSaveResult.CANCELLED) {
                return result(uploaded, downloaded, unchanged, conflicts, errors, true, deleted)
            }
            if (baselineSaved == BaselineSaveResult.FAILED) return SyncRunResult(uploaded, downloaded, unchanged, conflicts, listOf("无法保存同步基线"), false, deleted,
                listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, reason = SyncErrorReason.BASELINE_FAILED)))
            completedChangeIds.forEach { id ->
                if (changeStore?.acknowledge(id, api.providerId) != true) return SyncRunResult(uploaded, downloaded, unchanged, conflicts, listOf("无法确认本地搬运历史"), false, deleted)
            }
        }
        return outcome
    }

    /** Unproven historical fingerprints must never authorize a deletion or an unchanged shortcut. */
    private fun validateBaseline(
        stored: Map<String, DriveBaselineFile>,
        remoteFiles: Map<String, DriveItem>
    ): Map<String, DriveBaselineFile> = buildMap {
        stored.forEach { (path, base) ->
            if (coordinator.isCancelled) throw SyncTransferCancelled()
            if (base.localMd5 != null && base.remoteMd5 != null && !base.localMd5.equals(base.remoteMd5, true)) {
                return@forEach
            }
            if (base.contentVerified) {
                if (base.localMd5 != null && base.remoteMd5 != null) put(path, base)
                return@forEach
            }
            val remote = remoteFiles[path] ?: return@forEach
            if (!sameRemoteRevision(base, remote)) return@forEach
            val digests = independentRemoteDigests(remote, path)
            val current = api.revision(remote.id).item
            if (current.id != remote.id || current.revision != remote.revision ||
                (current.md5 != null && !current.md5.equals(digests.md5, true))) {
                throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            }
            if (base.localSha256.equals(digests.sha256, true)) {
                put(path, base.copy(localMd5 = digests.md5, remoteMd5 = digests.md5, contentVerified = true))
            }
        }
    }

    private fun sameRemoteRevision(base: DriveBaselineFile, remote: DriveItem): Boolean =
        base.remoteId == remote.id && remote.revision != null &&
            (base.remoteRevision ?: base.remoteVersion?.toString()) == remote.revision

    private fun saveBaseline(root: DriveVaultRoot, previous: Map<String, DriveBaselineFile>): BaselineSaveResult {
        if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
        val local = repository.syncFilesStrict().associateBy { it.relativePath }
        if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
        val remoteFiles = linkedMapOf<String, DriveItem>()
        val folders = linkedMapOf("" to root.id)
        if (!scanRemote(root.id, "", remoteFiles, folders)) return BaselineSaveResult.CANCELLED
        if (folderIdentities.any { (path, id) -> folders[path] != id }) return BaselineSaveResult.FAILED
        if (local.keys != remoteFiles.keys) return BaselineSaveResult.FAILED
        val files = buildMap {
            local.forEach { (path, file) ->
                if (coordinator.isCancelled) return BaselineSaveResult.CANCELLED
                val remote = remoteFiles.getValue(path)
                // SAF timestamps and sizes are not proof: same-size edits can retain both.
                val digests = LocalFileDigestsCalculator.computeDigests(repository.openSyncInput(file))
                    ?: return BaselineSaveResult.FAILED
                val base = previous[path]
                val remoteHash = remote.md5 ?: if (base?.contentVerified == true && sameRemoteRevision(base, remote)) {
                    base.remoteMd5
                } else {
                    val hash = independentRemoteDigests(remote, path).md5
                    val current = api.revision(remote.id).item
                    if (remote.revision == null || current.id != remote.id || current.revision != remote.revision ||
                        (current.md5 != null && !current.md5.equals(hash, true))) return BaselineSaveResult.FAILED
                    hash
                }
                if (remoteHash == null || !digests.md5.equals(remoteHash, true)) return BaselineSaveResult.FAILED
                val sha = digests.sha256
                val localMd5 = digests.md5
                put(path, DriveBaselineFile(
                    path = path,
                    localSha256 = sha,
                    remoteId = remote.id,
                    remoteMd5 = remoteHash,
                    remoteVersion = remote.version,
                    localMd5 = localMd5,
                    localLastModified = file.document.lastModified,
                    localSize = file.document.size,
                    remoteRevision = remote.revision,
                    contentVerified = true
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
        if (folderIdentities.any { (path, id) -> refreshedFolders[path] != id }) {
            throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED)
        }
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
            val remoteHash = remote.md5 ?: independentRemoteDigests(remote, target).md5
            if (!localHash.equals(remoteHash, true)) throw IllegalStateException("Move target verification failed")
        }
        change.sourceToTarget.keys.forEach { source ->
            if (localFiles.containsKey(source)) return@forEach
            val remote = refreshedFiles[source] ?: return@forEach
            val actual = remote.md5 ?: independentRemoteDigests(remote, source).md5
            val base = baseline[source]
            val baselineMatches = if (base == null) {
                val actualSha256 = independentRemoteDigests(remote, source).sha256
                when (MoveAdoptionPolicy.decide(change.beforeSha256[source], actualSha256)) {
                    MoveAdoptionDecision.MISSING_FINGERPRINT -> throw IllegalStateException("Move source has no adoption fingerprint")
                    MoveAdoptionDecision.UNCHANGED_SOURCE -> Unit
                    MoveAdoptionDecision.PRESERVE_CONFLICT -> {
                    val conflict = conflictPath(source, providerName, change.id.take(8))
                    val existingLocalConflict = localFiles[conflict]
                    if (existingLocalConflict == null) {
                        val saved = verifiedDownload(remote, source).use { input ->
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
                        verifyRemoteFile(remote, source)
                        ensureRemoteFolder(conflict.substringBeforeLast('/', ""), folders)
                        api.copy(remote.id, parent, conflict.substringAfterLast('/'))
                    } else {
                        val existingHash = existingRemoteConflict.md5 ?: independentRemoteDigests(existingRemoteConflict, conflict).md5
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
                if (refreshedFiles[conflict] == null) {
                    verifyRemoteFile(remote, source)
                    ensureRemoteFolder(conflict.substringBeforeLast('/', ""), folders)
                    api.copy(remote.id, parent, conflict.substringAfterLast('/'))
                }
                throw IllegalStateException("Move source changed remotely or has no successful baseline")
            }
            val revision = api.revision(remote.id)
            val current = revision.item
            val currentHash = current.md5 ?: independentRemoteDigests(current, source).md5
            if (current.revision != remote.revision || !currentHash.equals(actual, true)) throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, source)
            verifyRemoteFile(remote, source)
            val precondition = DriveMutationPrecondition.requireStrong(revision.etag)
            api.trash(remote.id, precondition)
        }
        val afterFiles = linkedMapOf<String, DriveItem>()
        val afterFolders = linkedMapOf("" to root.id)
        if (!scanRemote(root.id, "", afterFiles, afterFolders)) throw SyncTransferCancelled()
        val trashedSources = change.sourceToTarget.keys.filter { !localFiles.containsKey(it) }
        if (trashedSources.any(afterFiles::containsKey)) throw IllegalStateException("Old $providerName paths are still present")
        return if (preservedAdoptionConflict) MoveApplyResult.COMPLETED_WITH_CONFLICT else MoveApplyResult.COMPLETED
    }

    private enum class MoveApplyResult { COMPLETED, COMPLETED_WITH_CONFLICT }

    private fun verifyRoot() {
        if (coordinator.isCancelled) throw SyncTransferCancelled()
        val root = api.revision(selectedRootId).item
        if (root.id != selectedRootIdentity || !api.isFolder(root)) {
            throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED)
        }
    }

    private fun verifyRemoteFile(expected: DriveItem, path: String): DriveItem {
        verifyRoot()
        if (expected.revision == null) throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) {
            throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
        }
        var parent = selectedRootId
        var prefix = ""
        for ((index, name) in parts.withIndex()) {
            val matches = api.listChildren(parent).filter { it.name == name }
            if (matches.size > 1) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
            val item = matches.singleOrNull() ?: throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            prefix = join(prefix, name)
            if (index < parts.lastIndex) {
                if (!api.isFolder(item) || folderIdentities[prefix] != item.id) {
                    throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
                }
                parent = item.id
            } else if (api.isFolder(item) || item.id != expected.id) {
                throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            }
        }
        val current = api.revision(expected.id).item
        if (current.id != expected.id || current.revision != expected.revision ||
            (expected.md5 != null && current.md5 != null && !expected.md5.equals(current.md5, true))) {
            throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
        }
        return current
    }

    private fun independentRemoteDigests(remote: DriveItem, path: String): LocalFileDigests {
        verifyRemoteFile(remote, path)
        val digests = LocalFileDigestsCalculator.computeDigests(
            CancellationInputStream(api.download(remote)) { coordinator.isCancelled }
        ) ?: throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
        if (remote.md5 != null && !remote.md5.equals(digests.md5, true)) {
            throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
        }
        val current = verifyRemoteFile(remote, path)
        if (current.md5 != null && !current.md5.equals(digests.md5, true)) {
            throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
        }
        sourceDigests[sourceKey(remote, path)] = digests
        return digests
    }

    private fun sourceKey(remote: DriveItem, path: String) = "${remote.id}\u0000${remote.revision}\u0000$path"

    private fun verifiedDownload(remote: DriveItem, path: String): InputStream {
        verifyRemoteFile(remote, path)
        val independent = if (remote.md5 == null) {
            sourceDigests[sourceKey(remote, path)] ?: independentRemoteDigests(remote, path)
        } else null
        val expectedHash = remote.md5 ?: independent!!.md5
        return VerifiedRemoteInputStream(api.download(remote), expectedHash,
            independent?.sha256, path, { coordinator.isCancelled }) {
            val current = verifyRemoteFile(remote, path)
            if (current.md5 != null && !current.md5.equals(expectedHash, true)) {
                throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            }
        }
    }

    private fun scanRemote(
        parentId: String,
        prefix: String,
        files: MutableMap<String, DriveItem>,
        folders: MutableMap<String, String>
    ): Boolean {
        if (coordinator.isCancelled) return false
        val children = api.listChildren(parentId).filter { item ->
            val allowed = remotePathAllowed(join(prefix, item.name), api.isFolder(item)) && (api.isFolder(item) || api.shouldSync(item))
            if (allowed && (item.name.contains('/') || item.name.contains('\\'))) {
                throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, prefix)
            }
            allowed
        }
        if (children.groupBy { it.name }.any { it.value.size > 1 }) {
            throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, prefix)
        }
        children.forEach { item ->
            if (coordinator.isCancelled) return false
            val path = join(prefix, item.name)
            if (!remotePathAllowed(path, api.isFolder(item))) return@forEach
            if (api.isFolder(item)) {
                folders[path] = item.id
                if (!scanRemote(item.id, path, files, folders)) return false
            } else if (api.shouldSync(item)) {
                if (files.put(path, item) != null) {
                    throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
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
        val input = repository.openSyncInput(local) ?: throw SyncOperationException(SyncErrorReason.LOCAL_READ_FAILED, path)
        input.use { api.upload(parentId, path.substringAfterLast('/'), local.document.mimeType ?: "application/octet-stream", it) }
    }

    private fun ensureRemoteFolder(path: String, folders: MutableMap<String, String>): String {
        verifyRoot()
        var parent = selectedRootId
        var prefix = ""
        for (name in path.split('/').filter { it.isNotBlank() }) {
            prefix = join(prefix, name)
            val matches = api.listChildren(parent).filter { it.name == name }
            if (matches.size > 1 || matches.any { !api.isFolder(it) }) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
            val cached = folderIdentities[prefix]
            var item = matches.singleOrNull()
            if (cached != null && item?.id != cached) throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            if (cached == null && item != null) throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED, path)
            if (item == null) {
                val created = api.createFolder(parent, name)
                val confirmed = api.listChildren(parent).filter { it.name == name }
                if (created.name != name || !api.isFolder(created) || confirmed.size != 1 ||
                    confirmed.single().id != created.id || !api.isFolder(confirmed.single())) {
                    throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
                }
                item = confirmed.single()
                folderIdentities[prefix] = item.id
            }
            folders[prefix] = item.id
            parent = item.id
        }
        return parent
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
        uploaded: Int, downloaded: Int, unchanged: Int, conflicts: Int, errors: List<String>, cancelled: Boolean, deleted: Int = 0
    ) = SyncRunResult(uploaded, downloaded, unchanged, conflicts, errors, cancelled, deleted)

}

typealias GoogleDriveSyncService = RemoteDriveSyncEngine
