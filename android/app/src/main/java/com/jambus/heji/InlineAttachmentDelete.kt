package com.jambus.heji

import java.net.URLDecoder
import java.util.Base64

data class InlineAttachmentDeleteTransaction(
    val id: String,
    val notePath: String,
    val rawDestination: String,
    val assetPath: String,
    val assetUri: String,
    val assetSha256: String,
    val assetSize: Long,
    val expectedNoteSha256: String,
    val savedNoteSha256: String,
    val stage: Stage
) {
    enum class Stage { PREPARED, NOTE_WRITE_INTENT, NOTE_COMMITTED, REFERENCES_VERIFIED, DELETE_INTENT, DELETED }

    fun withStage(next: Stage): InlineAttachmentDeleteTransaction = copy(stage = next)

    fun serialize(): String = listOf(
        id, notePath, rawDestination, assetPath, assetUri, assetSha256, assetSize.toString(),
        expectedNoteSha256, savedNoteSha256, stage.name
    ).joinToString("\n") { Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    companion object {
        fun parse(value: String): InlineAttachmentDeleteTransaction? = runCatching {
            val fields = value.split('\n')
                .map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            if (fields.size != 10) return null
            InlineAttachmentDeleteTransaction(
                fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
                fields[6].toLong(), fields[7], fields[8], Stage.valueOf(fields[9])
            )
        }.getOrNull()
    }
}

data class DirectAssetDeleteTransaction(
    val id: String,
    val assetPath: String,
    val assetUri: String,
    val assetSha256: String,
    val assetSize: Long,
    val stage: Stage
) {
    enum class Stage { DIRECT_CONFIRMED, REFERENCES_VERIFIED, DELETE_INTENT, DELETED }

    fun withStage(next: Stage): DirectAssetDeleteTransaction = copy(stage = next)

    fun serialize(): String = listOf(id, assetPath, assetUri, assetSha256, assetSize.toString(), stage.name)
        .joinToString("\n") { Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    companion object {
        fun parse(value: String): DirectAssetDeleteTransaction? = runCatching {
            val fields = value.split('\n').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            if (fields.size != 6) return null
            DirectAssetDeleteTransaction(
                fields[0], fields[1], fields[2], fields[3], fields[4].toLong(), Stage.valueOf(fields[5])
            )
        }.getOrNull()
    }
}

object InlineAttachmentReferencePolicy {
    enum class Status { NONE, REFERENCED, AMBIGUOUS }

    fun referenceStatus(markdown: String, noteParent: String, targetPath: String): Status {
        var found = false
        var ambiguous = false
        destinations(markdown).forEach { raw ->
            if (SCHEME.containsMatchIn(raw.trim().removePrefix("<"))) return@forEach
            val resolved = resolve(noteParent, raw)
            when {
                resolved == targetPath -> found = true
                resolved == null && decodedPath(raw).contains(targetPath.substringAfterLast('/')) -> ambiguous = true
            }
        }
        if (found) return Status.REFERENCED
        var residual = markdown
        listOf(INLINE, DEFINITION, WIKI, HTML).forEach { residual = it.replace(residual, "") }
        residual = EXTERNAL.replace(residual, "")
        val decodedResidual = decodedPath(residual)
        if (decodedResidual.contains(targetPath) || decodedResidual.contains(targetPath.substringAfterLast('/'))) {
            ambiguous = true
        }
        return if (ambiguous) Status.AMBIGUOUS else Status.NONE
    }

    fun resolve(noteParent: String, rawDestination: String): String? {
        val path = decodedPath(rawDestination.trim().removePrefix("<").removeSuffix(">"))
            .substringBefore('?').substringBefore('#')
            .replace("\\(", "(").replace("\\)", ")")
        return VaultRelativePath.resolveFromNoteParent(noteParent, path)
    }

    private fun destinations(markdown: String): Sequence<String> = sequence {
        INLINE.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[3] else it.groupValues[4]) }
        DEFINITION.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3]) }
        WIKI.findAll(markdown).forEach { yield(it.groupValues[2]) }
        HTML.findAll(markdown).forEach { yield(it.groupValues[2]) }
    }

    private fun decodedPath(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private val INLINE = Regex("(!?\\[[^]]*]\\()(?:(<)([^>]+)>|((?:\\\\.|[^\\s)])+))(\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?(\\))")
    private val DEFINITION = Regex("(?m)^(\\s*\\[[^]]+]\\s*:\\s*)(?:<([^>]+)>|([^\\s]+))(.*)$")
    private val WIKI = Regex("(!?\\[\\[)([^|\\]#]+)([^]]*)]]")
    private val HTML = Regex("((?:src|href)\\s*=\\s*[\"'])([^\"']+)([\"'])", RegexOption.IGNORE_CASE)
    private val EXTERNAL = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}

object InlineAttachmentDeleteRecoveryPolicy {
    fun mayDelete(stage: InlineAttachmentDeleteTransaction.Stage): Boolean =
        stage in setOf(
            InlineAttachmentDeleteTransaction.Stage.NOTE_COMMITTED,
            InlineAttachmentDeleteTransaction.Stage.REFERENCES_VERIFIED,
            InlineAttachmentDeleteTransaction.Stage.DELETE_INTENT,
            InlineAttachmentDeleteTransaction.Stage.DELETED
        )

    fun shouldScanRootDirectory(name: String): Boolean = name !in setOf(".obsidian", ".markbook")
}

object AttachmentDeleteMarkerPolicy {
    fun <T> parseDurable(payload: String?, parser: (String) -> T?): T? = payload?.let(parser)
}
