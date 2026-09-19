package com.jambus.heji

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.security.SecureRandom
import java.security.MessageDigest

data class VaultDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val parentUri: Uri? = null,
    val relativePath: String = "",
    val lastModified: Long? = null,
    val size: Long? = null
) {
    val parentRelativePath: String
        get() = relativePath.substringBeforeLast('/', "")
}

data class PhotoAttachments(
    val original: String,
    val corrected: String,
    val relativeDirectory: String,
    val transactionUri: Uri
)

enum class PhotoPostSaveAction { CONFIRM, ROLLBACK, RETAIN_FOR_RECOVERY }

object PhotoCommitPolicy {
    fun bodySha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun canBeginCommit(expectedBodyHash: String?, currentBody: String?): Boolean =
        expectedBodyHash != null && currentBody != null && bodySha256(currentBody) == expectedBodyHash

    fun afterSave(rereadBody: String?, exactLink: String): PhotoPostSaveAction = when {
        rereadBody == null -> PhotoPostSaveAction.RETAIN_FOR_RECOVERY
        rereadBody.contains(exactLink) -> PhotoPostSaveAction.CONFIRM
        else -> PhotoPostSaveAction.ROLLBACK
    }

    fun insertPhotoAtCapturePoint(base: String, imageLink: String): String {
        val insertion = "\n\n$imageLink\n\n"
        val content = if (base.contains(MarkdownCodec.CARET_MARKER)) {
            base.replace(MarkdownCodec.CARET_MARKER, insertion).trimEnd() + "\n"
        } else {
            base.trimEnd() + insertion
        }
        return if (base.replace(MarkdownCodec.CARET_MARKER, "").isBlank()) {
            content.trimStart()
        } else {
            content
        }
    }

    data class RawLine(
        val start: Int,
        val end: Int,
        val endWithNewline: Int,
        val text: String
    )

    fun splitRawLines(raw: String): List<RawLine> {
        if (raw.isEmpty()) return emptyList()
        val lines = mutableListOf<RawLine>()
        var idx = 0
        val len = raw.length
        while (idx < len) {
            val start = idx
            while (idx < len && raw[idx] != '\r' && raw[idx] != '\n') {
                idx++
            }
            val end = idx
            if (idx < len && raw[idx] == '\r') {
                idx++
            }
            if (idx < len && raw[idx] == '\n') {
                idx++
            }
            val endWithNewline = idx
            lines.add(RawLine(start, end, endWithNewline, raw.substring(start, end)))
        }
        return lines
    }

    private fun isTableDelimiterLine(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.contains('|')) return false
        val cells = trimmed.split('|').map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
        if (cells.isEmpty()) return false
        return cells.all { it.matches(Regex("^:?-+:?$")) }
    }

    private fun findTableRanges(lines: List<RawLine>): List<IntRange> {
        val tableRanges = mutableListOf<IntRange>()
        var i = 1
        while (i < lines.size) {
            if (isTableDelimiterLine(lines[i].text) && lines[i - 1].text.contains('|')) {
                val tableStart = lines[i - 1].start
                var endLine = i
                var j = i + 1
                while (j < lines.size && lines[j].text.trim().isNotEmpty() && lines[j].text.contains('|')) {
                    endLine = j
                    j++
                }
                val tableEnd = lines[endLine].endWithNewline
                tableRanges.add(tableStart..tableEnd)
                i = j
            } else {
                i++
            }
        }
        return tableRanges
    }

    private fun findCodeBlockRanges(lines: List<RawLine>, rawLength: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var inFence = false
        var fenceChar = ' '
        var fenceLength = 0
        var fenceStart = 0

        for (line in lines) {
            val trimmedLeading = line.text.trimStart()
            val indent = line.text.length - trimmedLeading.length
            if (indent <= 3) {
                if (!inFence) {
                    val backticks = trimmedLeading.takeWhile { it == '`' }
                    val tildes = trimmedLeading.takeWhile { it == '~' }
                    if (backticks.length >= 3) {
                        inFence = true
                        fenceChar = '`'
                        fenceLength = backticks.length
                        fenceStart = line.start
                    } else if (tildes.length >= 3) {
                        inFence = true
                        fenceChar = '~'
                        fenceLength = tildes.length
                        fenceStart = line.start
                    }
                } else {
                    val matchingChars = trimmedLeading.takeWhile { it == fenceChar }
                    val rest = trimmedLeading.substring(matchingChars.length).trim()
                    if (matchingChars.length >= fenceLength && rest.isEmpty()) {
                        ranges.add(fenceStart..line.endWithNewline)
                        inFence = false
                    }
                }
            }
        }
        if (inFence) {
            ranges.add(fenceStart..rawLength)
        }
        return ranges
    }

    private fun findFrontMatterRange(lines: List<RawLine>): IntRange? {
        if (lines.isEmpty()) return null
        if (lines[0].text.trim() != "---") return null
        for (i in 1 until lines.size) {
            if (lines[i].text.trim() == "---") {
                return 0..lines[i].endWithNewline
            }
        }
        return null
    }

    private fun findInlineCodeRanges(lineText: String, lineStart: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < lineText.length) {
            if (lineText[i] == '`') {
                val fenceStart = i
                while (i < lineText.length && lineText[i] == '`') {
                    i++
                }
                val fenceLen = i - fenceStart
                val fenceStr = "`".repeat(fenceLen)
                val closeIdx = lineText.indexOf(fenceStr, i)
                if (closeIdx != -1) {
                    val end = closeIdx + fenceLen
                    ranges.add((lineStart + fenceStart)..(lineStart + end))
                    i = end
                }
            } else {
                i++
            }
        }
        return ranges
    }

    private val linkRegex = Regex("""!?\[[^\]]*\]\([^)]*\)""")
    private val htmlTagOrCommentRegex = Regex("""<!--.*?-->|<[a-zA-Z/][^>]*>""")
    private val atxHeadingRegex = Regex("""^[ ]{0,3}#{1,6}(\s.*|$)""")
    private val setextHeadingRegex = Regex("""^[ ]{0,3}(=+|-+)[ ]*$""")

    private fun interpolateWhitespace(
        normGap: String,
        rawGap: String,
        cursorInGap: Int,
        rawStart: Int,
        rawEnd: Int
    ): Int {
        if (rawGap.isEmpty() || rawStart >= rawEnd) return rawStart
        if (normGap.isEmpty()) return rawStart

        val clampedCursor = cursorInGap.coerceIn(0, normGap.length)
        if (clampedCursor == 0) return rawStart
        if (clampedCursor == normGap.length) return rawEnd

        val normNls = mutableListOf<Int>()
        for (i in normGap.indices) {
            if (normGap[i] == '\n') normNls.add(i)
        }

        val rawNls = mutableListOf<Int>()
        for (j in rawGap.indices) {
            if (rawGap[j] == '\n') rawNls.add(j)
        }

        if (normNls.isEmpty()) {
            if (rawNls.isEmpty()) {
                val ratio = clampedCursor.toDouble() / normGap.length
                val rawOffsetInGap = (ratio * rawGap.length).toInt()
                return (rawStart + rawOffsetInGap).coerceIn(rawStart, rawEnd)
            } else {
                val firstNl = rawNls.first()
                val spaceLimit = if (firstNl > 0 && rawGap[firstNl - 1] == '\r') firstNl - 1 else firstNl
                return (rawStart + minOf(clampedCursor, spaceLimit)).coerceIn(rawStart, rawEnd)
            }
        }

        if (rawNls.isEmpty()) {
            val ratio = clampedCursor.toDouble() / normGap.length
            return (rawStart + (ratio * rawGap.length).toInt()).coerceIn(rawStart, rawEnd)
        }

        val firstNormNl = normNls.first()
        val lastNormNl = normNls.last()

        if (clampedCursor <= firstNormNl) {
            val firstRawNl = rawNls.first()
            val rawTrailingBeforeNl = if (firstRawNl > 0 && rawGap[firstRawNl - 1] == '\r') firstRawNl - 1 else firstRawNl
            return (rawStart + minOf(clampedCursor, rawTrailingBeforeNl)).coerceIn(rawStart, rawEnd)
        }

        if (clampedCursor > lastNormNl) {
            val distFromEnd = normGap.length - clampedCursor
            val lastRawNl = rawNls.last()
            val rawLeadingAfterNl = rawGap.length - (lastRawNl + 1)
            val offsetFromEnd = minOf(distFromEnd, rawLeadingAfterNl)
            return (rawEnd - offsetFromEnd).coerceIn(rawStart, rawEnd)
        }

        val normNlsBefore = normGap.substring(0, clampedCursor).count { it == '\n' }
        val rawTargetNlIdx = (normNlsBefore - 1).coerceIn(0, rawNls.size - 1)
        val rawNlPos = rawNls[rawTargetNlIdx]
        return (rawStart + rawNlPos + 1).coerceIn(rawStart, rawEnd)
    }

    fun mapNormalizedOffsetToRaw(raw: String, normalizedOffset: Int, normalized: String? = null): Int {
        if (normalizedOffset <= 0) return 0
        if (raw.isEmpty()) return 0
        if (normalized == null || normalized.isEmpty()) {
            var norm = 0
            var rawIndex = 0
            while (rawIndex < raw.length && norm < normalizedOffset) {
                if (raw.startsWith("\r\n", rawIndex)) {
                    rawIndex += 2
                } else {
                    rawIndex += 1
                }
                norm += 1
            }
            return rawIndex.coerceIn(0, raw.length)
        }

        val targetNorm = normalizedOffset.coerceIn(0, normalized.length)
        if (targetNorm == 0) return 0
        if (targetNorm == normalized.length) return raw.length

        val normNonWs = mutableListOf<Int>()
        for (i in normalized.indices) {
            if (!normalized[i].isWhitespace()) {
                normNonWs.add(i)
            }
        }

        val rawNonWs = mutableListOf<Int>()
        for (j in raw.indices) {
            if (!raw[j].isWhitespace()) {
                rawNonWs.add(j)
            }
        }

        if (normNonWs.isEmpty()) {
            return 0
        }
        if (rawNonWs.isEmpty()) {
            return raw.length
        }

        val normToRaw = IntArray(normNonWs.size) { -1 }
        var rIdx = 0
        var nIdx = 0
        while (nIdx < normNonWs.size && rIdx < rawNonWs.size) {
            val nChar = normalized[normNonWs[nIdx]]
            val rChar = raw[rawNonWs[rIdx]]
            if (nChar == rChar) {
                normToRaw[nIdx] = rIdx
                nIdx++
                rIdx++
            } else {
                val maxLookahead = 25
                var foundN = -1
                var foundR = -1
                outer@ for (dist in 1..maxLookahead) {
                    for (di in 0..dist) {
                        val dj = dist - di
                        val ni = nIdx + di
                        val rj = rIdx + dj
                        if (ni < normNonWs.size && rj < rawNonWs.size &&
                            normalized[normNonWs[ni]] == raw[rawNonWs[rj]]) {
                            foundN = ni
                            foundR = rj
                            break@outer
                        }
                    }
                }
                if (foundN != -1 && foundR != -1) {
                    nIdx = foundN
                    rIdx = foundR
                    normToRaw[nIdx] = rIdx
                    nIdx++
                    rIdx++
                } else {
                    nIdx++
                }
            }
        }

        if (targetNorm <= normNonWs.first()) {
            val firstMatchedRaw = normToRaw.firstOrNull { it != -1 } ?: 0
            val rawLimit = rawNonWs[firstMatchedRaw]
            val normGap = normalized.substring(0, normNonWs.first())
            val rawGap = raw.substring(0, rawLimit)
            return interpolateWhitespace(normGap, rawGap, targetNorm, 0, rawLimit)
        }

        if (targetNorm > normNonWs.last()) {
            val lastMatchedNorm = normToRaw.indexOfLast { it != -1 }
            val lastMatchedRaw = if (lastMatchedNorm != -1) normToRaw[lastMatchedNorm] else rawNonWs.size - 1
            val rawStart = rawNonWs[lastMatchedRaw] + 1
            val normGap = normalized.substring(normNonWs.last() + 1)
            val rawGap = if (rawStart <= raw.length) raw.substring(rawStart) else ""
            val cursorInGap = targetNorm - (normNonWs.last() + 1)
            return interpolateWhitespace(normGap, rawGap, cursorInGap, rawStart, raw.length)
        }

        var low = 0
        var high = normNonWs.size - 1
        var k = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (normNonWs[mid] < targetNorm) {
                k = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (targetNorm == normNonWs[k]) {
            val r = if (normToRaw[k] != -1) rawNonWs[normToRaw[k]] else (raw.length * targetNorm / normalized.length)
            return r.coerceIn(0, raw.length)
        }

        if (k + 1 < normNonWs.size && targetNorm == normNonWs[k + 1]) {
            val nextR = if (normToRaw[k + 1] != -1) rawNonWs[normToRaw[k + 1]] else (raw.length * targetNorm / normalized.length)
            return nextR.coerceIn(0, raw.length)
        }

        val rK = if (normToRaw[k] != -1) {
            rawNonWs[normToRaw[k]]
        } else {
            val prevMatched = (k downTo 0).firstOrNull { normToRaw[it] != -1 }
            if (prevMatched != null) rawNonWs[normToRaw[prevMatched]] else 0
        }

        val rKNext = if (k + 1 < normNonWs.size && normToRaw[k + 1] != -1) {
            rawNonWs[normToRaw[k + 1]]
        } else {
            val nextMatched = ((k + 1) until normNonWs.size).firstOrNull { normToRaw[it] != -1 }
            if (nextMatched != null) rawNonWs[normToRaw[nextMatched]] else raw.length
        }

        val rawGapStart = (rK + 1).coerceAtMost(raw.length)
        val rawGapEnd = rKNext.coerceIn(rawGapStart, raw.length)

        val normGapStart = normNonWs[k] + 1
        val normGapEnd = normNonWs[k + 1]
        val normGap = normalized.substring(normGapStart, normGapEnd)
        val rawGap = raw.substring(rawGapStart, rawGapEnd)
        val cursorInGap = targetNorm - normGapStart

        return interpolateWhitespace(normGap, rawGap, cursorInGap, rawGapStart, rawGapEnd)
    }

    fun adjustToSafeTokenBoundary(raw: String, offset: Int): Int {
        var safeOffset = offset.coerceIn(0, raw.length)
        if (safeOffset == 0 || safeOffset == raw.length) return safeOffset

        if (safeOffset > 0 && safeOffset < raw.length && raw[safeOffset - 1] == '\r' && raw[safeOffset] == '\n') {
            safeOffset++
        }
        if (safeOffset >= raw.length) return raw.length

        val lines = splitRawLines(raw)
        if (lines.isEmpty()) return safeOffset

        val frontMatter = findFrontMatterRange(lines)
        if (frontMatter != null && safeOffset in frontMatter.first until frontMatter.last) {
            return frontMatter.last.coerceAtMost(raw.length)
        }

        val codeBlocks = findCodeBlockRanges(lines, raw.length)
        for (block in codeBlocks) {
            if (safeOffset in block.first until block.last) {
                return block.last.coerceAtMost(raw.length)
            }
        }

        val tables = findTableRanges(lines)
        for (table in tables) {
            if (safeOffset in table.first until table.last) {
                return table.last.coerceAtMost(raw.length)
            }
        }

        for (line in lines) {
            if (line.text.matches(atxHeadingRegex)) {
                if (safeOffset in line.start until line.endWithNewline) {
                    return line.endWithNewline.coerceAtMost(raw.length)
                }
            }
        }
        for (i in 0 until lines.size - 1) {
            if (lines[i].text.isNotBlank() && lines[i + 1].text.matches(setextHeadingRegex)) {
                if (safeOffset in lines[i].start until lines[i + 1].endWithNewline) {
                    return lines[i + 1].endWithNewline.coerceAtMost(raw.length)
                }
            }
        }

        val currentLine = lines.firstOrNull { safeOffset in it.start..it.endWithNewline }
        if (currentLine != null) {
            val codeSpans = findInlineCodeRanges(currentLine.text, currentLine.start)
            for (span in codeSpans) {
                if (safeOffset in (span.first + 1) until span.last) {
                    safeOffset = span.last
                }
            }

            for (match in linkRegex.findAll(currentLine.text)) {
                val start = currentLine.start + match.range.first
                val end = currentLine.start + match.range.last + 1
                if (safeOffset in (start + 1) until end) {
                    safeOffset = end
                }
            }

            for (match in htmlTagOrCommentRegex.findAll(currentLine.text)) {
                val start = currentLine.start + match.range.first
                val end = currentLine.start + match.range.last + 1
                if (safeOffset in (start + 1) until end) {
                    safeOffset = end
                }
            }

            if (currentLine.text.isNotBlank() && safeOffset in currentLine.start until currentLine.end) {
                val trailing = raw.substring(safeOffset, currentLine.end)
                if (trailing.isNotEmpty() && trailing.isBlank()) {
                    safeOffset = currentLine.end
                }
            }
        }

        if (safeOffset > 0 && safeOffset < raw.length && raw[safeOffset - 1] == '\r' && raw[safeOffset] == '\n') {
            safeOffset++
        }

        return safeOffset.coerceIn(0, raw.length)
    }

    fun safeInsertPoint(raw: String, normalizedOffset: Int, normalized: String? = null): Int {
        val rawOffset = mapNormalizedOffsetToRaw(raw, normalizedOffset, normalized)
        return adjustToSafeTokenBoundary(raw, rawOffset)
    }
}

data class VideoAttachment(
    val name: String,
    val relativeDirectory: String,
    val transactionUri: Uri
)

data class VaultSyncFile(
    val relativePath: String,
    val document: VaultDocument
)

enum class VaultFailureKind {
    PERMISSION_DENIED,
    READ_FAILED
}

sealed class NoteReadResult {
    data class Success(val content: String) : NoteReadResult()
    data class Failure(val kind: VaultFailureKind) : NoteReadResult()
}

interface NoteReadWriter {
    fun saveText(document: VaultDocument, content: String): Boolean
    fun refreshDocument(document: VaultDocument): VaultDocument?
    fun readNote(document: VaultDocument): NoteReadResult
}

sealed class DailyNoteResult {
    data class Existing(val document: VaultDocument) : DailyNoteResult()
    data class Created(val document: VaultDocument) : DailyNoteResult()
    data class Failure(val kind: VaultFailureKind) : DailyNoteResult()
}

sealed class VaultRecoveryResult {
    object Success : VaultRecoveryResult()
    data class Failure(val kind: VaultFailureKind) : VaultRecoveryResult()
}

data class VaultSearchHit(val document: VaultDocument, val match: VaultSearchMatch)

sealed class VaultSearchResult {
    data class Success(val hits: List<VaultSearchHit>, val unreadableCount: Int) : VaultSearchResult()
    data class Failure(val kind: VaultFailureKind) : VaultSearchResult()
    object Cancelled : VaultSearchResult()
}

sealed class TrashContentsResult {
    data class Success(val count: Int) : TrashContentsResult()
    data class Failure(val kind: VaultFailureKind) : TrashContentsResult()
}

sealed class TrashDocumentsResult {
    data class Success(val documents: List<VaultDocument>) : TrashDocumentsResult()
    data class Failure(val kind: VaultFailureKind) : TrashDocumentsResult()
}

data class TrashClearResult(
    val deleted: Int,
    val failed: Int,
    val remaining: Int
)

enum class VaultMutationFailureKind {
    PERMISSION_DENIED,
    READ_FAILED,
    INVALID_NAME,
    NAME_CONFLICT,
    CREATE_FAILED,
    RENAME_FAILED,
    TRASH_NAME_CONFLICT,
    MOVE_UNSUPPORTED
}

enum class DailyDirectoryChange { UNCHANGED, REWRITTEN, RESET_TO_ROOT }

sealed class VaultMutationResult {
    data class Success(
        val document: VaultDocument?,
        val requestedName: String,
        val actualName: String?,
        val dailyDirectoryChange: DailyDirectoryChange = DailyDirectoryChange.UNCHANGED
    ) : VaultMutationResult()

    data class Failure(val kind: VaultMutationFailureKind, val message: String? = null) : VaultMutationResult()
}

class VaultRepository(private val context: Context, private val fixedVaultUri: Uri? = null) : NoteReadWriter {
    private val resolver: ContentResolver = context.contentResolver
    private val preferences = context.getSharedPreferences("heji_notes", Context.MODE_PRIVATE)

    fun savedVaultUri(): Uri? = fixedVaultUri ?: preferences.getString(VAULT_URI_KEY, null)?.let(Uri::parse)

    fun rememberVault(uri: Uri) {
        val flags = IntentFlags.READ_WRITE
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some HarmonyOS 4 builds grant the tree without exposing persistable flags.
        }
        preferences.edit().putString(VAULT_URI_KEY, uri.toString()).apply()
    }

    fun clearVault() {
        preferences.edit().remove(VAULT_URI_KEY).apply()
    }

    fun appearanceMode(): String = preferences.getString(APPEARANCE_MODE_KEY, APPEARANCE_NIGHT)
        ?: APPEARANCE_NIGHT

    fun setAppearanceMode(mode: String) {
        if (mode !in setOf(APPEARANCE_DAY, APPEARANCE_NIGHT)) return
        preferences.edit().putString(APPEARANCE_MODE_KEY, mode).apply()
    }

    fun swipeDiscoveryHintSeen(): Boolean = preferences.getBoolean(SWIPE_DISCOVERY_HINT_SEEN_KEY, false)

    fun markSwipeDiscoveryHintSeen() {
        preferences.edit().putBoolean(SWIPE_DISCOVERY_HINT_SEEN_KEY, true).apply()
    }

    fun dailyNoteDirectoryPath(): String = preferences.getString(
        DAILY_NOTE_DIRECTORY_KEY,
        DEFAULT_DAILY_NOTE_DIRECTORY
    ) ?: DEFAULT_DAILY_NOTE_DIRECTORY

    fun dailyNoteDirectoryParts(): List<String> = dailyNoteDirectoryPath()
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }

    fun setDailyNoteDirectory(parts: List<String>) {
        val cleanParts = parts.map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        preferences.edit()
            .putString(DAILY_NOTE_DIRECTORY_KEY, cleanParts.joinToString("/"))
            .remove(DAILY_DIRECTORY_RESET_NOTICE_KEY)
            .apply()
    }

    fun dailyDirectoryResetNotice(): Boolean = preferences.getBoolean(DAILY_DIRECTORY_RESET_NOTICE_KEY, false)

    fun vaultRoot(): VaultDocument? {
        val tree = savedVaultUri() ?: return null
        val root = rootDocument(tree)
        val name = try {
            resolver.query(
                root,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return VaultDocument(root, name?.takeIf { it.isNotBlank() } ?: "Vault", DocumentsContract.Document.MIME_TYPE_DIR)
    }

    fun children(directory: VaultDocument): List<VaultDocument> {
        val tree = savedVaultUri() ?: return emptyList()
        return listChildren(tree, directory.uri, directory.relativePath)
    }

    /** Used when an optimistic UI result must distinguish an unreadable directory from an empty one. */
    fun verifiedChildren(directory: VaultDocument): List<VaultDocument> {
        val tree = savedVaultUri() ?: throw IllegalStateException("Vault unavailable")
        return listChildrenStrict(tree, directory.uri, directory.relativePath)
    }

    /** All user-manageable directories, for a same-Vault note-move destination picker. */
    fun directories(): List<VaultDocument> {
        val tree = savedVaultUri() ?: return emptyList()
        val root = vaultRoot() ?: return emptyList()
        fun descend(directory: VaultDocument): List<VaultDocument> = buildList {
            add(directory)
            listChildrenStrict(tree, directory.uri, directory.relativePath)
                .filter { isDirectory(it) && !isInternalPath(it.relativePath) }
                .sortedBy { it.relativePath.lowercase(Locale.ROOT) }
                .forEach { addAll(descend(it)) }
        }
        return try { descend(root) } catch (_: Exception) { emptyList() }
    }

    fun searchNotes(query: String, shouldContinue: () -> Boolean = { true }): VaultSearchResult {
        val tree = savedVaultUri() ?: return VaultSearchResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        val root = vaultRoot() ?: return VaultSearchResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        val hits = mutableListOf<VaultSearchHit>()
        var unreadable = 0
        fun scan(directory: VaultDocument): Boolean {
            if (!shouldContinue()) return false
            val children = try {
                listChildrenStrict(tree, directory.uri, directory.relativePath)
            } catch (_: Exception) {
                if (directory == root) return false
                unreadable += 1
                return true
            }
            children.forEach { child ->
                if (!shouldContinue()) return false
                if (VaultPathPolicy.isProtected(child.relativePath) || child.name.startsWith(".markbook-")) return@forEach
                if (isDirectory(child)) {
                    if (!scan(child)) return false
                } else if (child.name.endsWith(".md", true)) {
                    val content = readText(child)
                    if (content == null) unreadable += 1 else {
                        VaultSearchPolicy.match(child.name.removeSuffix(".md"), content, query)?.let { match ->
                            hits += VaultSearchHit(child, match)
                        }
                    }
                }
            }
            return true
        }
        return try {
            if (!scan(root)) return if (shouldContinue()) VaultSearchResult.Failure(VaultFailureKind.READ_FAILED) else VaultSearchResult.Cancelled
            VaultSearchResult.Success(
                hits.sortedWith(compareBy<VaultSearchHit> { it.match.rank }
                    .thenBy { it.document.name.lowercase() }
                    .thenBy { it.document.relativePath.lowercase() }),
                unreadable
            )
        } catch (_: SecurityException) {
            VaultSearchResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultSearchResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    fun canReadDirectory(directory: VaultDocument): Boolean {
        val tree = savedVaultUri() ?: return false
        val parentId = try {
            DocumentsContract.getDocumentId(directory.uri)
        } catch (_: Exception) {
            return false
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun isDirectory(document: VaultDocument): Boolean =
        document.mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    override fun refreshDocument(document: VaultDocument): VaultDocument? {
        val tree = savedVaultUri() ?: return null
        val parent = document.parentUri ?: return null
        return findChild(tree, parent, document.name, document.parentRelativePath)
    }

    fun relativeAttachmentPath(note: VaultDocument, attachments: PhotoAttachments): String {
        return VaultRelativePath.attachmentPath(
            note.parentRelativePath,
            "${attachments.relativeDirectory}/${attachments.corrected}"
        )
    }

    fun relativeAttachmentPath(note: VaultDocument, attachment: VideoAttachment): String =
        VaultRelativePath.attachmentPath(note.parentRelativePath, "${attachment.relativeDirectory}/${attachment.name}")

    fun savePendingVideoCapture(session: PendingVideoCaptureSession) {
        preferences.edit()
            .putString(PENDING_VIDEO_NOTE_URI, session.noteUri)
            .putString(PENDING_VIDEO_NOTE_PATH, session.noteRelativePath)
            .putString(PENDING_VIDEO_HASH, session.contentSha256)
            .putInt(PENDING_VIDEO_CARET, session.caretOffset)
            .putInt(PENDING_VIDEO_SCROLL, session.scrollY)
            .putString(PENDING_VIDEO_CACHE, session.cachePath)
            .putString(PENDING_VIDEO_STAGE, session.stage)
            .putString(PENDING_VIDEO_ATTACHMENT_PATH, session.attachmentVaultPath)
            .commit()
    }

    fun pendingVideoCapture(): PendingVideoCaptureSession? {
        val noteUri = preferences.getString(PENDING_VIDEO_NOTE_URI, null) ?: return null
        val notePath = preferences.getString(PENDING_VIDEO_NOTE_PATH, null) ?: return null
        val hash = preferences.getString(PENDING_VIDEO_HASH, null) ?: return null
        val cachePath = preferences.getString(PENDING_VIDEO_CACHE, null) ?: return null
        val stage = preferences.getString(PENDING_VIDEO_STAGE, null) ?: return null
        return PendingVideoCaptureSession(
            noteUri, notePath, hash, preferences.getInt(PENDING_VIDEO_CARET, 0),
            preferences.getInt(PENDING_VIDEO_SCROLL, 0), cachePath, stage,
            preferences.getString(PENDING_VIDEO_ATTACHMENT_PATH, null)
        )
    }

    fun clearPendingVideoCapture() {
        preferences.edit().remove(PENDING_VIDEO_NOTE_URI).remove(PENDING_VIDEO_NOTE_PATH)
            .remove(PENDING_VIDEO_HASH).remove(PENDING_VIDEO_CARET).remove(PENDING_VIDEO_SCROLL)
            .remove(PENDING_VIDEO_CACHE).remove(PENDING_VIDEO_STAGE).remove(PENDING_VIDEO_ATTACHMENT_PATH).commit()
    }

    fun openDailyNote(): DailyNoteResult {
        return try {
            val tree = savedVaultUri()
                ?: return DailyNoteResult.Failure(VaultFailureKind.PERMISSION_DENIED)
            val directory = findOrCreateDirectory(tree, dailyNoteDirectoryParts())
                ?: return DailyNoteResult.Failure(VaultFailureKind.READ_FAILED)
            val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".md"
            val dailyPath = dailyNoteDirectoryParts().joinToString("/")
            val existing = findChild(tree, directory, name, dailyPath)
            if (existing != null) return DailyNoteResult.Existing(existing)
            val created = DocumentsContract.createDocument(
                resolver,
                directory,
                "text/markdown",
                name
            ) ?: return DailyNoteResult.Failure(VaultFailureKind.READ_FAILED)
            DailyNoteResult.Created(VaultDocument(
                created,
                name,
                "text/markdown",
                directory,
                (dailyNoteDirectoryParts() + name).joinToString("/")
            ))
        } catch (_: SecurityException) {
            DailyNoteResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            DailyNoteResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    fun dailyNote(): VaultDocument? = when (val result = openDailyNote()) {
        is DailyNoteResult.Created -> result.document
        is DailyNoteResult.Existing -> result.document
        is DailyNoteResult.Failure -> null
    }

    override fun readNote(document: VaultDocument): NoteReadResult {
        return try {
            val content = resolver.openInputStream(document.uri)?.use {
                String(it.readBytes(), StandardCharsets.UTF_8)
            } ?: return NoteReadResult.Failure(VaultFailureKind.READ_FAILED)
            NoteReadResult.Success(content)
        } catch (_: SecurityException) {
            NoteReadResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            NoteReadResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    fun readText(document: VaultDocument): String? = when (val result = readNote(document)) {
        is NoteReadResult.Success -> result.content
        is NoteReadResult.Failure -> null
    }

    fun bodySha256(content: String): String = PhotoCommitPolicy.bodySha256(content)

    /** Resolves interrupted Vault transactions throughout the user-editable Vault. */
    fun recoverVault(): VaultRecoveryResult {
        return try {
            val tree = savedVaultUri()
                ?: return VaultRecoveryResult.Failure(VaultFailureKind.PERMISSION_DENIED)
            recoverVaultDirectory(tree, rootDocument(tree), "")
            recoverPendingVideoCapture(tree)
            recoverPreparedLocalChanges(tree)
            if (!recoverNoteBundleMoves(tree) || hasUnresolvedMoveTransactions()) return VaultRecoveryResult.Failure(VaultFailureKind.READ_FAILED)
            VaultRecoveryResult.Success
        } catch (_: SecurityException) {
            VaultRecoveryResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultRecoveryResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    /**
     * Reads at most [maxBytes] from a note. List rows only need the opening lines, so they must not
     * pay for reading an entire document.
     */
    fun readPreview(document: VaultDocument, maxBytes: Int): String? = try {
        resolver.openInputStream(document.uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val count = stream.read(buffer, read, maxBytes - read)
                if (count <= 0) break
                read += count
            }
            String(buffer, 0, read, StandardCharsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    override fun saveText(document: VaultDocument, content: String): Boolean {
        val parent = document.parentUri ?: return false
        val tempName = ".markbook-${UUID.randomUUID()}.tmp"
        val temp = DocumentsContract.createDocument(resolver, parent, "text/plain", tempName)
            ?: return false
        var backup: Uri? = null
        return try {
            resolver.openOutputStream(temp, "wt")?.use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } ?: throw IllegalStateException("Unable to open temporary note")

            val backupName = ".markbook-${document.name}.bak"
            backup = DocumentsContract.renameDocument(resolver, document.uri, backupName)
            val committed = DocumentsContract.renameDocument(resolver, temp, document.name)
            if (committed == null) throw IllegalStateException("Unable to commit note")
            if (backup != null) {
                try {
                    DocumentsContract.deleteDocument(resolver, backup)
                } catch (_: Exception) {
                    // The committed note is valid; the next launch can clean the backup.
                }
            }
            true
        } catch (_: Exception) {
            try {
                if (backup != null) DocumentsContract.renameDocument(resolver, backup, document.name)
                DocumentsContract.deleteDocument(resolver, temp)
            } catch (_: Exception) {
                // Recovery is retried on the next launch when the tree is available.
            }
            false
        }
    }

    fun saveText(uri: Uri, name: String, content: String, parentUri: Uri): Boolean =
        saveText(VaultDocument(uri, name, "text/markdown", parentUri), content)

    fun createNote(parent: VaultDocument, requestedName: String): VaultMutationResult =
        createChild(parent, requestedName, VaultEntryKind.NOTE)

    fun createFolder(parent: VaultDocument, requestedName: String): VaultMutationResult =
        createChild(parent, requestedName, VaultEntryKind.FOLDER)

    fun rename(document: VaultDocument, requestedName: String): VaultMutationResult {
        if (!isManageable(document)) return VaultMutationResult.Failure(VaultMutationFailureKind.RENAME_FAILED)
        val parent = document.parentUri ?: return VaultMutationResult.Failure(VaultMutationFailureKind.RENAME_FAILED)
        val kind = if (isDirectory(document)) VaultEntryKind.FOLDER else VaultEntryKind.NOTE
        val validated = VaultNamePolicy.validate(requestedName, kind)
        if (validated is VaultNameValidation.Invalid) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME, validated.message)
        }
        val validName = validated as VaultNameValidation.Valid
        if (kind == VaultEntryKind.FOLDER && document.parentRelativePath.isEmpty() && VaultPathPolicy.isProtectedRootName(validName.actualName)) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME, "该名称由 Vault 保留")
        }
        return try {
            val tree = savedVaultUri() ?: return VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
            val existing = listChildrenStrict(tree, parent, document.parentRelativePath).map { it.name }
            if (VaultNamePolicy.conflicts(validName, existing, document.name)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.NAME_CONFLICT, "当前目录已有同名笔记或文件夹")
            }
            val renamed = DocumentsContract.renameDocument(resolver, document.uri, validName.actualName)
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.RENAME_FAILED)
            val resolved = resolveMutationDocument(parent, document.parentRelativePath, renamed)
            val actualName = resolved?.name
            val dailyChange = if (kind == VaultEntryKind.FOLDER) {
                if (resolved != null) rewriteDailyDirectoryAfterRenameCommitted(document.relativePath, resolved.relativePath)
                else resetDailyDirectoryIfRemovedCommitted(document.relativePath)
            } else DailyDirectoryChange.UNCHANGED
            VaultMutationResult.Success(
                resolved,
                validName.actualName,
                actualName,
                dailyChange
            )
        } catch (_: SecurityException) {
            VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultMutationResult.Failure(VaultMutationFailureKind.RENAME_FAILED)
        }
    }

    /** Moves one direct child atomically; a provider that cannot move leaves the source untouched. */
    fun moveToTrash(document: VaultDocument): VaultMutationResult {
        if (!isManageable(document)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        val sourceParent = document.parentUri ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        return try {
            val tree = savedVaultUri() ?: return VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
            val trash = findOrCreateDirectory(tree, listOf(".trash"))
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED)
            val moved = try {
                DocumentsContract.moveDocument(resolver, document.uri, sourceParent, trash)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (moved != null) {
                val resolved = resolveMutationDocument(trash, ".trash", moved)
                val dailyChange = if (isDirectory(document)) resetDailyDirectoryIfRemovedCommitted(document.relativePath) else DailyDirectoryChange.UNCHANGED
                return VaultMutationResult.Success(resolved, document.name, resolved?.name, dailyChange)
            }
            if (isDirectory(document)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
            moveNoteToTrashByCopy(tree, document, trash)
        } catch (_: SecurityException) {
            VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        }
    }

    /**
     * Moves a Markdown note and its exclusive attachment bundle. This deliberately has no
     * copy/delete fallback: a provider that cannot atomically move either object leaves the
     * source available for a retry.
     */
    fun moveNoteWithAssets(document: VaultDocument, destination: VaultDocument): VaultMutationResult {
        if (hasUnresolvedMoveTransactions()) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "存在尚未恢复的笔记移动")
        if (!isManageable(document) || !document.name.endsWith(".md", true) || !isManageableParent(destination)) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        }
        if (document.parentUri == destination.uri) return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME, "笔记已在这个目录")
        val sourceParent = document.parentUri ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        return try {
            val tree = savedVaultUri() ?: return VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
            val resolvedSource = findByRelativePath(tree, document.relativePath)
            val resolvedDestination = if (destination.relativePath.isBlank()) vaultRoot() else findByRelativePath(tree, destination.relativePath)
            if (resolvedSource?.uri != document.uri || resolvedDestination == null ||
                resolvedDestination.uri != destination.uri || !isDirectory(resolvedDestination)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "源和目标必须属于当前 Vault")
            }
            val noteName = VaultNamePolicy.validate(document.name, VaultEntryKind.NOTE) as? VaultNameValidation.Valid
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME)
            if (VaultNamePolicy.conflicts(noteName, listChildrenStrict(tree, destination.uri, destination.relativePath).map { it.name })) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.NAME_CONFLICT, "目标目录已有同名笔记或文件夹")
            }
            val markdown = readText(document) ?: return VaultMutationResult.Failure(VaultMutationFailureKind.READ_FAILED, "无法读取要移动的笔记")
            val colocatedBundlePath = NoteBundleMovePolicy.bundlePath(document.relativePath)
            val legacyBundlePath = NoteBundleMovePolicy.legacyBundlePath(document.relativePath)
            val colocatedExists = findByRelativePath(tree, colocatedBundlePath)?.let(::isDirectory) == true
            val legacyExists = findByRelativePath(tree, legacyBundlePath)?.let(::isDirectory) == true

            val resolution = NoteBundleMovePolicy.resolveSourceBundle(
                noteRelativePath = document.relativePath,
                markdown = markdown,
                colocatedExists = colocatedExists,
                legacyExists = legacyExists
            )
            val sourceBundlePath = when (resolution) {
                is NoteBundleMovePolicy.ResolvedSourceBundle.Found -> resolution.bundlePath
                is NoteBundleMovePolicy.ResolvedSourceBundle.None -> null
                is NoteBundleMovePolicy.ResolvedSourceBundle.AmbiguousReference ->
                    return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "附件链接无法安全重写")
                is NoteBundleMovePolicy.ResolvedSourceBundle.MultipleReferencedBundles ->
                    return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "检测到多个同名附件目录且均被引用，无法安全移动")
            }
            val sourceBundle = sourceBundlePath?.let { findByRelativePath(tree, it) }
            if (sourceBundlePath != null && sourceBundle == null) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "Vault 在移动前发生变化，请重试")
            }
            if (sourceBundlePath != null && markdownReferencesBundle(tree, document, sourceBundlePath)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "附件被其他笔记使用，无法安全移动")
            }
            val targetPath = joinRelativePath(destination.relativePath, document.name)
            val targetBundlePath = NoteBundleMovePolicy.bundlePath(targetPath)
            if (sourceBundle != null && findByRelativePath(tree, targetBundlePath) != null) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.NAME_CONFLICT, "目标目录已有同名附件")
            }
            val rewritten = if (sourceBundlePath == null) markdown else try {
                NoteBundleMovePolicy.rewriteBundleReferences(markdown, document.parentRelativePath, destination.relativePath, sourceBundlePath, targetBundlePath)
            } catch (_: IllegalArgumentException) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "附件链接无法安全重写")
            }
            val changeId = UUID.randomUUID().toString()
            val change = buildMoveChange(tree, changeId, document, sourceBundle, sourceBundlePath, targetPath, targetBundlePath, markdown, rewritten)
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.READ_FAILED, "无法读取附件清单")
            val marker = createMoveMarker(tree, NoteBundleMoveTransaction(
                changeId, document.relativePath, targetPath, sourceBundlePath ?: "", targetBundlePath,
                sha256(markdown), sha256(rewritten), NoteBundleMoveTransaction.Stage.PREPARED
            )) ?: run {
                return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED, "无法创建恢复标记")
            }
            val journal = LocalChangeJournal(context)
            if (!journal.prepare(change)) {
                runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
                return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED, "无法记录本地移动历史")
            }
            if (sha256(readText(document) ?: "") != sha256(markdown) ||
                (sourceBundlePath != null && markdownReferencesBundle(tree, document, sourceBundlePath))) {
                abortPreparedMove(marker, changeId)
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "Vault 在移动前发生变化，请重试")
            }
            if (sourceBundle != null) {
                val targetAssetsPath = joinRelativePath(destination.relativePath, "assets")
                val targetAssets = findOrCreateDirectory(
                    tree, destination.relativePath.split('/').filter { it.isNotBlank() } + "assets"
                ) ?: return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED)
                val targetAssetsDocument = findByRelativePath(tree, targetAssetsPath)
                if (targetAssetsDocument == null || targetAssetsDocument.uri != targetAssets ||
                    targetAssetsDocument.name != "assets" || !isDirectory(targetAssetsDocument)) {
                    return VaultMutationResult.Failure(VaultMutationFailureKind.NAME_CONFLICT, "目标 assets 目录名称不确定")
                }
                val bundleParent = sourceBundle.parentUri
                    ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
                if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.BUNDLE_INTENT)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "无法更新恢复标记")
                val movedBundle = DocumentsContract.moveDocument(resolver, sourceBundle.uri, bundleParent, targetAssets)
                if (movedBundle == null) {
                    return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "无法移动附件目录，源内容未改动")
                }
                val resolvedBundle = resolveMutationDocument(targetAssets, targetAssetsPath, movedBundle)
                if (resolvedBundle?.name != sourceBundle.name || resolvedBundle.relativePath != targetBundlePath) {
                    return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "Provider 更改了附件目录名称，已保留恢复标记")
                }
                if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.BUNDLE_AT_TARGET)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "附件已移动，恢复标记写入失败")
            }
            if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.NOTE_INTENT)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "无法更新恢复标记")
            val moved = DocumentsContract.moveDocument(resolver, document.uri, sourceParent, destination.uri)
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "笔记移动结果不确定，正在保留数据以便恢复")
            val movedDocument = resolveMutationDocument(destination.uri, destination.relativePath, moved)
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "无法确认移动后的笔记")
            if (movedDocument.name != document.name || movedDocument.relativePath != targetPath) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "Provider 更改了笔记名称，已保留恢复标记")
            }
            if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.NOTE_AT_TARGET)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "笔记已移动，恢复标记写入失败")
            if (sha256(readText(movedDocument) ?: "") != sha256(markdown)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "笔记已被外部修改，已保留恢复标记")
            if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.REWRITE_INTENT)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "无法更新恢复标记")
            if (!saveText(movedDocument, rewritten)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "附件已移动，但无法更新笔记链接；请勿清理数据并重试")
            }
            if (!updateMoveMarker(marker, NoteBundleMoveTransaction.Stage.REWRITTEN)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "笔记已更新，恢复标记写入失败")
            if (!verifyMoveTargets(tree, changeId)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "移动结果验证失败，已保留恢复标记")
            if (!journal.commit(changeId)) return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "本地移动完成，但变化历史尚未确认")
            DocumentsContract.deleteDocument(resolver, marker.uri)
            VaultMutationResult.Success(refreshDocument(movedDocument), document.name, document.name)
        } catch (_: SecurityException) {
            VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        }
    }

    fun trashContents(): TrashContentsResult {
        return try {
            val tree = savedVaultUri()
                ?: return TrashContentsResult.Failure(VaultFailureKind.PERMISSION_DENIED)
            val trash = findChildStrict(tree, rootDocument(tree), ".trash")
                ?: return TrashContentsResult.Success(0)
            TrashContentsResult.Success(listChildrenStrict(tree, trash.uri).size)
        } catch (_: SecurityException) {
            TrashContentsResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            TrashContentsResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    fun trashDocuments(): TrashDocumentsResult {
        return try {
            val tree = savedVaultUri() ?: return TrashDocumentsResult.Failure(VaultFailureKind.PERMISSION_DENIED)
            val trash = findChildStrict(tree, rootDocument(tree), ".trash")
                ?: return TrashDocumentsResult.Success(emptyList())
            TrashDocumentsResult.Success(listChildrenStrict(tree, trash.uri, ".trash").sortedWith(
                compareBy<VaultDocument> { !isDirectory(it) }.thenBy { it.name.lowercase() }
            ))
        } catch (_: SecurityException) {
            TrashDocumentsResult.Failure(VaultFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            TrashDocumentsResult.Failure(VaultFailureKind.READ_FAILED)
        }
    }

    /** Deletes only direct children whose immutable URI snapshot was confirmed by the user. */
    fun deleteTrashSnapshot(confirmedUris: Collection<String>): TrashClearResult {
        if (confirmedUris.isEmpty()) return TrashClearResult(0, 0, trashContentsCount())
        return try {
            val tree = savedVaultUri() ?: return TrashClearResult(0, confirmedUris.size, 0)
            val trash = findChildStrict(tree, rootDocument(tree), ".trash")
                ?: return TrashClearResult(0, confirmedUris.size, 0)
            val direct = listChildrenStrict(tree, trash.uri, ".trash")
            val directIds = direct.map { it.uri.toString() }
            val targets = TrashSnapshotPolicy.targets(confirmedUris, directIds)
            var deleted = 0
            var failed = TrashSnapshotPolicy.missingCount(confirmedUris, directIds)
            targets.forEach { id ->
                val item = direct.firstOrNull { it.uri.toString() == id }
                val didDelete = try { item != null && DocumentsContract.deleteDocument(resolver, item.uri) } catch (_: Exception) { false }
                if (didDelete) deleted++ else failed++
            }
            val remaining = try { listChildrenStrict(tree, trash.uri, ".trash").size } catch (_: Exception) { failed.coerceAtLeast(0) }
            TrashClearResult(deleted, failed, remaining)
        } catch (_: Exception) {
            TrashClearResult(0, confirmedUris.size, trashContentsCount())
        }
    }

    private fun trashContentsCount(): Int = (trashDocuments() as? TrashDocumentsResult.Success)?.documents?.size ?: 0

    fun savePhotoPair(
        note: VaultDocument,
        original: InputStream,
        corrected: ByteArray,
        extension: String = "jpg"
    ): PhotoAttachments? {
        val tree = savedVaultUri() ?: return null
        val attachmentDirectory = attachmentDirectory(tree, note) ?: return null
        val relativeDirectory = attachmentDirectory.first
        val directory = attachmentDirectory.second
        val id = nextCaptureId(tree, directory) ?: return null
        val originalName = "$id-o.$extension"
        val correctedName = "$id-c.$extension"
        val marker = DocumentsContract.createDocument(
            resolver, directory, "text/plain", ".markbook-$id.txn"
        ) ?: return null
        val originalTemp = DocumentsContract.createDocument(
            resolver, directory, "image/jpeg", ".markbook-${UUID.randomUUID()}.tmp"
        ) ?: run {
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
            return null
        }
        val correctedTemp = DocumentsContract.createDocument(
            resolver, directory, "image/jpeg", ".markbook-${UUID.randomUUID()}.tmp"
        )
        if (correctedTemp == null) {
            try { DocumentsContract.deleteDocument(resolver, originalTemp) } catch (_: Exception) { }
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
            return null
        }
        var committedOriginal: Uri? = null
        var committedCorrected: Uri? = null
        return try {
            resolver.openOutputStream(marker, "wt")?.use { output ->
                output.write("$originalName\n$correctedName".toByteArray(StandardCharsets.UTF_8))
            } ?: throw IllegalStateException("Unable to write photo transaction")
            resolver.openOutputStream(originalTemp, "wt")?.use { output -> original.copyTo(output) }
                ?: throw IllegalStateException("Unable to write original")
            resolver.openOutputStream(correctedTemp, "wt")?.use { output -> output.write(corrected) }
                ?: throw IllegalStateException("Unable to write corrected")
            committedOriginal = DocumentsContract.renameDocument(resolver, originalTemp, originalName)
                ?: throw IllegalStateException("Unable to commit original")
            committedCorrected = DocumentsContract.renameDocument(resolver, correctedTemp, correctedName)
                ?: throw IllegalStateException("Unable to commit corrected")
            PhotoAttachments(originalName, correctedName, relativeDirectory, marker)
        } catch (_: Exception) {
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
            try { DocumentsContract.deleteDocument(resolver, originalTemp) } catch (_: Exception) { }
            try { DocumentsContract.deleteDocument(resolver, correctedTemp) } catch (_: Exception) { }
            try { committedOriginal?.let { DocumentsContract.deleteDocument(resolver, it) } } catch (_: Exception) { }
            try { committedCorrected?.let { DocumentsContract.deleteDocument(resolver, it) } } catch (_: Exception) { }
            null
        }
    }

    fun confirmPhotoPair(attachments: PhotoAttachments) {
        try { DocumentsContract.deleteDocument(resolver, attachments.transactionUri) } catch (_: Exception) { }
    }

    fun rollbackPhotoPair(attachments: PhotoAttachments) {
        val tree = savedVaultUri() ?: return
        val directory = findByRelativePath(tree, attachments.relativeDirectory)?.uri ?: return
        var allDeleted = true
        listOf(attachments.original, attachments.corrected).forEach { name ->
            findChild(tree, directory, name)?.let {
                try { if (!DocumentsContract.deleteDocument(resolver, it.uri)) allDeleted = false } catch (_: Exception) { allDeleted = false }
            }
        }
        if (allDeleted) try { DocumentsContract.deleteDocument(resolver, attachments.transactionUri) } catch (_: Exception) { }
    }

    /** Writes one original system-camera video under an attachment marker. Cache ownership remains with caller. */
    fun saveVideoAttachment(
        note: VaultDocument,
        cacheFile: File,
        extension: String,
        onProgress: (copiedBytes: Long) -> Boolean
    ): VideoAttachment? {
        if (!cacheFile.isFile || cacheFile.length() <= 0L) return null
        val tree = savedVaultUri() ?: return null
        val attachmentDirectory = attachmentDirectory(tree, note) ?: return null
        val relativeDirectory = attachmentDirectory.first
        val directory = attachmentDirectory.second
        val id = nextCaptureId(tree, directory) ?: return null
        val name = "$id-v.$extension"
        val marker = DocumentsContract.createDocument(resolver, directory, "text/plain", ".markbook-$id.txn") ?: return null
        val markerPayload = VideoTransactionPolicy.markerPayload(name)
        if (!VideoTransactionPolicy.mayCreateTemporary(markerPayload)) return null
        val markerWritten = try {
            resolver.openOutputStream(marker, "wt")?.use { output ->
                output.write(markerPayload.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } != null
        } catch (_: Exception) {
            false
        }
        if (!markerWritten) {
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
            return null
        }
        val mimeType = if (extension.equals("3gp", ignoreCase = true)) "video/3gpp" else "video/mp4"
        val temporary = DocumentsContract.createDocument(
            resolver, directory, mimeType, ".markbook-${UUID.randomUUID()}.tmp"
        ) ?: run {
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
            return null
        }
        var finalRenameAttempted = false
        return try {
            FileInputStream(cacheFile).use { input ->
                resolver.openOutputStream(temporary, "wt")?.use { output ->
                    val buffer = ByteArray(DEFAULT_SYNC_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (!onProgress(copied)) throw VideoCopyCancelledException()
                    }
                    output.flush()
                } ?: throw IllegalStateException("Unable to write video")
            }
            finalRenameAttempted = true
            val committed = DocumentsContract.renameDocument(resolver, temporary, name)
                ?: throw IllegalStateException("Unable to commit video")
            VideoAttachment(name, relativeDirectory, marker)
        } catch (_: VideoCopyCancelledException) {
            cleanupVideoTransactionBeforeFinalRename(tree, directory, name, temporary, marker, finalRenameAttempted)
            null
        } catch (_: Exception) {
            cleanupVideoTransactionBeforeFinalRename(tree, directory, name, temporary, marker, finalRenameAttempted)
            null
        }
    }

    /** Returns false when marker cleanup could not complete and recovery must retain it. */
    fun confirmVideoAttachment(attachment: VideoAttachment, cacheFile: File): Boolean {
        if (cacheFile.exists() && !cacheFile.delete()) return false
        return try { DocumentsContract.deleteDocument(resolver, attachment.transactionUri) } catch (_: Exception) { false }
    }

    fun noteReferencesPendingVideo(note: VaultDocument, session: PendingVideoCaptureSession): Boolean {
        val attachment = session.attachmentVaultPath ?: return false
        val relativePath = VaultRelativePath.attachmentPath(note.parentRelativePath, attachment)
        val content = readText(note) ?: return false
        return PendingVideoLinkPolicy.isReferenced(content, relativePath)
    }

    /** Returns true once a saved link is found, even when best-effort cleanup must retry later. */
    fun cleanupPendingVideoCaptureIfCommitted(note: VaultDocument, session: PendingVideoCaptureSession): Boolean {
        if (!noteReferencesPendingVideo(note, session)) return false
        val tree = savedVaultUri() ?: return true
        cleanupCompletedPendingVideoCapture(tree, session)
        return true
    }

    private fun cleanupVideoTransactionBeforeFinalRename(
        tree: Uri,
        directory: Uri,
        finalName: String,
        temporary: Uri,
        marker: Uri,
        finalRenameAttempted: Boolean
    ) {
        val finalConfirmedAbsent = !finalRenameAttempted &&
            runCatching { findChildStrict(tree, directory, finalName) == null }.getOrDefault(false)
        val temporaryName = documentName(temporary)
        val temporaryDeleteSucceeded = try { DocumentsContract.deleteDocument(resolver, temporary) } catch (_: Exception) { false }
        val temporaryConfirmedAbsent = temporaryName != null && temporaryDeleteSucceeded &&
            runCatching { findChildStrict(tree, directory, temporaryName) == null }.getOrDefault(false)
        if (VideoTransactionPolicy.mayDeleteMarkerAfterFailure(
                finalRenameAttempted, finalConfirmedAbsent, temporaryConfirmedAbsent
            )
        ) {
            try { DocumentsContract.deleteDocument(resolver, marker) } catch (_: Exception) { }
        }
    }

    private fun recoverPendingVideoCapture(tree: Uri) {
        val session = pendingVideoCapture() ?: return
        val note = findByRelativePath(tree, session.noteRelativePath) ?: return
        val cacheExists = File(session.cachePath).isFile
        when (PendingVideoCaptureRecoveryPolicy.action(
            session.stage,
            session.attachmentVaultPath != null,
            noteReferencesPendingVideo(note, session),
            cacheExists
        )) {
            PendingVideoRecoveryAction.CLEANUP_ONLY -> cleanupPendingVideoCaptureIfCommitted(note, session)
            PendingVideoRecoveryAction.SHOW_CONFIRMATION, PendingVideoRecoveryAction.WAIT -> Unit
        }
    }

    /** A recovered saved link owns the result; cleanup must not recreate UI or save it again. */
    private fun cleanupCompletedPendingVideoCapture(tree: Uri, session: PendingVideoCaptureSession) {
        val vaultPath = session.attachmentVaultPath ?: return
        val slash = vaultPath.lastIndexOf('/')
        if (slash <= 0) return
        val directory = findByRelativePath(tree, vaultPath.substring(0, slash))?.uri ?: return
        val videoName = vaultPath.substring(slash + 1)
        val captureId = videoName.substringBeforeLast("-v.", missingDelimiterValue = "")
        if (captureId.isBlank()) return
        val marker = findChild(tree, directory, ".markbook-$captureId.txn")
        val markerClean = marker == null || try { DocumentsContract.deleteDocument(resolver, marker.uri) } catch (_: Exception) { false }
        val cache = File(session.cachePath)
        val cacheClean = !cache.exists() || cache.delete()
        if (markerClean && cacheClean) clearPendingVideoCapture()
    }

    fun openRelativeAttachment(relativePath: String): InputStream? {
        return try {
            val tree = savedVaultUri() ?: return null
            val document = findByRelativePath(tree, relativePath) ?: return null
            resolver.openInputStream(document.uri)
        } catch (_: Exception) {
            null
        }
    }

    fun resolveAttachmentPath(note: VaultDocument, markdownPath: String): String? {
        val decoded = Uri.decode(markdownPath.trim().removePrefix("<").removeSuffix(">"))
        return VaultRelativePath.resolveFromNoteParent(note.parentRelativePath, decoded)
    }

    /**
     * Returns the user-owned files that may be synchronized. App configuration, interrupted write
     * artifacts, and the Vault-local trash never leave the device.
     */
    fun syncFiles(): List<VaultSyncFile> {
        val tree = savedVaultUri() ?: return emptyList()
        return try {
            scanSyncDirectory(tree, rootDocument(tree))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Strict scan for plans that may create remote recycle-bin operations. */
    fun syncFilesStrict(): List<VaultSyncFile> {
        val tree = savedVaultUri() ?: throw SecurityException("Vault permission unavailable")
        return scanSyncDirectory(tree, rootDocument(tree))
    }

    fun openSyncInput(file: VaultSyncFile): InputStream? = try {
        resolver.openInputStream(file.document.uri)
    } catch (_: Exception) {
        null
    }

    /** Rechecks the current file immediately before a sync applies a remote replacement. */
    fun syncMd5(relativePath: String): String? {
        return try {
            val tree = savedVaultUri() ?: return null
            val document = findByRelativePath(tree, relativePath) ?: return null
            resolver.openInputStream(document.uri)?.use(::md5)
        } catch (_: Exception) { null }
    }

    /** Writes a downloaded file with the same recoverable replace protocol as note saving. */
    fun writeSyncFile(relativePath: String, mimeType: String?, input: InputStream, expectedCurrentMd5: String? = null): Boolean {
        val cleanPath = safeSyncPath(relativePath) ?: return false
        val tree = savedVaultUri() ?: return false
        val parts = cleanPath.split('/')
        val name = parts.last()
        val parent = findOrCreateDirectory(tree, parts.dropLast(1)) ?: return false
        val temp = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            ".markbook-${UUID.randomUUID()}.tmp"
        ) ?: return false
        var backup: Uri? = null
        return try {
            resolver.openOutputStream(temp, "wt")?.use { output -> copyStream(input, output) }
                ?: throw IllegalStateException("Unable to write downloaded file")
            val existing = findChild(tree, parent, name)
            if (expectedCurrentMd5 != null) {
                val current = existing?.let { resolver.openInputStream(it.uri)?.use(::md5) }
                if (!expectedCurrentMd5.equals(current, true)) throw IllegalStateException("Local file changed during sync")
            }
            if (existing != null) {
                backup = DocumentsContract.renameDocument(
                    resolver,
                    existing.uri,
                    ".markbook-$name.bak"
                )
                if (backup == null) throw IllegalStateException("Unable to protect existing file")
            }
            if (DocumentsContract.renameDocument(resolver, temp, name) == null) {
                throw IllegalStateException("Unable to commit downloaded file")
            }
            backup?.let { uri -> try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { } }
            true
        } catch (_: Exception) {
            try { backup?.let { DocumentsContract.renameDocument(resolver, it, name) } } catch (_: Exception) { }
            try { DocumentsContract.deleteDocument(resolver, temp) } catch (_: Exception) { }
            false
        }
    }

    /**
     * Commits a remote-only download only if a local file has not appeared since the scan.
     * This deliberately fails closed: the caller must keep the remote version as a conflict copy.
     */
    fun writeSyncFileIfAbsent(relativePath: String, mimeType: String?, input: InputStream): Boolean {
        val cleanPath = safeSyncPath(relativePath) ?: return false
        val tree = savedVaultUri() ?: return false
        val parts = cleanPath.split('/')
        val name = parts.last()
        val parent = findOrCreateDirectory(tree, parts.dropLast(1)) ?: return false
        if (findChild(tree, parent, name) != null) return false
        val temp = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            ".markbook-${UUID.randomUUID()}.tmp"
        ) ?: return false
        return try {
            resolver.openOutputStream(temp, "wt")?.use { output -> copyStream(input, output) }
                ?: throw IllegalStateException("Unable to write downloaded file")
            // Check immediately before final rename so a concurrently created or saved local note wins.
            if (findChild(tree, parent, name) != null) throw IllegalStateException("Local file changed during sync")
            if (DocumentsContract.renameDocument(resolver, temp, name) == null) {
                throw IllegalStateException("Unable to commit downloaded file")
            }
            true
        } catch (_: Exception) {
            try { DocumentsContract.deleteDocument(resolver, temp) } catch (_: Exception) { }
            false
        }
    }

    fun htmlAttachmentUrl(relativePath: String): String =
        "markbook://attachment/" + Uri.encode(relativePath, "/")

    fun htmlAttachmentUrl(note: VaultDocument, markdownPath: String): String? =
        resolveAttachmentPath(note, markdownPath)?.let(::htmlAttachmentUrl)

    private fun findByRelativePath(tree: Uri, relativePath: String): VaultDocument? {
        val parts = normalizeRelativePath(relativePath).split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var parent = rootDocument(tree)
        var parentRelativePath = ""
        var current: VaultDocument? = null
        for (part in parts) {
            current = findChild(tree, parent, part, parentRelativePath) ?: return null
            parent = current.uri
            parentRelativePath = current.relativePath
        }
        return current
    }

    private fun scanSyncDirectory(tree: Uri, directory: Uri, prefix: String = ""): List<VaultSyncFile> {
        return buildList {
            listChildrenStrict(tree, directory, prefix).forEach { child ->
                val path = listOf(prefix, child.name).filter { it.isNotEmpty() }.joinToString("/")
                if (!syncPathAllowed(path, child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR)) return@forEach
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    addAll(scanSyncDirectory(tree, child.uri, path))
                } else {
                    add(VaultSyncFile(path, child))
                }
            }
        }
    }

    /** A bundle cannot move unless every user Markdown file is readable and no peer references it. */
    private fun markdownReferencesBundle(tree: Uri, moving: VaultDocument, bundlePath: String): Boolean {
        if (bundlePath.isBlank()) return false
        fun scan(directory: VaultDocument): Boolean {
            listChildrenStrict(tree, directory.uri, directory.relativePath).forEach { child ->
                if (isInternalPath(child.relativePath)) return@forEach
                if (isDirectory(child)) {
                    if (scan(child)) return true
                } else if (child.name.endsWith(".md", true)) {
                    val content = readText(child) ?: return true // unreadable is conservatively unsafe
                    if (child.uri != moving.uri && NoteBundleMovePolicy.isBundleReference(content, child.parentRelativePath, bundlePath)) return true
                }
            }
            return false
        }
        return scan(VaultDocument(rootDocument(tree), "Vault", DocumentsContract.Document.MIME_TYPE_DIR))
    }

    private fun createMoveMarker(tree: Uri, transaction: NoteBundleMoveTransaction): VaultDocument? {
        val directory = findOrCreateDirectory(tree, listOf(".markbook", "moves")) ?: return null
        val name = ".markbook-note-move-${transaction.id}.txn"
        val uri = DocumentsContract.createDocument(resolver, directory, "text/plain", name) ?: return null
        val marker = resolveMutationDocument(directory, ".markbook/moves", uri) ?: return null
        return if (writeMoveMarker(marker, transaction)) marker else null
    }

    fun hasUnresolvedMoveTransactions(): Boolean {
        val tree = savedVaultUri() ?: return true
        if (LocalChangeJournal(context).changes(tree.toString())?.any { it.state == LocalChangeState.PREPARED } != false) return true
        val moves = findByRelativePath(tree, ".markbook/moves") ?: return false
        return try { listChildrenStrict(tree, moves.uri, ".markbook/moves").isNotEmpty() } catch (_: Exception) { true }
    }

    private fun abortPreparedMove(marker: VaultDocument, changeId: String) {
        if (LocalChangeJournal(context).acknowledge(changeId)) runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
    }

    private fun recoverPreparedLocalChanges(tree: Uri) {
        val journal = LocalChangeJournal(context)
        journal.changes(tree.toString())?.filter { it.state == LocalChangeState.PREPARED }?.forEach { change ->
            val sourcePresent = change.sourceToTarget.keys.all { findByRelativePath(tree, it) != null }
            val targetAbsent = change.sourceToTarget.values.all { findByRelativePath(tree, it) == null }
            if (sourcePresent && targetAbsent) journal.acknowledge(change.id)
        }
    }

    private fun buildMoveChange(
        tree: Uri,
        id: String,
        note: VaultDocument,
        bundle: VaultDocument?,
        sourceBundlePath: String?,
        targetNotePath: String,
        targetBundlePath: String,
        markdown: String,
        rewritten: String
    ): MoveBundleChange? {
        val mapping = linkedMapOf(note.relativePath to targetNotePath)
        val bundleMapping = if (bundle != null && sourceBundlePath != null) mapOf(sourceBundlePath to targetBundlePath) else emptyMap()
        val before = linkedMapOf(note.relativePath to sha256(markdown.toByteArray(StandardCharsets.UTF_8)))
        val after = linkedMapOf(targetNotePath to sha256(rewritten.toByteArray(StandardCharsets.UTF_8)))
        fun scan(directory: VaultDocument, sourcePrefix: String, targetPrefix: String): Boolean {
            val children = try { listChildrenStrict(tree, directory.uri, directory.relativePath) } catch (_: Exception) { return false }
            children.forEach { child ->
                val suffix = child.relativePath.removePrefix(sourcePrefix).removePrefix("/")
                val targetPath = joinRelativePath(targetPrefix, suffix)
                if (isDirectory(child)) {
                    if (!scan(child, sourcePrefix, targetPrefix)) return false
                } else {
                    val digest = resolver.openInputStream(child.uri)?.use(::sha256) ?: return false
                    mapping[child.relativePath] = targetPath
                    before[child.relativePath] = digest
                    after[targetPath] = digest
                }
            }
            return true
        }
        if (bundle != null && sourceBundlePath != null && !scan(bundle, sourceBundlePath, targetBundlePath)) return null
        return MoveBundleChange(id, tree.toString(), mapping, bundleMapping, before, after, LocalChangeState.PREPARED)
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest().joinToString("") { "%02x".format(it) }
            digest.update(buffer, 0, count)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest().joinToString("") { "%02x".format(it) }
            digest.update(buffer, 0, count)
        }
    }

    /** Never guesses after an interrupted move: verified-complete markers are cleaned, all other
     * states remain visible in the Vault for a later explicit recovery instead of deleting data. */
    private fun recoverNoteBundleMoves(tree: Uri): Boolean {
        val moves = findByRelativePath(tree, ".markbook/moves") ?: return true
        var parseFailure = false
        listChildrenStrict(tree, moves.uri, ".markbook/moves").forEach { marker ->
            val transaction = readText(marker)?.let(NoteBundleMoveTransaction::parse)
            if (transaction == null) {
                parseFailure = true
                return@forEach
            }
            val source = findByRelativePath(tree, transaction.sourceNote)
            val target = findByRelativePath(tree, transaction.targetNote)
            val sourceBundle = transaction.sourceBundle.takeIf { it.isNotBlank() }?.let { findByRelativePath(tree, it) }
            val targetBundle = transaction.targetBundle.takeIf { it.isNotBlank() }?.let { findByRelativePath(tree, it) }
            if (source == null && target != null && readText(target)?.let(::sha256) == transaction.rewrittenHash &&
                (transaction.sourceBundle.isBlank() || targetBundle != null) && verifyMoveTargets(tree, transaction.id)) {
                if (LocalChangeJournal(context).commit(transaction.id)) runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
                return@forEach
            }
            if (target == null && targetBundle == null &&
                source != null && readText(source)?.let(::sha256) == transaction.sourceHash &&
                (transaction.sourceBundle.isBlank() || sourceBundle != null)) {
                val journal = LocalChangeJournal(context)
                if (journal.changes(tree.toString())?.none { it.id == transaction.id } == true || journal.acknowledge(transaction.id)) {
                    runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
                }
                return@forEach
            }
            // Bundle moved but note did not: verified rollback restores the original readable state.
            if (source != null && readText(source)?.let(::sha256) == transaction.sourceHash &&
                sourceBundle == null && targetBundle != null && transaction.sourceBundle.isNotBlank()) {
                val originalParent = findByRelativePath(tree, transaction.sourceBundle.substringBeforeLast('/'))
                if (originalParent != null && DocumentsContract.moveDocument(resolver, targetBundle.uri,
                        targetBundle.parentUri ?: return@forEach, originalParent.uri) != null) {
                    if (LocalChangeJournal(context).acknowledge(transaction.id)) runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
                }
                return@forEach
            }
            // Note and bundle are at target but old Markdown remains: verified completion is safe.
            if (source == null && target != null && (transaction.sourceBundle.isBlank() || targetBundle != null) &&
                readText(target)?.let(::sha256) == transaction.sourceHash) {
                val rewritten = if (transaction.sourceBundle.isBlank()) {
                    readText(target) ?: return@forEach
                } else runCatching {
                    NoteBundleMovePolicy.rewriteBundleReferences(
                        readText(target) ?: return@forEach,
                        transaction.sourceNote.substringBeforeLast('/', ""),
                        transaction.targetNote.substringBeforeLast('/', ""),
                        transaction.sourceBundle, transaction.targetBundle
                    )
                }.getOrNull() ?: return@forEach
                if (sha256(rewritten) == transaction.rewrittenHash && saveText(target, rewritten)) {
                    if (verifyMoveTargets(tree, transaction.id) && LocalChangeJournal(context).commit(transaction.id)) {
                        runCatching { DocumentsContract.deleteDocument(resolver, marker.uri) }
                    }
                }
            }
        }
        return !parseFailure
    }

    private fun updateMoveMarker(marker: VaultDocument, stage: NoteBundleMoveTransaction.Stage): Boolean {
        val current = readText(marker)?.let(NoteBundleMoveTransaction::parse) ?: return false
        return writeMoveMarker(marker, current.withStage(stage))
    }

    private fun verifyMoveTargets(tree: Uri, changeId: String): Boolean {
        val change = LocalChangeJournal(context).changes(tree.toString())?.firstOrNull { it.id == changeId } ?: return false
        if (change.sourceToTarget.keys.any { findByRelativePath(tree, it) != null }) return false
        if (change.bundleSourceToTarget.any { (source, target) -> findByRelativePath(tree, source) != null || findByRelativePath(tree, target) == null }) return false
        return change.afterSha256.all { (path, expected) ->
            val document = findByRelativePath(tree, path) ?: return@all false
            val actual = resolver.openInputStream(document.uri)?.use(::sha256) ?: return@all false
            actual.equals(expected, true)
        }
    }

    private fun writeMoveMarker(marker: VaultDocument, transaction: NoteBundleMoveTransaction): Boolean = try {
        resolver.openOutputStream(marker.uri, "wt")?.use { output ->
            output.write(transaction.serialize().toByteArray(StandardCharsets.UTF_8)); output.flush()
        } != null
    } catch (_: Exception) { false }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun syncPathAllowed(path: String, directory: Boolean): Boolean {
        return SyncPathPolicy.isAllowed(path, directory)
    }

    private fun safeSyncPath(relativePath: String): String? {
        val parts = relativePath.split('/')
        if (parts.isEmpty() || parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) return null
        val clean = parts.joinToString("/")
        return clean.takeIf { syncPathAllowed(it, false) }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_SYNC_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private fun normalizeRelativePath(relativePath: String): String {
        val parts = mutableListOf<String>()
        relativePath.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun findOrCreateDirectory(tree: Uri, parts: List<String>): Uri? {
        var parent = rootDocument(tree)
        for (part in parts) {
            val existing = findChild(tree, parent, part)
            parent = existing?.uri ?: DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                part
            )
                ?: return null
        }
        return parent
    }

    private fun createChild(
        parent: VaultDocument,
        requestedName: String,
        kind: VaultEntryKind
    ): VaultMutationResult {
        if (!isManageableParent(parent)) return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED)
        val validated = VaultNamePolicy.validate(requestedName, kind)
        if (validated is VaultNameValidation.Invalid) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME, validated.message)
        }
        val validName = validated as VaultNameValidation.Valid
        if (kind == VaultEntryKind.FOLDER && parent.relativePath.isEmpty() && VaultPathPolicy.isProtectedRootName(validName.actualName)) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.INVALID_NAME, "该名称由 Vault 保留")
        }
        return try {
            val tree = savedVaultUri() ?: return VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
            val existing = listChildrenStrict(tree, parent.uri, parent.relativePath).map { it.name }
            if (VaultNamePolicy.conflicts(validName, existing)) {
                return VaultMutationResult.Failure(VaultMutationFailureKind.NAME_CONFLICT, "当前目录已有同名笔记或文件夹")
            }
            val mimeType = if (kind == VaultEntryKind.NOTE) "text/markdown" else DocumentsContract.Document.MIME_TYPE_DIR
            val created = DocumentsContract.createDocument(resolver, parent.uri, mimeType, validName.actualName)
                ?: return VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED)
            val resolved = resolveMutationDocument(parent.uri, parent.relativePath, created)
            VaultMutationResult.Success(
                resolved,
                validName.actualName,
                resolved?.name
            )
        } catch (_: SecurityException) {
            VaultMutationResult.Failure(VaultMutationFailureKind.PERMISSION_DENIED)
        } catch (_: Exception) {
            VaultMutationResult.Failure(VaultMutationFailureKind.CREATE_FAILED)
        }
    }

    private fun isManageableParent(document: VaultDocument): Boolean =
        isDirectory(document) && !isInternalPath(document.relativePath)

    private fun isManageable(document: VaultDocument): Boolean =
        document.relativePath.isNotEmpty() && !isInternalPath(document.relativePath)

    private fun isInternalPath(relativePath: String): Boolean =
        VaultPathPolicy.isProtected(relativePath)

    private fun resolveMutationDocument(parent: Uri, parentRelativePath: String, uri: Uri): VaultDocument? {
        val name = runCatching { documentName(uri) }.getOrNull()
        if (name != null) {
            return VaultDocument(uri, name, null, parent, joinRelativePath(parentRelativePath, name))
        }
        return runCatching {
            listChildrenStrict(savedVaultUri() ?: return null, parent, parentRelativePath)
                .firstOrNull { it.uri == uri }
        }.getOrNull()
    }

    private fun moveNoteToTrashByCopy(tree: Uri, document: VaultDocument, trash: Uri): VaultMutationResult {
        val sourceParent = document.parentUri ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        val existing = listChildrenStrict(tree, trash, ".trash").map { it.name }
        val fallbackName = VaultTrashPolicy.uniqueNoteName(
            document.name,
            existing,
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        )
        val copy = DocumentsContract.createDocument(resolver, trash, "text/markdown", fallbackName)
            ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        return try {
            resolver.openInputStream(document.uri)?.use { input ->
                resolver.openOutputStream(copy, "wt")?.use { output -> input.copyTo(output) }
                    ?: throw IllegalStateException("Unable to write trash copy")
            } ?: throw IllegalStateException("Unable to read note")
            if (!DocumentsContract.deleteDocument(resolver, document.uri)) {
                runCatching { DocumentsContract.deleteDocument(resolver, copy) }
                return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
            }
            val resolved = resolveMutationDocument(trash, ".trash", copy)
            VaultMutationResult.Success(resolved, document.name, resolved?.name)
        } catch (_: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED)
        }
    }

    private fun rewriteDailyDirectoryAfterRenameCommitted(oldPath: String, newPath: String): DailyDirectoryChange {
        val current = dailyNoteDirectoryPath()
        val rewritten = VaultRelativePath.renamedDailyDirectory(current, oldPath, newPath)
        if (rewritten == current) return DailyDirectoryChange.UNCHANGED
        preferences.edit().putString(DAILY_NOTE_DIRECTORY_KEY, rewritten).commit()
        return DailyDirectoryChange.REWRITTEN
    }

    private fun resetDailyDirectoryIfRemovedCommitted(removedPath: String): DailyDirectoryChange {
        val current = dailyNoteDirectoryPath()
        val reset = VaultRelativePath.resetIfRemoved(current, removedPath)
        if (reset == current) return DailyDirectoryChange.UNCHANGED
        preferences.edit().putString(DAILY_NOTE_DIRECTORY_KEY, reset)
            .putBoolean(DAILY_DIRECTORY_RESET_NOTICE_KEY, true).commit()
        return DailyDirectoryChange.RESET_TO_ROOT
    }

    private fun documentName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun joinRelativePath(parent: String, name: String): String =
        listOf(parent, name).filter { it.isNotEmpty() }.joinToString("/")

    private fun nextCaptureId(tree: Uri, directory: Uri): String? {
        val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        repeat(MAX_PHOTO_NAME_ATTEMPTS) {
            val suffix = buildString(PHOTO_RANDOM_LENGTH) {
                repeat(PHOTO_RANDOM_LENGTH) {
                    append(PHOTO_RANDOM_ALPHABET[photoRandom.nextInt(PHOTO_RANDOM_ALPHABET.length)])
                }
            }
            val id = "$timestamp-$suffix"
            val names = listChildren(tree, directory).map { it.name }
            if (!VideoCapturePolicy.captureIdCollides(names, id)) return id
        }
        return null
    }

    private fun findChild(tree: Uri, parent: Uri, name: String, parentRelativePath: String = ""): VaultDocument? {
        return listChildren(tree, parent, parentRelativePath).firstOrNull { it.name == name }
    }

    private fun findChildStrict(tree: Uri, parent: Uri, name: String, parentRelativePath: String = ""): VaultDocument? =
        listChildrenStrict(tree, parent, parentRelativePath).firstOrNull { it.name == name }

    private fun listChildren(tree: Uri, parent: Uri, parentRelativePath: String = ""): List<VaultDocument> = try {
        listChildrenStrict(tree, parent, parentRelativePath)
    } catch (_: Exception) {
        emptyList()
    }

    private fun listChildrenStrict(tree: Uri, parent: Uri, parentRelativePath: String = ""): List<VaultDocument> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val id = cursor.getString(idIndex) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    add(VaultDocument(
                        uri,
                        name,
                        cursor.getString(mimeIndex),
                        parent,
                        joinRelativePath(parentRelativePath, name),
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null,
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    ))
                }
            }
        } ?: throw IllegalStateException("Unable to enumerate Vault directory")
    }

    private fun recoverVaultDirectory(tree: Uri, directory: Uri, relativeDirectory: String) {
        listChildrenStrict(tree, directory).forEach { document ->
            val relativePath = listOf(relativeDirectory, document.name)
                .filter { it.isNotEmpty() }
                .joinToString("/")
            when {
                document.mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                    if (relativePath !in RECOVERY_EXCLUDED_DIRECTORIES) {
                        recoverVaultDirectory(tree, document.uri, relativePath)
                    }
                }
                else -> recoverArtifact(
                    tree,
                    directory,
                    document,
                    VaultRecoveryPolicy.classify(document.name, isAttachmentDirectory(relativeDirectory))
                )
            }
        }
    }

    private fun recoverArtifact(
        tree: Uri,
        directory: Uri,
        document: VaultDocument,
        artifact: RecoveryArtifact
    ) {
        when (artifact) {
            RecoveryArtifact.Ignore -> Unit
            RecoveryArtifact.DeleteTemporary -> {
                try { DocumentsContract.deleteDocument(resolver, document.uri) } catch (_: Exception) { }
            }
            is RecoveryArtifact.RestoreBackup -> {
                val existing = findChild(tree, directory, artifact.originalName)
                try {
                    if (existing == null) {
                        DocumentsContract.renameDocument(resolver, document.uri, artifact.originalName)
                    } else {
                        DocumentsContract.deleteDocument(resolver, document.uri)
                    }
                } catch (_: Exception) { }
            }
            RecoveryArtifact.ResolveAttachmentTransaction -> {
                val names = readText(document)?.lines()?.filter { it.isNotBlank() }.orEmpty()
                val referenceState = if (names.isEmpty()) AttachmentReferenceScan.UNREADABLE else attachmentReferenceState(tree, names)
                try {
                    when (AttachmentRecoveryPolicy.action(names.isNotEmpty(), referenceState)) {
                        AttachmentRecoveryAction.DELETE_FILES_AND_MARKER -> {
                        names.forEach { name ->
                            findChild(tree, directory, name)?.let {
                                DocumentsContract.deleteDocument(resolver, it.uri)
                            }
                        }
                        DocumentsContract.deleteDocument(resolver, document.uri)
                        }
                        AttachmentRecoveryAction.DELETE_MARKER_ONLY -> DocumentsContract.deleteDocument(resolver, document.uri)
                        AttachmentRecoveryAction.KEEP_MARKER_AND_FILES -> Unit
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun isAttachmentDirectory(relativeDirectory: String): Boolean =
        relativeDirectory.split('/').any { it in setOf("assets", "attachments") }

    private fun attachmentReferenceState(tree: Uri, names: List<String>): AttachmentReferenceScan =
        directoryReferenceState(tree, rootDocument(tree), "", names)

    private fun directoryReferenceState(
        tree: Uri,
        directory: Uri,
        relativeDirectory: String,
        names: List<String>
    ): AttachmentReferenceScan {
        for (document in listChildrenStrict(tree, directory)) {
            if (document.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val childPath = listOf(relativeDirectory, document.name)
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
                if (childPath.substringBefore('/') !in NOTE_SCAN_EXCLUDED_DIRECTORIES) {
                    when (directoryReferenceState(tree, document.uri, childPath, names)) {
                        AttachmentReferenceScan.REFERENCED -> return AttachmentReferenceScan.REFERENCED
                        AttachmentReferenceScan.UNREADABLE -> return AttachmentReferenceScan.UNREADABLE
                        AttachmentReferenceScan.UNREFERENCED -> Unit
                    }
                }
            } else if (document.name.endsWith(".md", ignoreCase = true)) {
                val content = readText(document) ?: return AttachmentReferenceScan.UNREADABLE
                if (names.any(content::contains)) return AttachmentReferenceScan.REFERENCED
            }
        }
        return AttachmentReferenceScan.UNREFERENCED
    }

    private fun rootDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree)
    )

    private object IntentFlags {
        const val READ_WRITE = 3
    }

    private fun assetFolderName(noteName: String): String {
        val stem = noteName.removeSuffix(".md")
        val cleaned = stem.map { character ->
            if (character in "<>:\"/\\|?*" || character == '\u0000') '_' else character
        }.joinToString("").trim().trim('.')
        return cleaned.ifEmpty { "Untitled" }
    }

    /** Prefer a moved note's co-located bundle; root bundles are legacy-compatible. */
    private fun attachmentDirectory(tree: Uri, note: VaultDocument): Pair<String, Uri>? {
        val stem = assetFolderName(note.name)
        val current = NoteBundleMovePolicy.bundlePath(note.relativePath)
        findByRelativePath(tree, current)?.takeIf(::isDirectory)?.let { return current to it.uri }
        val legacy = "assets/$stem"
        findByRelativePath(tree, legacy)?.takeIf(::isDirectory)?.let { return legacy to it.uri }
        val parts = note.parentRelativePath.split('/').filter { it.isNotBlank() } + listOf("assets", stem)
        val directory = findOrCreateDirectory(tree, parts) ?: return null
        return current to directory
    }

    private class VideoCopyCancelledException : Exception()

    companion object {
        private const val VAULT_URI_KEY = "vault_uri"
        private const val APPEARANCE_MODE_KEY = "appearance_mode"
        private const val SWIPE_DISCOVERY_HINT_SEEN_KEY = "swipe_discovery_hint_seen"
        private const val DAILY_NOTE_DIRECTORY_KEY = "daily_note_directory"
        private const val DAILY_DIRECTORY_RESET_NOTICE_KEY = "daily_note_directory_reset_notice"
        private const val PENDING_VIDEO_NOTE_URI = "pending_video_note_uri"
        private const val PENDING_VIDEO_NOTE_PATH = "pending_video_note_path"
        private const val PENDING_VIDEO_HASH = "pending_video_hash"
        private const val PENDING_VIDEO_CARET = "pending_video_caret"
        private const val PENDING_VIDEO_SCROLL = "pending_video_scroll"
        private const val PENDING_VIDEO_CACHE = "pending_video_cache"
        private const val PENDING_VIDEO_STAGE = "pending_video_stage"
        private const val PENDING_VIDEO_ATTACHMENT_PATH = "pending_video_attachment_path"
        private const val DEFAULT_DAILY_NOTE_DIRECTORY = "Daily Notes"
        const val APPEARANCE_DAY = "day"
        const val APPEARANCE_NIGHT = "night"
        private const val MAX_PHOTO_NAME_ATTEMPTS = 32
        private const val PHOTO_RANDOM_LENGTH = 4
        private const val PHOTO_RANDOM_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val DEFAULT_SYNC_BUFFER_BYTES = 32 * 1024
        private val RECOVERY_EXCLUDED_DIRECTORIES = setOf(".obsidian", ".trash", ".markbook/moves")
        private val NOTE_SCAN_EXCLUDED_DIRECTORIES = setOf(
            ".obsidian", ".trash", ".markbook", "assets", "attachments"
        )
        private val photoRandom = SecureRandom()
    }
}
