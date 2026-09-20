package com.jambus.heji

import java.util.regex.Pattern

/**
 * Renders Markdown into the editable HTML document used by the note editor, and defines the
 * contract the in-page JavaScript uses to serialize that document back to Markdown.
 *
 * Block structures the editor cannot edit without losing fidelity are rendered as read-only
 * verbatim blocks. Their original text is carried in `data-markbook-raw` and written back byte for
 * byte, so opening and saving a note never rewrites content the user did not touch.
 *
 * This object has no Android framework dependencies so it can be covered by unit tests.
 */
object MarkdownCodec {
    const val CARET_MARKER = "<!--MARKBOOK_CARET-->"

    private val IMAGE_PATTERN: Pattern = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)")
    private val BOLD_PATTERN = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC_PATTERN = Regex("\\*([^*]+)\\*")
    private val LINK_PATTERN = Regex("\\[([^]]+)]\\((?:<([^>]+)>|([^\\s)]+))\\)")
    private val ORDERED_ITEM = Regex("^\\d+[.)]\\s")
    private val TASK_ITEM = Regex("^[-*+]\\s+\\[[ xX]]")
    private val THEMATIC_BREAK = Regex("^(-{3,}|\\*{3,}|_{3,}|={3,})$")
    private val TABLE_DELIMITER = Regex("^[\\s|:-]*-{2,}[\\s|:-]*$")

    fun toHtml(
        markdown: String,
        attachmentUrl: (String) -> String,
        nightMode: Boolean = false
    ): String = document(renderBody(markdown, attachmentUrl), nightMode)

    /** Mirrors the in-page anchor serializer for destinations that require Markdown angle brackets. */
    fun serializeLink(label: String, destination: String): String =
        "[$label](${serializeLinkDestination(destination)})"

    fun serializeLinkDestination(destination: String): String =
        if (destination.any { it.isWhitespace() || it == '(' || it == ')' || it == '<' || it == '>' }) {
            "<$destination>"
        } else destination

    /** Renders the editable body only. Exposed so round-trip behaviour can be unit tested. */
    fun renderBody(markdown: String, attachmentUrl: (String) -> String): String {
        val source = sourceLines(markdown)
        val body = StringBuilder()
        var listOpen = false
        var index = 0

        fun closeList() {
            if (listOpen) {
                body.append("</ul>")
                listOpen = false
            }
        }

        val frontMatterEnd = frontMatterEnd(source)
        if (frontMatterEnd > 0) {
            body.append(verbatim(source.subList(0, frontMatterEnd).joinToString("\n")))
            index = frontMatterEnd
        }

        while (index < source.size) {
            val line = source[index]

            val fence = fenceMarker(line)
            if (fence != null) {
                closeList()
                var end = index + 1
                while (end < source.size && !source[end].trimStart().startsWith(fence)) end++
                val last = if (end < source.size) end else source.size - 1
                body.append(verbatim(source.subList(index, last + 1).joinToString("\n")))
                index = last + 1
                continue
            }

            if (isTableStart(source, index)) {
                closeList()
                var end = index
                while (end < source.size && source[end].isNotBlank() && source[end].contains('|')) end++
                body.append(verbatim(source.subList(index, end).joinToString("\n")))
                index = end
                continue
            }

            if (isVerbatimLine(line)) {
                closeList()
                var end = index
                while (end < source.size &&
                    isVerbatimLine(source[end]) &&
                    fenceMarker(source[end]) == null
                ) end++
                body.append(verbatim(source.subList(index, end).joinToString("\n")))
                index = end
                continue
            }

            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!listOpen) {
                    body.append("<ul>")
                    listOpen = true
                }
                body.append("<li>").append(inline(line.substring(2), attachmentUrl)).append("</li>")
                index++
                continue
            }
            closeList()

            when {
                line.isEmpty() -> body.append("<p><br></p>")
                line.startsWith("##### ") -> body.append("<h5>")
                    .append(inline(line.substring(6), attachmentUrl)).append("</h5>")
                line.startsWith("#### ") -> body.append("<h4>")
                    .append(inline(line.substring(5), attachmentUrl)).append("</h4>")
                line.startsWith("### ") -> body.append("<h3>")
                    .append(inline(line.substring(4), attachmentUrl)).append("</h3>")
                line.startsWith("## ") -> body.append("<h2>")
                    .append(inline(line.substring(3), attachmentUrl)).append("</h2>")
                line.startsWith("# ") -> body.append("<h1>")
                    .append(inline(line.substring(2), attachmentUrl)).append("</h1>")
                else -> body.append("<p>").append(inline(line, attachmentUrl)).append("</p>")
            }
            index++
        }
        closeList()
        return body.toString()
    }

    private fun sourceLines(markdown: String): List<String> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // A document ending with a newline yields a trailing empty element that is not a blank line.
        return if (lines.size > 1 && lines.last().isEmpty()) lines.subList(0, lines.size - 1) else lines
    }

    private fun frontMatterEnd(source: List<String>): Int {
        if (source.firstOrNull() != "---") return 0
        for (index in 1 until source.size) {
            if (source[index] == "---" || source[index] == "...") return index + 1
        }
        return 0
    }

    private fun fenceMarker(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) return "```"
        if (trimmed.startsWith("~~~")) return "~~~"
        return null
    }

    private fun isTableStart(source: List<String>, index: Int): Boolean {
        val line = source[index]
        if (line.isBlank() || !line.contains('|')) return false
        val delimiter = source.getOrNull(index + 1) ?: return false
        return delimiter.contains('|') && TABLE_DELIMITER.matches(delimiter)
    }

    /**
     * Lines the editable representation cannot reproduce exactly. Anything indented, quoted,
     * ordered, tabular or raw HTML is kept verbatim instead of being flattened into a paragraph.
     */
    private fun isVerbatimLine(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line[0] == ' ' || line[0] == '\t') return true
        if (line[0] == '>' || line[0] == '|' || line[0] == '<') return true
        if (line.startsWith("[^") || line.startsWith("+ ")) return true
        if (ORDERED_ITEM.containsMatchIn(line)) return true
        if (TASK_ITEM.containsMatchIn(line)) return true
        return THEMATIC_BREAK.matches(line)
    }

    private fun verbatim(raw: String): String = buildString {
        append("<pre class=\"markbook-raw\" contenteditable=\"false\" data-markbook-raw=\"")
        append(escapeAttribute(raw))
        append("\">")
        append(escapeHtml(raw))
        append("</pre>")
    }

    private fun document(body: String, nightMode: Boolean): String {
        val background = if (nightMode) "#18231e" else "#f7f7f2"
        val text = if (nightMode) "#eff6f1" else "#18201c"
        val heading = if (nightMode) "#ffffff" else "#18201c"
        val caret = if (nightMode) "#8dcfa8" else "#2f6b4f"
        val link = if (nightMode) "#a7e7c1" else "#246343"
        val rawBackground = if (nightMode) "#22302a" else "#ecefe9"
        val rawBorder = if (nightMode) "#8dcfa8" else "#2f6b4f"
        val rawText = if (nightMode) "#c3d3c9" else "#46514b"
        return """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:$background} #editor{box-sizing:border-box;max-width:760px;min-height:100vh;margin:0 auto;padding:20px 20px 64px;font-family:sans-serif;font-size:1.125rem;line-height:1.68;color:$text;caret-color:$caret;outline:none}
            img{display:block;max-width:100%;height:auto;margin:1em 0;border-radius:10px} .markbook-video{display:block;position:relative;max-width:100%;margin:1em 0;border-radius:10px;overflow:hidden;background:#000;color:#fff;line-height:1.25} .markbook-video video{display:block;width:100%;aspect-ratio:16/9;object-fit:contain;background:#000} .markbook-video-play{position:absolute;left:50%;top:50%;width:64px;height:64px;transform:translate(-50%,-50%);border:0;border-radius:50%;background:rgba(24,32,28,.82);color:#fff;font-size:30px;line-height:64px;text-align:center;padding:0 0 0 4px} .markbook-video.is-started .markbook-video-play,.markbook-video.is-playing .markbook-video-caption{display:none} .markbook-video-caption{display:block;position:absolute;left:0;right:0;bottom:0;padding:24px 12px 9px;background:linear-gradient(transparent,rgba(0,0,0,.76));font-size:.82em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;pointer-events:none} .markbook-inserted{outline:3px solid $caret;outline-offset:3px;border-radius:10px} h1,h2,h3,h4,h5{font-weight:700;line-height:1.24;color:$heading} h1{font-size:1.72em;margin:.35em 0 .7em} h2{font-size:1.32em;margin:1.65em 0 .58em} h3{font-size:1.15em;margin:1.45em 0 .48em} h4{font-size:1.02em;margin:1.28em 0 .4em} h5{font-size:.92em;margin:1.15em 0 .35em} p{margin:.72em 0} ul{margin:.7em 0;padding-left:1.4em} li{margin:.28em 0} a{color:$link;text-decoration:underline;text-decoration-thickness:.09em;text-underline-offset:.13em} strong{font-weight:700} em{font-style:italic}
            pre.markbook-raw{margin:1em 0;padding:11px 12px;border-left:3px solid $rawBorder;border-radius:8px;background:$rawBackground;color:$rawText;font-family:monospace;font-size:.9em;line-height:1.55;white-space:pre-wrap;word-break:break-word;user-select:text}</style></head>
            <body><div id="editor" contenteditable="true" spellcheck="true">$body</div></body>
            <script>
            (function() {
              var RAW_OPEN = '\u0000raw', RAW_CLOSE = '\u0000';
              var rawBlocks = [];
              function text(node) { return (node.textContent || '').replace(/\u00a0/g,' '); }
              function linkDestination(href) {
                return /[\s()<>]/.test(href) ? '<' + href + '>' : href;
              }
              function md(node) {
                if (node.nodeType === Node.TEXT_NODE) return text(node);
                if (node.nodeType !== Node.ELEMENT_NODE) return '';
                var tag = node.tagName.toLowerCase(), out = '';
                if (tag === 'pre' && node.hasAttribute('data-markbook-raw')) {
                  rawBlocks.push(node.getAttribute('data-markbook-raw'));
                  return RAW_OPEN + (rawBlocks.length - 1) + RAW_CLOSE + '\n';
                }
                if (tag === 'img') return '!['+(node.getAttribute('alt')||'image')+']('+(node.getAttribute('data-markdown')||'')+')';
                if (tag === 'span' && node.getAttribute('data-markbook-video') === 'true') {
                  return '[' + (node.getAttribute('data-markbook-label') || '视频') + '](' +
                    linkDestination(node.getAttribute('data-markdown') || '') + ')';
                }
                if (tag === 'span' && node.getAttribute('data-markbook-caret') === 'true') return '$CARET_MARKER';
                for (var i = 0; i < node.childNodes.length; i++) out += md(node.childNodes[i]);
                if (tag === 'h1') return '# ' + out.trim() + '\n';
                if (tag === 'h2') return '## ' + out.trim() + '\n';
                if (tag === 'h3') return '### ' + out.trim() + '\n';
                if (tag === 'h4') return '#### ' + out.trim() + '\n';
                if (tag === 'h5') return '##### ' + out.trim() + '\n';
                if (tag === 'li') return '- ' + out.trim() + '\n';
                if (tag === 'ul' || tag === 'ol') return out;
                if (tag === 'strong' || tag === 'b') return '**' + out + '**';
                if (tag === 'em' || tag === 'i') return '*' + out + '*';
                if (tag === 'a') return '[' + out + '](' + linkDestination(node.getAttribute('href') || '') + ')';
                if (tag === 'br') return '\n';
                if (tag === 'p' || tag === 'div') return out.trim() + '\n';
                return out;
              }
              var editor = document.getElementById('editor');
              var lastRange = null;
              var mediaTokenCounter = 0;
              function mediaNode(node) {
                while (node && node !== editor) {
                  if (node.nodeType === Node.ELEMENT_NODE && node.tagName.toLowerCase() === 'img' && node.hasAttribute('data-markdown')) return node;
                  if (node.nodeType === Node.ELEMENT_NODE && node.getAttribute('data-markbook-video') === 'true') return node;
                  node = node.parentNode;
                }
                return null;
              }
              function mediaBlock(node) {
                var parent = node ? node.parentNode : null;
                return parent && (parent.tagName.toLowerCase() === 'p' || parent.tagName.toLowerCase() === 'div') ? parent : node;
              }
              function mediaToken(node) {
                var token = node.getAttribute('data-markbook-media-token');
                if (!token) {
                  token = 'media-' + (++mediaTokenCounter);
                  node.setAttribute('data-markbook-media-token', token);
                }
                return token;
              }
              function ensureTrailingMediaLine() {
                var last = editor.lastElementChild;
                if (!last || (last.tagName.toLowerCase() !== 'p' && last.tagName.toLowerCase() !== 'div')) return null;
                var media = last.children.length === 1 ? mediaNode(last.firstElementChild) : null;
                if (!media || mediaBlock(media) !== last) return null;
                var tail = document.createElement('p');
                tail.className = 'markbook-media-tail';
                tail.innerHTML = '<br>';
                editor.appendChild(tail);
                return tail;
              }
              function placeCaretAfterMedia(node) {
                var block = mediaBlock(node), target = null;
                if (block && block !== node && block.children.length === 1) {
                  target = block.nextElementSibling;
                  if (!target) {
                    target = document.createElement('p');
                    target.className = 'markbook-media-tail';
                    target.innerHTML = '<br>';
                    block.parentNode.insertBefore(target, block.nextSibling);
                  }
                }
                var range = document.createRange(), selection = window.getSelection();
                if (target) {
                  range.selectNodeContents(target); range.collapse(true);
                } else {
                  range.setStartAfter(node); range.collapse(true);
                }
                selection.removeAllRanges(); selection.addRange(range); editor.focus();
                lastRange = range.cloneRange();
              }
              function selectionBelongsToEditor(selection) {
                return !!(selection && selection.rangeCount &&
                  editor.contains(selection.getRangeAt(0).commonAncestorContainer));
              }
              function rememberSelection() {
                var selection = window.getSelection();
                if (selectionBelongsToEditor(selection)) lastRange = selection.getRangeAt(0).cloneRange();
              }
              function restoreSelection() {
                editor.focus();
                if (!lastRange) return;
                var selection = window.getSelection();
                selection.removeAllRanges();
                selection.addRange(lastRange);
              }
              function notifyChange() {
                if (window.Android) Android.onChanged();
                notifyFormatState();
              }
              function notifyFormatState() {
                if (!window.Android || !Android.onFormatState) return;
                var block = (document.queryCommandValue('formatBlock') || 'p').toLowerCase();
                Android.onFormatState(JSON.stringify({
                  undo: document.queryCommandEnabled('undo'), redo: document.queryCommandEnabled('redo'),
                  heading: block !== 'p' && block !== 'div', bold: document.queryCommandState('bold'),
                  italic: document.queryCommandState('italic')
                }));
              }
              function selectedText() {
                var selection = window.getSelection();
                return selectionBelongsToEditor(selection) ? selection.toString() : '';
              }
              function applyFormat(command, value) {
                restoreSelection();
                var changed = false;
                if (command === 'undo' || command === 'redo' || command === 'bold' || command === 'italic') {
                  changed = document.execCommand(command, false, null);
                } else if (command === 'heading') {
                  changed = document.execCommand('formatBlock', false, value || 'p');
                } else if (command === 'tag') {
                  changed = document.execCommand('insertText', false, '#' + value);
                } else if (command === 'link') {
                  if (selectedText()) {
                    changed = document.execCommand('createLink', false, value);
                  } else {
                    var link = document.createElement('a');
                    link.href = value;
                    link.textContent = '链接文字';
                    changed = document.execCommand('insertHTML', false, link.outerHTML);
                  }
                } else if (command === 'table') {
                  changed = document.execCommand(
                    'insertText', false, '\n| 标题 1 | 标题 2 |\n| --- | --- |\n| 内容 | 内容 |\n'
                  );
                }
                rememberSelection();
                if (changed) notifyChange();
                return changed;
              }
              function serialize() {
                rawBlocks = [];
                var value = md(editor).replace(/\n+${'$'}/, '') + '\n';
                return value.replace(/\u0000raw(\d+)\u0000/g, function(match, index) { return rawBlocks[+index]; });
              }
              window.markbook = {
                serialize: serialize,
                serializeWithCaret: function() {
                  var selection = window.getSelection();
                  if (!selection || !selection.rangeCount || !editor.contains(selection.getRangeAt(0).commonAncestorContainer)) return serialize();
                  var range = selection.getRangeAt(0).cloneRange();
                  range.collapse(false);
                  var marker = document.createElement('span');
                  marker.setAttribute('data-markbook-caret','true');
                  range.insertNode(marker);
                  var value = serialize();
                  marker.remove();
                  return value;
                },
                focusAfterImage: function(path) {
                  var images = editor.querySelectorAll('img[data-markdown]');
                  for (var i = 0; i < images.length; i++) {
                    if (images[i].getAttribute('data-markdown') !== path) continue;
                    var range = document.createRange(), selection = window.getSelection();
                    range.setStartAfter(images[i]); range.collapse(true);
                    selection.removeAllRanges(); selection.addRange(range); editor.focus();
                    images[i].scrollIntoView({block:'nearest'});
                    if (!window.matchMedia || !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                      images[i].classList.add('markbook-inserted');
                      setTimeout(function() { images[i].classList.remove('markbook-inserted'); }, 900);
                    }
                    return true;
                  }
                  return false;
                },
                focusAfterLink: function(path) {
                  var links = editor.querySelectorAll('a[href],[data-markbook-video="true"]');
                  for (var i = 0; i < links.length; i++) {
                    var targetPath = links[i].getAttribute('data-markdown') || links[i].getAttribute('href');
                    if (targetPath !== path) continue;
                    var range = document.createRange(), selection = window.getSelection();
                    range.setStartAfter(links[i]); range.collapse(true);
                    selection.removeAllRanges(); selection.addRange(range); editor.focus();
                    links[i].scrollIntoView({block:'nearest'});
                    if (!window.matchMedia || !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                      links[i].classList.add('markbook-inserted');
                      setTimeout(function() { links[i].classList.remove('markbook-inserted'); }, 900);
                    }
                    return true;
                  }
                  return false;
                },
                removeExpectedMedia: function(token, path) {
                  var nodes = editor.querySelectorAll('[data-markbook-media-token]'), node = null;
                  for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].getAttribute('data-markbook-media-token') === token) { node = nodes[i]; break; }
                  }
                  if (!node || !editor.contains(node) || (node.getAttribute('data-markdown') || '') !== path) return false;
                  var block = mediaBlock(node);
                  node.remove();
                  if (block && block !== node && !block.querySelector('img[data-markdown],[data-markbook-video="true"]') && !text(block).trim()) {
                    block.innerHTML = '<br>';
                    var range = document.createRange(), selection = window.getSelection();
                    range.selectNodeContents(block); range.collapse(true);
                    selection.removeAllRanges(); selection.addRange(range); editor.focus();
                    lastRange = range.cloneRange();
                  }
                  return true;
                },
                applyFormat: applyFormat
              };
              document.addEventListener('selectionchange', function() { rememberSelection(); notifyFormatState(); });
              editor.addEventListener('keyup', rememberSelection);
              editor.addEventListener('mouseup', rememberSelection);
              editor.addEventListener('paste', function(event) {
                event.preventDefault();
                var clipboard = event.clipboardData || window.clipboardData;
                var plain = clipboard ? clipboard.getData('text/plain') : '';
                if (plain) document.execCommand('insertText', false, plain);
              });
              editor.addEventListener('input', function() { notifyChange(); });
              editor.addEventListener('contextmenu', function(event) {
                var media = mediaNode(event.target);
                if (!media || !window.Android || !window.Android.openMediaActions) return;
                event.preventDefault();
                Android.openMediaActions(
                  media.getAttribute('data-markbook-video') === 'true' ? 'video' : 'image',
                  media.getAttribute('data-markdown') || '',
                  mediaToken(media)
                );
              });
              editor.addEventListener('click', function(event) {
                var node = event.target;
                if (node && node.classList && node.classList.contains('markbook-video-play')) {
                  var card = node.parentNode, video = card ? card.querySelector('video') : null;
                  if (video) {
                    event.preventDefault();
                    video.controls = true;
                    card.classList.add('is-started');
                    video.play();
                    return;
                  }
                }
                var media = mediaNode(node);
                if (media && (!node.tagName || node.tagName.toLowerCase() !== 'video')) {
                  placeCaretAfterMedia(media);
                  return;
                }
                if (!node || node.tagName.toLowerCase() !== 'a') return;
                var href = node.getAttribute('href') || '';
                if (/^https?:/i.test(href) || !window.Android || !window.Android.openAttachment) return;
                event.preventDefault(); Android.openAttachment(href);
              });
              var videos = editor.querySelectorAll('[data-markbook-video="true"]');
              for (var v = 0; v < videos.length; v++) {
                (function(card) {
                  var media = card.querySelector('video');
                  if (!media) return;
                  media.addEventListener('play', function() { card.classList.add('is-playing'); });
                  media.addEventListener('pause', function() { card.classList.remove('is-playing'); });
                  media.addEventListener('ended', function() { card.classList.remove('is-playing'); });
                })(videos[v]);
              }
              ensureTrailingMediaLine();
              setTimeout(notifyFormatState, 0);
            })();
            </script></html>
        """.trimIndent()
    }

    private fun inline(value: String, attachmentUrl: (String) -> String): String {
        val matcher = IMAGE_PATTERN.matcher(value)
        val out = StringBuilder()
        var last = 0
        while (matcher.find()) {
            out.append(inlineText(value.substring(last, matcher.start()), attachmentUrl))
            val alt = matcher.group(1).orEmpty()
            val path = matcher.group(2).orEmpty()
            out.append("<img alt=\"").append(escapeAttribute(alt))
                .append("\" data-markdown=\"").append(escapeAttribute(path))
                .append("\" src=\"").append(escapeAttribute(attachmentUrl(path))).append("\">")
            last = matcher.end()
        }
        out.append(inlineText(value.substring(last), attachmentUrl))
        return out.toString()
    }

    /** Parses Markdown links before HTML escaping so `<path with spaces>` remains a link. */
    private fun inlineText(value: String, attachmentUrl: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (match in LINK_PATTERN.findAll(value)) {
            out.append(emphasize(escapeHtml(value.substring(last, match.range.first))))
            val destination = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val label = match.groupValues[1]
            if (isLocalVideo(destination)) {
                out.append("<span class=\"markbook-video\" contenteditable=\"false\" data-markbook-video=\"true\"")
                    .append(" data-markbook-label=\"").append(escapeAttribute(label))
                    .append("\" data-markdown=\"").append(escapeAttribute(destination)).append("\">")
                    .append("<video playsinline preload=\"metadata\" src=\"")
                    .append(escapeAttribute(attachmentUrl(destination))).append("\" aria-label=\"")
                    .append(escapeAttribute(label)).append("\"></video>")
                    .append("<button type=\"button\" class=\"markbook-video-play\" aria-label=\"播放 ")
                    .append(escapeAttribute(label)).append("\">&#9654;</button>")
                    .append("<span class=\"markbook-video-caption\">").append(escapeHtml(label)).append("</span></span>")
            } else {
                out.append("<a href=\"").append(escapeAttribute(destination)).append("\">")
                    .append(emphasize(escapeHtml(label))).append("</a>")
            }
            last = match.range.last + 1
        }
        out.append(emphasize(escapeHtml(value.substring(last))))
        return out.toString()
    }

    private fun isLocalVideo(destination: String): Boolean {
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(destination)) return false
        val path = destination.substringBefore('?').substringBefore('#')
        return path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".3gp", ignoreCase = true)
    }

    private fun emphasize(escaped: String): String = escaped
        .replace(BOLD_PATTERN, "<strong>$1</strong>")
        .replace(ITALIC_PATTERN, "<em>$1</em>")

    private fun escapeHtml(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    private fun escapeAttribute(value: String): String = escapeHtml(value)
        .replace("\n", "&#10;")
        .replace("\t", "&#9;")
}
