package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rendering half of the Markdown round trip. Structures that cannot be edited without
 * losing fidelity must reach the editor as verbatim blocks carrying their original text, because
 * the in-page serializer writes that text back byte for byte.
 */
class MarkdownCodecTest {

    private val attachmentUrl: (String) -> String = { path -> "markbook://attachment/$path" }

    private fun render(markdown: String) = MarkdownCodec.renderBody(markdown, attachmentUrl)

    private fun verbatimBlocks(html: String): List<String> =
        Regex("data-markbook-raw=\"([^\"]*)\"").findAll(html)
            .map { decodeAttribute(it.groupValues[1]) }
            .toList()

    private fun decodeAttribute(value: String): String = value
        .replace("&#10;", "\n")
        .replace("&#9;", "\t")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    @Test
    fun headingsAndParagraphsStayEditable() {
        val html = render("# Title\n#### Detail\n##### Fine detail\n\nBody text\n")
        assertEquals(
            "<h1>Title</h1><h4>Detail</h4><h5>Fine detail</h5><p><br></p><p>Body text</p>",
            html
        )
    }

    @Test
    fun topLevelBulletsStayEditable() {
        val html = render("- one\n- two\n")
        assertEquals("<ul><li>one</li><li>two</li></ul>", html)
    }

    @Test
    fun eachBlankLineBecomesItsOwnParagraph() {
        val html = render("a\n\n\n\nb\n")
        assertEquals(3, Regex("<p><br></p>").findAll(html).count())
    }

    @Test
    fun fencedCodeBlockKeepsIndentationVerbatim() {
        val source = "```kotlin\nfun main() {\n    println(\"hi\")\n}\n```\n"
        assertEquals(
            listOf("```kotlin\nfun main() {\n    println(\"hi\")\n}\n```"),
            verbatimBlocks(render(source))
        )
    }

    @Test
    fun unterminatedFenceIsStillCapturedVerbatim() {
        val source = "```\nstill open\n"
        assertEquals(listOf("```\nstill open"), verbatimBlocks(render(source)))
    }

    @Test
    fun tableIsVerbatim() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |\n"
        assertEquals(listOf("| a | b |\n| --- | --- |\n| 1 | 2 |"), verbatimBlocks(render(source)))
    }

    @Test
    fun orderedListIsVerbatim() {
        assertEquals(listOf("1. one\n2. two"), verbatimBlocks(render("1. one\n2. two\n")))
    }

    @Test
    fun nestedBulletsAreVerbatim() {
        val source = "- parent\n  - child\n"
        val html = render(source)
        assertEquals(listOf("  - child"), verbatimBlocks(html))
        assertTrue(html.contains("<li>parent</li>"))
    }

    @Test
    fun blockquoteIsVerbatim() {
        assertEquals(listOf("> quoted\n> more"), verbatimBlocks(render("> quoted\n> more\n")))
    }

    @Test
    fun taskListIsVerbatim() {
        assertEquals(listOf("- [ ] todo\n- [x] done"), verbatimBlocks(render("- [ ] todo\n- [x] done\n")))
    }

    @Test
    fun thematicBreakIsVerbatim() {
        assertEquals(listOf("---"), verbatimBlocks(render("a\n\n---\n\nb\n")))
    }

    @Test
    fun frontMatterIsVerbatim() {
        val source = "---\ntitle: note\ntags:\n  - a\n---\n\nbody\n"
        val blocks = verbatimBlocks(render(source))
        assertEquals("---\ntitle: note\ntags:\n  - a\n---", blocks.first())
    }

    @Test
    fun indentedCodeBlockIsVerbatim() {
        assertEquals(listOf("    indented code"), verbatimBlocks(render("text\n\n    indented code\n")))
    }

    @Test
    fun rawHtmlLineIsVerbatim() {
        assertEquals(listOf("<div class=\"x\">raw</div>"), verbatimBlocks(render("<div class=\"x\">raw</div>\n")))
    }

    @Test
    fun markupCharactersAreEscapedExactlyOnce() {
        assertEquals("<p>a &lt; b &amp; c &gt; d</p>", render("a < b & c > d\n"))
    }

    @Test
    fun imageKeepsOriginalRelativePath() {
        val html = render("![shot](../assets/Note/120000-ab12-c.jpg)\n")
        assertTrue(html.contains("data-markdown=\"../assets/Note/120000-ab12-c.jpg\""))
        assertTrue(html.contains("src=\"markbook://attachment/../assets/Note/120000-ab12-c.jpg\""))
        assertTrue(html.contains("alt=\"shot\""))
    }

    @Test
    fun localVideoLinkRendersAsInlinePlayableCard() {
        val path = "../assets/会议 记录/120000-ab12-v.mp4"
        val html = render("[视频 00:00:05](<$path>)\n")
        assertTrue(html.contains("data-markbook-video=\"true\""))
        assertTrue(html.contains("data-markbook-label=\"视频 00:00:05\""))
        assertTrue(html.contains("data-markdown=\"$path\""))
        assertTrue(html.contains("src=\"markbook://attachment/$path\""))
        assertTrue(html.contains("<video playsinline preload=\"metadata\""))
        assertTrue(!html.contains("<video controls"))
        assertTrue(html.contains("class=\"markbook-video-play\""))
    }

    @Test
    fun videoLinkWithSpacesAndParenthesesRoundTripsThroughAnchorSerialization() {
        val destination = "../assets/会议 记录/120000-ab12-v (final).mp4"
        val markdown = MarkdownCodec.serializeLink("视频 00:00:05", destination)
        assertEquals("[视频 00:00:05](<$destination>)", markdown)
        val html = render(markdown + "\n")
        assertTrue(html.contains("data-markdown=\"$destination\""))
        assertTrue(html.contains("data-markbook-label=\"视频 00:00:05\""))
    }

    @Test
    fun externalVideoUrlAndNonVideoAttachmentRemainLinks() {
        assertEquals(
            "<p><a href=\"https://example.com/movie.mp4\">remote</a></p>",
            render("[remote](https://example.com/movie.mp4)\n")
        )
        assertEquals(
            "<p><a href=\"../assets/manual.pdf\">manual</a></p>",
            render("[manual](../assets/manual.pdf)\n")
        )
    }

    @Test
    fun emphasisIsRendered() {
        assertEquals("<p><strong>bold</strong> and <em>italic</em></p>", render("**bold** and *italic*\n"))
    }

    @Test
    fun standardMarkdownLinksStayEditable() {
        assertEquals(
            "<p>Read <a href=\"https://obsidian.md\">Obsidian</a></p>",
            render("Read [Obsidian](https://obsidian.md)\n")
        )
    }

    @Test
    fun documentUsesReadableThemeStylesWithoutChangingBodyRendering() {
        val day = MarkdownCodec.toHtml("# 标题\n\n[链接](https://obsidian.md)\n", attachmentUrl, false)
        val night = MarkdownCodec.toHtml("# 标题\n\n[链接](https://obsidian.md)\n", attachmentUrl, true)

        assertTrue(day.contains("text-decoration:underline"))
        assertTrue(day.contains(".markbook-video video"))
        assertTrue(day.contains("aspect-ratio:16/9"))
        assertTrue(day.contains("video.controls = true"))
        assertTrue(day.contains("is-started"))
        assertTrue(day.contains("ensureTrailingMediaLine()"))
        assertTrue(day.contains("Android.openMediaActions"))
        assertTrue(day.contains("removePendingMedia"))
        assertTrue(day.contains("<h1>标题</h1>"))
        assertTrue(day.contains("<a href=\"https://obsidian.md\">链接</a>"))
        assertTrue(night.contains("#a7e7c1"))
    }

    @Test
    fun documentWithoutTrailingNewlineKeepsLastLine() {
        assertEquals("<p>last</p>", render("last"))
    }

    @Test
    fun mixedDocumentClassifiesEveryBlock() {
        val source = buildString {
            append("---\ntitle: t\n---\n")
            append("\n")
            append("# Heading\n")
            append("\n")
            append("Paragraph with `code`.\n")
            append("\n")
            append("```\nblock\n```\n")
            append("\n")
            append("1. first\n")
            append("\n")
            append("> quote\n")
        }
        assertEquals(
            listOf("---\ntitle: t\n---", "```\nblock\n```", "1. first", "> quote"),
            verbatimBlocks(render(source))
        )
    }
}
