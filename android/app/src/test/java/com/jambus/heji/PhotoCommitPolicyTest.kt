package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCommitPolicyTest {
    private val link = "![scan](assets/note/scan-c.jpg)"

    @Test fun `confirmed link commits attachments`() {
        assertEquals(PhotoPostSaveAction.CONFIRM, PhotoCommitPolicy.afterSave("before\n$link\nafter", link))
    }

    @Test fun `confirmed absent link rolls attachments back`() {
        assertEquals(PhotoPostSaveAction.ROLLBACK, PhotoCommitPolicy.afterSave("unchanged body", link))
    }

    @Test fun `unreadable body conservatively retains marker and attachments`() {
        assertEquals(PhotoPostSaveAction.RETAIN_FOR_RECOVERY, PhotoCommitPolicy.afterSave(null, link))
    }

    @Test fun `external body change blocks commit`() {
        val expected = PhotoCommitPolicy.bodySha256("saved body")
        assertEquals(true, PhotoCommitPolicy.canBeginCommit(expected, "saved body"))
        assertEquals(false, PhotoCommitPolicy.canBeginCommit(expected, "externally changed"))
        assertEquals(false, PhotoCommitPolicy.canBeginCommit(expected, null))
    }

    @Test fun `canBeginCommit with empty body succeeds when expected hash matches empty body`() {
        val expected = PhotoCommitPolicy.bodySha256("")
        assertEquals(true, PhotoCommitPolicy.canBeginCommit(expected, ""))
        assertEquals(false, PhotoCommitPolicy.canBeginCommit(null, ""))
        assertEquals(false, PhotoCommitPolicy.canBeginCommit(expected, "not empty"))
    }

    @Test fun `insertPhotoAtCapturePoint inserts link at caret marker`() {
        val base = "hello ${MarkdownCodec.CARET_MARKER} world\n"
        val result = PhotoCommitPolicy.insertPhotoAtCapturePoint(base, link)
        assertEquals("hello \n\n$link\n\n world\n", result)
    }

    @Test fun `insertPhotoAtCapturePoint in empty note trims leading whitespace`() {
        val result = PhotoCommitPolicy.insertPhotoAtCapturePoint(MarkdownCodec.CARET_MARKER, link)
        assertEquals("$link\n", result)

        val resultFromBlank = PhotoCommitPolicy.insertPhotoAtCapturePoint("${MarkdownCodec.CARET_MARKER}\n", link)
        assertEquals("$link\n", resultFromBlank)
    }

    @Test fun `insertPhotoAtCapturePoint appends link when marker absent`() {
        val base = "# Title\n"
        val result = PhotoCommitPolicy.insertPhotoAtCapturePoint(base, link)
        assertEquals("# Title\n\n$link\n\n", result)
    }

    @Test fun `mapNormalizedOffsetToRaw accounts for CRLF line endings`() {
        val raw = "12345\r\n12345\r\n12345"
        assertEquals(0, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 0))
        assertEquals(5, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 5))
        assertEquals(7, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 6))
        assertEquals(12, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 11))
        assertEquals(14, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 12))
        assertEquals(raw.length, PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 100))
    }

    @Test fun `adjustToSafeTokenBoundary avoids splitting CRLF inline code and links`() {
        val withCrlf = "abc\r\ndef"
        // Offset 4 is right on '\n' of "\r\n"
        assertEquals(5, PhotoCommitPolicy.adjustToSafeTokenBoundary(withCrlf, 4))

        val withCode = "hello `int x = 10;` world"
        // Offset 10 is inside `int x = 10;`
        val codeEnd = withCode.indexOf('`', 7) + 1
        assertEquals(codeEnd, PhotoCommitPolicy.adjustToSafeTokenBoundary(withCode, 10))

        val withLink = "prefix [Click Here](https://heji.app) suffix"
        val linkStart = withLink.indexOf('[')
        val linkEnd = withLink.indexOf(')') + 1
        assertEquals(linkEnd, PhotoCommitPolicy.adjustToSafeTokenBoundary(withLink, linkStart + 3))

        val withImg = "prefix ![diagram](assets/a/1.jpg) suffix"
        val imgStart = withImg.indexOf("![")
        val imgEnd = withImg.indexOf(')') + 1
        assertEquals(imgEnd, PhotoCommitPolicy.adjustToSafeTokenBoundary(withImg, imgStart + 5))
    }

    @Test fun `safeInsertPoint combines CRLF compensation and boundary snapping`() {
        val raw = "# Title\r\nSee [docs](https://heji.app/doc) for details.\r\n"
        // In normalized text, "# Title\nSee [docs" -> 7 + 1 + 8 = 16 (inside the link)
        val safePoint = PhotoCommitPolicy.safeInsertPoint(raw, 16)
        val linkEnd = raw.indexOf(')') + 1
        assertEquals(linkEnd, safePoint)
    }

    @Test fun `safeInsertPoint with trailing space normalization does not slice word`() {
        val raw = "# Title  \nHello"
        val normalized = "# Title\nHello"
        // In normalized, "# Title\nHello" length is 13. Cursor after Hello is at 13.
        val mappedRaw = PhotoCommitPolicy.mapNormalizedOffsetToRaw(raw, 13, normalized)
        assertEquals(raw.length, mappedRaw)

        val safe = PhotoCommitPolicy.safeInsertPoint(raw, 13, normalized)
        assertEquals(raw.length, safe)

        val baseWithCaret = raw.substring(0, safe) + MarkdownCodec.CARET_MARKER + raw.substring(safe)
        val inserted = PhotoCommitPolicy.insertPhotoAtCapturePoint(baseWithCaret, link)
        assertEquals("# Title  \nHello\n\n$link\n", inserted)
    }

    @Test fun `safeInsertPoint with collapsed blank lines preserves blank line position`() {
        val raw = "Para 1\n\n\n\nPara 2"
        val normalized = "Para 1\n\nPara 2"
        // In normalized, cursor is on blank line (offset 7)
        val safe = PhotoCommitPolicy.safeInsertPoint(raw, 7, normalized)
        // Offset 7 in raw is after the first newline, in the blank lines region
        assertEquals(true, safe in 7..9)
    }

    @Test fun `adjustToSafeTokenBoundary snaps out of fenced code block`() {
        val raw = "prefix\n```kotlin\nval a = 1\nval b = 2\n```\nsuffix"
        val cursor = raw.indexOf("val a = 1")
        val expectedAfterFence = raw.indexOf("```\nsuffix") + 4
        assertEquals(expectedAfterFence, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `adjustToSafeTokenBoundary snaps out of markdown table`() {
        val raw = "| Col1 | Col2 |\n| --- | --- |\n| Val1 | Val2 |\n\nAfter table"
        val cursor = raw.indexOf("Val1")
        val expectedAfterTable = raw.indexOf("\n\nAfter table") + 1
        assertEquals(expectedAfterTable, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `adjustToSafeTokenBoundary snaps out of YAML frontmatter`() {
        val raw = "---\ntitle: Sample Note\ntags: [a, b]\n---\nBody text"
        val cursor = raw.indexOf("Sample Note")
        val expectedAfterFrontmatter = raw.indexOf("---\nBody text") + 4
        assertEquals(expectedAfterFrontmatter, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `adjustToSafeTokenBoundary snaps out of ATX heading`() {
        val raw = "# Important Heading\nParagraph"
        val cursor = raw.indexOf("Important")
        val expectedAfterHeading = raw.indexOf('\n') + 1
        assertEquals(expectedAfterHeading, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `adjustToSafeTokenBoundary snaps out of Setext heading`() {
        val raw = "Heading 1\n=========\nParagraph"
        val cursor = raw.indexOf("Heading 1") + 3
        val expectedAfterHeading = raw.indexOf("=========\n") + 10
        assertEquals(expectedAfterHeading, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `adjustToSafeTokenBoundary snaps trailing spaces on line to line end`() {
        val raw = "Paragraph line   \nNext line"
        val cursor = raw.indexOf("line") + 4 // right after word 'line', before trailing spaces
        val expectedLineEnd = raw.indexOf("   \n") + 3
        assertEquals(expectedLineEnd, PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, cursor))
    }

    @Test fun `precomputed raw offset survives rotation without normalized content`() {
        val raw = "# Title  \nHello"
        val normalized = "# Title\nHello"
        // 1. At capture time: compute raw offset using raw, normOffset, and normalized
        val normOffset = 13 // after Hello in normalized
        val precomputedRawOffset = PhotoCommitPolicy.safeInsertPoint(raw, normOffset, normalized)
        assertEquals(raw.length, precomputedRawOffset)

        // 2. Simulate rotation: normalized content is null, but precomputedRawOffset is restored from bundle
        val restoredOffset = precomputedRawOffset
        val safeAtCommit = PhotoCommitPolicy.adjustToSafeTokenBoundary(raw, restoredOffset)
        assertEquals(raw.length, safeAtCommit)

        val baseWithCaret = raw.substring(0, safeAtCommit) + MarkdownCodec.CARET_MARKER + raw.substring(safeAtCommit)
        val inserted = PhotoCommitPolicy.insertPhotoAtCapturePoint(baseWithCaret, link)
        assertEquals("# Title  \nHello\n\n$link\n", inserted)
    }
}
