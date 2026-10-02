package com.jambus.heji

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Opaque identity and strictly enumerated location, supplied by the SAF adapter. */
internal data class SyncDownloadDocument(val id: String, val name: String, val path: String, val directory: Boolean = false)

internal interface SyncDownloadBackend {
    fun find(name: String): SyncDownloadDocument?
    fun create(name: String, mimeType: String?): SyncDownloadDocument
    fun output(document: SyncDownloadDocument): OutputStream
    fun digests(document: SyncDownloadDocument): LocalFileDigests
    fun rename(document: SyncDownloadDocument, name: String): SyncDownloadDocument?
    fun delete(document: SyncDownloadDocument): Boolean
    fun validateLocation() {}
    /** Serializes only the local commit with editor saves, never the network transfer. */
    fun <T> commit(action: () -> T): T = action()
}

/** Keeps uncertain commits out of generic .tmp/.bak recovery and never deletes a possible final file. */
internal object VerifiedSyncDownload {
    fun write(
        backend: SyncDownloadBackend, path: String, mimeType: String?, input: InputStream,
        token: String, onlyIfAbsent: Boolean, expectedCurrentMd5: String? = null
    ): Boolean {
        val name = path.substringAfterLast('/')
        val prefix = path.substringBeforeLast('/', "")
        fun location(n: String) = if (prefix.isEmpty()) n else "$prefix/$n"
        val tempName = ".markbook-sync-$token.pending"
        val backupName = ".markbook-sync-$token.previous"
        var temp: SyncDownloadDocument? = null
        var backup: SyncDownloadDocument? = null
        var originalDigest: LocalFileDigests? = null
        var writtenDigest: LocalFileDigests? = null
        var renameAttempted = false
        var backupAttempted = false

        fun exact(document: SyncDownloadDocument, n: String): Boolean =
            !document.directory && document.name == n && document.path == location(n) &&
                backend.find(n)?.id == document.id

        try {
            val initial = backend.find(name)
            if (onlyIfAbsent && initial != null) return false
            if (initial?.directory == true) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
            if (backend.find(tempName) != null || backend.find(backupName) != null) {
                throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
            }
            val staged = backend.create(tempName, mimeType)
            temp = staged
            if (!exact(staged, tempName)) throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
            val md5 = MessageDigest.getInstance("MD5")
            val sha = MessageDigest.getInstance("SHA-256")
            backend.output(staged).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = try { input.read(buffer) } catch (failure: SyncOperationException) {
                        throw failure
                    } catch (failure: SyncTransferCancelled) {
                        throw failure
                    } catch (_: Exception) {
                        throw SyncOperationException(SyncErrorReason.NETWORK, path)
                    }
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    md5.update(buffer, 0, count)
                    sha.update(buffer, 0, count)
                }
                output.flush()
            }
            fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val streamed = LocalFileDigests(hex(md5), hex(sha))
            (input as? VerifiedRemoteInputStream)?.requireComplete()
            writtenDigest = streamed
            if (backend.digests(staged) != streamed) throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
            return backend.commit {
                (input as? VerifiedRemoteInputStream)?.checkCancellation()
                backend.validateLocation()
                val existing = backend.find(name)
                if (onlyIfAbsent && existing != null) return@commit false
                if (existing?.directory == true) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
                val original = existing?.let(backend::digests)
                if (expectedCurrentMd5 != null &&
                    (original == null || !original.md5.equals(expectedCurrentMd5, true))) return@commit false
                if (existing != null) {
                    originalDigest = original
                    // Never rename someone else's racing file into our private backup name.
                    if (backend.find(backupName) != null) throw SyncOperationException(SyncErrorReason.PATH_AMBIGUOUS, path)
                    backupAttempted = true
                    val savedBackup = backend.rename(existing, backupName)
                        ?: throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                    backup = savedBackup
                    if (!exact(savedBackup, backupName) || backend.digests(savedBackup) != originalDigest) {
                        throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                    }
                }
                if (backend.find(name) != null) throw SyncOperationException(SyncErrorReason.LOCAL_CHANGED, path)
                renameAttempted = true
                val committed = backend.rename(staged, name)
                    ?: throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                if (!exact(committed, name)) throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                if (backend.digests(committed) != streamed) throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
                backend.validateLocation()
                backup?.let {
                    if (!exact(it, backupName) || backend.digests(it) != originalDigest || !backend.delete(it)) {
                        throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                    }
                }
                true
            }
        } catch (failure: Exception) {
            // Restore only after positive absence + known backup identity/hash. Unknown reads preserve everything.
            val retainedBackup = backup
            val retainedTemp = temp
            if (retainedBackup != null) runCatching { backend.commit {
                backend.validateLocation()
                if (retainedTemp != null && backend.find(name) == null && exact(retainedTemp, tempName) &&
                    backend.digests(retainedTemp) == writtenDigest && exact(retainedBackup, backupName) &&
                    backend.digests(retainedBackup) == originalDigest) {
                    val restored = backend.rename(retainedBackup, name)
                    if (restored == null || !exact(restored, name) || backend.digests(restored) != originalDigest) {
                        throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, path)
                    }
                }
            } }
            if (failure is SyncOperationException || failure is SyncTransferCancelled) throw failure
            if (failure is SecurityException) throw SyncOperationException(SyncErrorReason.PERMISSION_DENIED, path)
            throw SyncOperationException(if (renameAttempted || backupAttempted) SyncErrorReason.COMMIT_UNCERTAIN else SyncErrorReason.LOCAL_WRITE_FAILED, path)
        } finally {
            // Before any rename intent this URI cannot have become a committed target.
            if (!renameAttempted && !backupAttempted) temp?.let { document ->
                runCatching {
                    backend.validateLocation()
                    if (exact(document, tempName)) backend.delete(document)
                }
            }
        }
    }
}
