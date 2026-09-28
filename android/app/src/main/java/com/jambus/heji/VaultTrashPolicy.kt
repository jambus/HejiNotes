package com.jambus.heji

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class StreamDigest(val size: Long, val sha256: String)

object VaultStreamUtils {
    const val BUFFER_SIZE = 32 * 1024

    fun computeDigest(input: InputStream, bufferSize: Int = BUFFER_SIZE): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            totalBytes += read
        }
        return StreamDigest(totalBytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun copyAndDigest(input: InputStream, output: OutputStream, bufferSize: Int = BUFFER_SIZE): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            totalBytes += read
        }
        output.flush()
        return StreamDigest(totalBytes, digest.digest().joinToString("") { "%02x".format(it) })
    }
}

data class TrashCopyTransaction(
    val id: String,
    val sourcePath: String,
    val sourceUri: String,
    val sourceSha256: String,
    val sourceSize: Long,
    val targetTrashName: String,
    val targetUri: String?,
    val stage: Stage
) {
    enum class Stage {
        PREPARED,
        TARGET_CREATED,
        COPIED_VERIFIED,
        SOURCE_DELETED
    }

    fun withStage(next: Stage): TrashCopyTransaction = copy(stage = next)

    fun serialize(): String = listOf(
        id, sourcePath, sourceUri, sourceSha256, sourceSize.toString(), targetTrashName, targetUri.orEmpty(), stage.name
    ).joinToString("\n") { Base64.getUrlEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }

    companion object {
        fun parse(value: String): TrashCopyTransaction? = runCatching {
            val fields = value.split('\n').map { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
            if (fields.size != 8) return null
            TrashCopyTransaction(
                fields[0], fields[1], fields[2], fields[3], fields[4].toLong(), fields[5], fields[6].ifEmpty { null }, Stage.valueOf(fields[7])
            )
        }.getOrNull()
    }
}

object VaultTrashCopyPolicy {
    fun isCopyVerified(sourceSize: Long, sourceSha256: String, copySize: Long, copySha256: String): Boolean =
        sourceSize == copySize && sourceSha256.equals(copySha256, ignoreCase = true)
}

enum class TrashRecoveryAction {
    DELETE_TARGET_AND_MARKER,
    FINALIZE_SOURCE_DELETE,
    DELETE_MARKER_ONLY,
    RETAIN_TRANSACTION
}

enum class SourceState {
    CONFIRMED_DELETED,
    CONFIRMED_INTACT,
    CONFIRMED_MODIFIED,
    UNKNOWN
}

object VaultTrashRecoveryPolicy {
    fun decide(
        stage: TrashCopyTransaction.Stage,
        targetVerified: Boolean,
        sourceState: SourceState
    ): TrashRecoveryAction = when (stage) {
        TrashCopyTransaction.Stage.PREPARED,
        TrashCopyTransaction.Stage.TARGET_CREATED -> TrashRecoveryAction.DELETE_TARGET_AND_MARKER
        TrashCopyTransaction.Stage.COPIED_VERIFIED -> {
            if (targetVerified) {
                when (sourceState) {
                    SourceState.CONFIRMED_DELETED -> TrashRecoveryAction.DELETE_MARKER_ONLY
                    SourceState.CONFIRMED_INTACT -> TrashRecoveryAction.FINALIZE_SOURCE_DELETE
                    SourceState.CONFIRMED_MODIFIED -> TrashRecoveryAction.DELETE_TARGET_AND_MARKER
                    SourceState.UNKNOWN -> TrashRecoveryAction.RETAIN_TRANSACTION
                }
            } else {
                when (sourceState) {
                    SourceState.CONFIRMED_INTACT -> TrashRecoveryAction.DELETE_TARGET_AND_MARKER
                    else -> TrashRecoveryAction.RETAIN_TRANSACTION
                }
            }
        }
        TrashCopyTransaction.Stage.SOURCE_DELETED -> TrashRecoveryAction.DELETE_MARKER_ONLY
    }

    fun decide(
        stage: TrashCopyTransaction.Stage,
        targetVerified: Boolean,
        sourceExists: Boolean,
        sourceMatches: Boolean
    ): TrashRecoveryAction {
        val state = when {
            !sourceExists -> SourceState.CONFIRMED_DELETED
            sourceMatches -> SourceState.CONFIRMED_INTACT
            else -> SourceState.CONFIRMED_MODIFIED
        }
        return decide(stage, targetVerified, state)
    }
}

/** Pure naming for copy-to-trash fallback. */
object VaultTrashPolicy {
    fun uniqueTrashName(original: String, existingNames: Iterable<String>, suffix: String): String {
        if (existingNames.none { VaultNamePolicy.conflictKey(it) == VaultNamePolicy.conflictKey(original) }) return original
        val hasExt = original.contains('.') && !original.startsWith('.')
        val ext = if (hasExt) {
            val rawExt = original.substringAfterLast('.')
            if (rawExt.equals("md", ignoreCase = true)) ".md" else ".$rawExt"
        } else ""
        val stem = if (hasExt) original.dropLast(ext.length) else original
        var attempt = 1
        while (true) {
            val candidate = "$stem-$suffix${if (attempt == 1) "" else "-$attempt"}$ext"
            if (existingNames.none { VaultNamePolicy.conflictKey(it) == VaultNamePolicy.conflictKey(candidate) }) return candidate
            attempt += 1
        }
    }

    fun uniqueNoteName(original: String, existingNames: Iterable<String>, suffix: String): String =
        uniqueTrashName(original, existingNames, suffix)
}
