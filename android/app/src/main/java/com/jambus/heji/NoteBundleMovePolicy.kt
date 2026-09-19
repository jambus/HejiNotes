package com.jambus.heji

import java.net.URLDecoder

/** Conservative Markdown path handling for a note-with-assets move. */
object NoteBundleMovePolicy {
    enum class ReferenceStatus { NONE, REFERENCES_BUNDLE, AMBIGUOUS }

    sealed class ResolvedSourceBundle {
        data class Found(val bundlePath: String) : ResolvedSourceBundle()
        object None : ResolvedSourceBundle()
        object AmbiguousReference : ResolvedSourceBundle()
        object MultipleReferencedBundles : ResolvedSourceBundle()
    }

    fun bundlePath(noteRelativePath: String): String = join(noteRelativePath.substringBeforeLast('/', ""), "assets/${noteRelativePath.substringAfterLast('/').removeSuffix(".md")}")
    fun legacyBundlePath(noteRelativePath: String): String = "assets/${noteRelativePath.substringAfterLast('/').removeSuffix(".md")}"

    fun resolveSourceBundle(
        noteRelativePath: String,
        markdown: String,
        colocatedExists: Boolean,
        legacyExists: Boolean
    ): ResolvedSourceBundle {
        val colocated = bundlePath(noteRelativePath)
        val legacy = legacyBundlePath(noteRelativePath)
        val noteParent = noteRelativePath.substringBeforeLast('/', "")

        if (colocated == legacy) {
            if (!colocatedExists) return ResolvedSourceBundle.None
            return when (referenceStatus(markdown, noteParent, colocated)) {
                ReferenceStatus.AMBIGUOUS -> ResolvedSourceBundle.AmbiguousReference
                else -> ResolvedSourceBundle.Found(colocated)
            }
        }

        val colocatedRef = referenceStatus(markdown, noteParent, colocated)
        val legacyRef = referenceStatus(markdown, noteParent, legacy)

        if (colocatedRef == ReferenceStatus.AMBIGUOUS || legacyRef == ReferenceStatus.AMBIGUOUS) {
            return ResolvedSourceBundle.AmbiguousReference
        }

        val colocatedReferenced = colocatedExists && colocatedRef == ReferenceStatus.REFERENCES_BUNDLE
        val legacyReferenced = legacyExists && legacyRef == ReferenceStatus.REFERENCES_BUNDLE

        return when {
            colocatedReferenced && legacyReferenced ->
                ResolvedSourceBundle.MultipleReferencedBundles
            colocatedReferenced ->
                ResolvedSourceBundle.Found(colocated)
            legacyReferenced ->
                ResolvedSourceBundle.Found(legacy)
            colocatedExists ->
                ResolvedSourceBundle.Found(colocated)
            else ->
                ResolvedSourceBundle.None
        }
    }

    fun referenceStatus(markdown: String, noteParent: String, bundlePath: String): ReferenceStatus {
        if (bundlePath.isBlank()) return ReferenceStatus.NONE
        var found = false
        destinations(markdown).forEach { destination ->
            when (belongsToBundle(destination, noteParent, bundlePath)) {
                true -> found = true
                null -> return ReferenceStatus.AMBIGUOUS
                false -> Unit
            }
        }
        var residual = markdown
        listOf(INLINE, DEFINITION, WIKI, HTML).forEach { residual = it.replace(residual, "") }
        residual = EXTERNAL.replace(residual, "")
        val needle = "assets/${bundlePath.substringAfterLast('/')}"
        if (decodePath(residual).contains(needle)) return ReferenceStatus.AMBIGUOUS
        return if (found) ReferenceStatus.REFERENCES_BUNDLE else ReferenceStatus.NONE
    }

    fun isBundleReference(markdown: String, noteParent: String, bundlePath: String): Boolean =
        bundlePath.isNotBlank() && referenceStatus(markdown, noteParent, bundlePath) != ReferenceStatus.NONE

    fun rewriteBundleReferences(markdown: String, sourceParent: String, destinationParent: String, sourceBundle: String, destinationBundle: String): String {
        if (referenceStatus(markdown, sourceParent, sourceBundle) == ReferenceStatus.AMBIGUOUS) throw IllegalArgumentException("Ambiguous attachment reference")
        var unsafe = false
        fun replacement(raw: String): String? {
            when (belongsToBundle(raw, sourceParent, sourceBundle)) {
                null -> { unsafe = true; return null }
                false -> return null
                true -> Unit
            }
            val (path, suffix) = splitSuffix(raw)
            val decoded = decodePath(path).replace("\\(", "(").replace("\\)", ")")
            val resolved = normalize(join(sourceParent, decoded)) ?: run { unsafe = true; return null }
            val source = normalize(sourceBundle) ?: run { unsafe = true; return null }
            return relative(destinationParent, join(destinationBundle, resolved.removePrefix(source).removePrefix("/"))) + suffix
        }
        var result = INLINE.replace(markdown) { m ->
            val raw = if (m.groupValues[2].isNotEmpty()) m.groupValues[3] else m.groupValues[4]
            replacement(raw)?.let { target ->
                val encoded = if (m.groupValues[2].isNotEmpty() || target.any { it.isWhitespace() || it == '(' || it == ')' }) "<$target>" else target
                m.groupValues[1] + encoded + m.groupValues[5] + m.groupValues[6]
            } ?: m.value
        }
        result = DEFINITION.replace(result) { m ->
            val raw = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            replacement(raw)?.let { target ->
                val encoded = if (m.groupValues[2].isNotEmpty()) "<$target>" else target
                m.groupValues[1] + encoded + m.groupValues[4]
            } ?: m.value
        }
        result = WIKI.replace(result) { m -> replacement(m.groupValues[2])?.let { m.groupValues[1] + it + m.groupValues[3] + "]]" } ?: m.value }
        result = HTML.replace(result) { m -> replacement(m.groupValues[2])?.let { m.groupValues[1] + it + m.groupValues[3] } ?: m.value }
        if (unsafe || referenceStatus(result, destinationParent, destinationBundle) == ReferenceStatus.AMBIGUOUS) throw IllegalArgumentException("Unsupported or unsafe attachment reference")
        return result
    }

    private fun destinations(markdown: String): Sequence<String> = sequence {
        INLINE.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[3] else it.groupValues[4]) }
        DEFINITION.findAll(markdown).forEach { yield(if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3]) }
        WIKI.findAll(markdown).forEach { yield(it.groupValues[2]) }
        HTML.findAll(markdown).forEach { yield(it.groupValues[2]) }
    }

    private fun belongsToBundle(destination: String, parent: String, bundle: String): Boolean? {
        val rawPath = decodePath(splitSuffix(destination).first).replace("\\(", "(").replace("\\)", ")")
        if (rawPath.isBlank() || rawPath.startsWith('/') || rawPath.contains("://") || rawPath.startsWith("#")) return false
        val resolved = normalize(join(parent, rawPath)) ?: return if (rawPath.contains("assets/")) null else false
        val clean = normalize(bundle) ?: return null
        return resolved == clean || resolved.startsWith("$clean/")
    }

    private fun splitSuffix(value: String): Pair<String, String> { val index = value.indexOfFirst { it == '?' || it == '#' }; return if (index < 0) value to "" else value.substring(0, index) to value.substring(index) }
    private fun relative(from: String, to: String): String { val a = normalize(from)!!.split('/').filter { it.isNotEmpty() }; val b = normalize(to)!!.split('/').filter { it.isNotEmpty() }; var i = 0; while (i < a.size && i < b.size && a[i] == b[i]) i++; return (List(a.size - i) { ".." } + b.drop(i)).joinToString("/") }
    private fun normalize(value: String): String? { val parts = mutableListOf<String>(); value.split('/').forEach { part -> when (part) { "", "." -> Unit; ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex); else -> parts += part } }; return parts.joinToString("/") }
    private fun join(left: String, right: String): String = listOf(left, right).filter { it.isNotBlank() }.joinToString("/")
    private fun decodePath(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    private val INLINE = Regex("(!?\\[[^]]*]\\()(?:(<)([^>]+)>|((?:\\\\.|[^\\s)])+))(\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?(\\))")
    private val DEFINITION = Regex("(?m)^(\\s*\\[[^]]+]\\s*:\\s*)(?:<([^>]+)>|([^\\s]+))(.*)$")
    private val WIKI = Regex("(!?\\[\\[)([^|\\]#]+)([^]]*)]]")
    private val HTML = Regex("((?:src|href)\\s*=\\s*[\"'])([^\"']+)([\"'])", RegexOption.IGNORE_CASE)
    private val EXTERNAL = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")
}
