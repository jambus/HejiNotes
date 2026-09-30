package com.jambus.heji

import java.net.URLDecoder

data class AssetFolderDeleteNode(
    val path: String,
    val uri: String,
    val directory: Boolean,
    val size: Long,
    val sha256: String
)

data class AssetFolderDeleteSnapshot(
    val vaultUri: String,
    val folderPath: String,
    val folderUri: String,
    val manifest: List<AssetFolderDeleteNode>
) {
    val directoryCount: Int get() = manifest.count { it.directory && it.path != folderPath }
    val fileCount: Int get() = manifest.count { !it.directory }
    val totalSize: Long get() = manifest.filter { !it.directory && it.size > 0L }.sumOf { it.size }
}

sealed class AssetFolderDeleteResult {
    data class Ready(val snapshot: AssetFolderDeleteSnapshot) : AssetFolderDeleteResult()
    object Deleted : AssetFolderDeleteResult()
    data class Rejected(val message: String) : AssetFolderDeleteResult()
}

interface AssetFolderDeletePort {
    data class Node(val path: String, val uri: String, val directory: Boolean, val size: Long?)
    data class Markdown(val path: String, val body: String)
    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    fun currentVaultUri(): String?
    fun safeToDelete(): Boolean
    fun resolve(path: String): Node?
    fun children(directory: Node): List<Node>
    fun sha256(file: Node): String?
    fun markdownFiles(): List<Markdown>
    fun deleteWholeFolder(folderUri: String): Boolean
    fun presence(path: String): Presence
}

/** Ephemeral, fail-closed two-phase deletion. No state from this operation is persisted. */
class AssetFolderDeleteOperation(private val port: AssetFolderDeletePort) {
    fun prepare(folderPath: String): AssetFolderDeleteResult {
        if (!port.safeToDelete()) return rejected("存在尚未安全完成的保存或媒体操作")
        val normalized = normalizedPath(folderPath) ?: return rejected("附件目录路径不明确")
        if (!VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder(normalized)) {
            return rejected("只能永久删除 assets 或 attachments 内的文件夹")
        }
        val vault = port.currentVaultUri()?.takeIf { it.isNotBlank() } ?: return rejected("无法访问 Vault")
        val folder = try { port.resolve(normalized) } catch (_: Exception) { null }
            ?: return rejected("附件目录已不存在或无法读取")
        if (!folder.directory || folder.path != normalized || forbidden(folder.path)) {
            return rejected("附件目录状态不安全")
        }
        val manifest = manifest(folder) ?: return rejected("无法完整读取附件目录")
        when (references(normalized, manifest)) {
            ReferenceState.REFERENCED -> return rejected("Markdown 仍引用此目录或其中附件")
            ReferenceState.AMBIGUOUS -> return rejected("存在无法安全确认的 Markdown 引用")
            ReferenceState.UNREADABLE -> return rejected("部分 Vault 内容无法读取")
            ReferenceState.NONE -> Unit
        }
        if (port.currentVaultUri() != vault || !port.safeToDelete()) return rejected("Vault 或保存状态已变化")
        return AssetFolderDeleteResult.Ready(AssetFolderDeleteSnapshot(vault, normalized, folder.uri, manifest))
    }

    fun commit(snapshot: AssetFolderDeleteSnapshot): AssetFolderDeleteResult {
        if (!port.safeToDelete() || port.currentVaultUri() != snapshot.vaultUri) return rejected("Vault 或保存状态已变化，请重新确认")
        if (snapshot.manifest != manifestFor(snapshot.folderPath)) return rejected("目录内容已变化，请重新确认")
        if (references(snapshot.folderPath, snapshot.manifest) != ReferenceState.NONE) return rejected("目录被引用或 Vault 无法完整读取")
        if (snapshot.manifest != manifestFor(snapshot.folderPath)) return rejected("目录内容已变化，请重新确认")
        if (references(snapshot.folderPath, snapshot.manifest) != ReferenceState.NONE) return rejected("删除前发现引用或无法完整复核 Vault")
        if (!port.safeToDelete() || port.currentVaultUri() != snapshot.vaultUri) return rejected("删除前保存或 Vault 状态已变化，请重新确认")
        val invoked = try { port.deleteWholeFolder(snapshot.folderUri) } catch (_: Exception) { false }
        return when (try { port.presence(snapshot.folderPath) } catch (_: Exception) { AssetFolderDeletePort.Presence.UNKNOWN }) {
            AssetFolderDeletePort.Presence.ABSENT -> AssetFolderDeleteResult.Deleted
            AssetFolderDeletePort.Presence.PRESENT -> rejected(
                if (invoked) "目录删除失败，请重新确认后重试" else "Provider 未确认删除，目录已保留"
            )
            AssetFolderDeletePort.Presence.UNKNOWN -> rejected("无法确认删除结果，请检查 Vault 后重试")
        }
    }

    private fun manifestFor(path: String): List<AssetFolderDeleteNode>? {
        val folder = try { port.resolve(path) } catch (_: Exception) { null } ?: return null
        if (!folder.directory || folder.path != path || forbidden(folder.path)) return null
        return manifest(folder)
    }

    private fun manifest(root: AssetFolderDeletePort.Node): List<AssetFolderDeleteNode>? = try {
        val result = mutableListOf<AssetFolderDeleteNode>()
        fun visit(node: AssetFolderDeletePort.Node) {
            if (forbidden(node.path)) throw IllegalStateException("protected descendant")
            if (node.path != root.path && !node.path.startsWith(root.path + "/")) throw IllegalStateException("ambiguous descendant")
            if (node.directory) {
                result += AssetFolderDeleteNode(node.path, node.uri, true, node.size ?: -1L, "")
                port.children(node).sortedBy { it.path }.forEach(::visit)
            } else {
                val hash = port.sha256(node) ?: throw IllegalStateException("unreadable file")
                result += AssetFolderDeleteNode(node.path, node.uri, false, node.size ?: -1L, hash)
            }
        }
        visit(root)
        result.sortedWith(compareBy<AssetFolderDeleteNode> { it.path }.thenBy { it.uri })
    } catch (_: Exception) { null }

    private enum class ReferenceState { NONE, REFERENCED, AMBIGUOUS, UNREADABLE }

    private fun references(folderPath: String, manifest: List<AssetFolderDeleteNode>): ReferenceState {
        return try {
        val folderPrefix = "$folderPath/"
        val fileNodes = manifest.filter { !it.directory }
        val fileBasenames = fileNodes.map { it.path.substringAfterLast('/') }.filter { it.isNotBlank() }.toSet()
        val allManifestPaths = manifest.map { it.path }.toSet()

        for (markdown in port.markdownFiles()) {
            val noteParent = markdown.path.substringBeforeLast('/', "")
            val body = markdown.body

            // 1. Direct and ambiguous links in destination syntax
            for (raw in destinations(body)) {
                if (SCHEME.containsMatchIn(raw.trim().removePrefix("<"))) continue
                val rawPath = raw.trim().removePrefix("<").removeSuffix(">")
                    .substringBefore('?').substringBefore('#')
                val localPath = decoded(rawPath)
                    .replace("\\(", "(").replace("\\)", ")")
                val resolved = VaultRelativePath.resolveFromNoteParent(noteParent, localPath)
                if (resolved != null) {
                    if (resolved == folderPath || resolved.startsWith(folderPrefix) || resolved in allManifestPaths) {
                        return ReferenceState.REFERENCED
                    }
                } else {
                    val decoded = decoded(raw)
                    if (decoded.contains(folderPath) || fileBasenames.any { decoded.contains(it) }) {
                        return ReferenceState.AMBIGUOUS
                    }
                }
            }

            // 2. Conservative WikiLink basename and folder matches
            for (wikiMatch in WIKI.findAll(body)) {
                val rawTarget = decoded(wikiMatch.groupValues[2]).trim()
                val targetClean = rawTarget.substringBefore('|').substringBefore('#').trim()
                if (targetClean == folderPath || targetClean.startsWith(folderPrefix)) {
                    return ReferenceState.REFERENCED
                }
                val targetLeaf = targetClean.substringAfterLast('/')
                if (targetLeaf in fileBasenames) {
                    return ReferenceState.REFERENCED
                }
            }

            // 3. Residual text scan (after stripping link syntax) for embedded code or raw paths
            var residual = body
            listOf(INLINE, DEFINITION, WIKI, HTML).forEach { residual = it.replace(residual, "") }
            residual = EXTERNAL.replace(residual, "")
            val decodedResidual = decoded(residual)

            // For folder path: nested paths have '/', while root segments like 'assets' check 'assets/'
            // to prevent false positives on common words in prose
            val folderPattern = if (folderPath.contains('/')) folderPath else "$folderPath/"
            if (decodedResidual.contains(folderPattern)) {
                return ReferenceState.AMBIGUOUS
            }

            if (fileBasenames.any { decodedResidual.contains(it) }) {
                return ReferenceState.AMBIGUOUS
            }
        }
        ReferenceState.NONE
    } catch (_: Exception) {
            ReferenceState.UNREADABLE
        }
    }

    private fun destinations(markdown: String): Sequence<String> = sequence {
        INLINE.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[3] else it.groupValues[4]) }
        DEFINITION.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3]) }
        WIKI.findAll(markdown).forEach { yield(it.groupValues[2]) }
        HTML.findAll(markdown).forEach { yield(it.groupValues[2]) }
    }

    private fun forbidden(path: String): Boolean = path.split('/').any {
        it.isBlank() || it == "." || it == ".." || it.startsWith('.') || it.startsWith(".markbook-")
    }

    private fun normalizedPath(path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.endsWith('/')) return null
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun rejected(message: String) = AssetFolderDeleteResult.Rejected(message)

    private fun decoded(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    companion object {
        private val INLINE = Regex("(!?\\[[^]]*]\\()(?:(<)([^>]+)>|((?:\\\\.|[^\\s)])+))(\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?(\\))")
        private val DEFINITION = Regex("(?m)^(\\s*\\[[^]]+]\\s*:\\s*)(?:<([^>]+)>|([^\\s]+))(.*)$")
        private val WIKI = Regex("(!?\\[\\[)([^|\\]#]+)([^]]*)]]")
        private val HTML = Regex("((?:src|href)\\s*=\\s*[\"'])([^\"']+)([\"'])", RegexOption.IGNORE_CASE)
        private val EXTERNAL = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}
