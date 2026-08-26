package ui

// Small Markdown-to-HTML converter covering only what Swing's JEditorPane can render (roughly
// HTML 3.2) and what BitBucket PR descriptions actually use: paragraphs, *emphasis*, `code`,
// links, simple lists. Not CommonMark-complete by design.

/** Converts [markdown] to a fragment of HTML (no surrounding `<html>`/`<body>` tags). */
fun markdownToHtml(markdown: String): String {
    if (markdown.isBlank()) return ""
    return markdown.replace("\r\n", "\n").trim()
            .split(Regex("\n\\s*\n")) // blank line(s) separate paragraphs/blocks
            .joinToString("") { renderBlock(it) }
}

private val BULLET_LINE = Regex("""^\s*[-*]\s+(.*)$""")
private val NUMBERED_LINE = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val HEADING_LINE = Regex("""^(#{1,6})\s+(.*)$""")

private fun renderBlock(block: String): String {
    val lines = block.trim('\n').split("\n").filter { it.isNotBlank() }
    if (lines.isEmpty()) return ""

    if (lines.all { BULLET_LINE.matches(it) }) {
        return lines.joinToString("", "<ul>", "</ul>") {
            "<li>${renderInline(BULLET_LINE.matchEntire(it)!!.groupValues[1])}</li>"
        }
    }
    if (lines.all { NUMBERED_LINE.matches(it) }) {
        return lines.joinToString("", "<ol>", "</ol>") {
            "<li>${renderInline(NUMBERED_LINE.matchEntire(it)!!.groupValues[1])}</li>"
        }
    }
    if (lines.size == 1) {
        val heading = HEADING_LINE.matchEntire(lines[0])
        if (heading != null) {
            val level = heading.groupValues[1].length
            return "<h$level>${renderInline(heading.groupValues[2])}</h$level>"
        }
    }
    return lines.joinToString("<br>", "<p>", "</p>") { renderInline(it) }
}

// Escapes first, then only builds tags around already-escaped text (no injection). Only
// *asterisk* emphasis, not _underscore_ — the latter would mangle snake_case identifiers.
private fun renderInline(text: String): String {
    var html = escapeHtml(text)
    html = Regex("""\[([^\[\]]+)]\((https?://[^\s()]+)\)""").replace(html) {
        "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
    }
    html = Regex("""\*\*(.+?)\*\*""").replace(html) { "<b>${it.groupValues[1]}</b>" }
    html = Regex("""\*(.+?)\*""").replace(html) { "<i>${it.groupValues[1]}</i>" }
    html = Regex("""`([^`]+)`""").replace(html) { "<code>${it.groupValues[1]}</code>" }
    return html
}

// Not private: also used by PRComponent for the author name.
fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
