package io.github.ashrodan.herdrmonitor

// Enough GitHub-flavoured Markdown for agent chat, without a library:
// headings, paragraphs, - and 1. lists, > quotes, --- rules, ``` code,
// | tables |, and inline **bold**, *italic*, `code`, [links](url) and bare
// URLs. keepBreaks: single line breaks stay (as remark-breaks does for
// your own prompts).

sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Items(val ordered: Boolean, val start: Int, val items: List<String>) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Quote(val text: String) : Block
    data object Rule : Block
}

data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val url: String? = null)

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val NUMBER = Regex("^\\s{0,3}(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    fun blocks(src: String, keepBreaks: Boolean = false): List<Block> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Para(para.joinToString(if (keepBreaks) "\n" else " ") { it.trim() })
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val lang = t.drop(3).trim()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { body += lines[i]; i++ }
                    out += Block.Code(lang, body.joinToString("\n"))
                }
                t.isEmpty() -> flush()
                HEADING.matches(t) -> { flush(); HEADING.find(t)!!.let { out += Block.Heading(it.groupValues[1].length, it.groupValues[2]) } }
                RULE.matches(line) -> { flush(); out += Block.Rule }
                t.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(t)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += Block.Table(header, rows)
                    continue
                }
                t.startsWith(">") -> {
                    flush()
                    val body = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { body += lines[i].trim().removePrefix(">").trim(); i++ }
                    out += Block.Quote(body.joinToString(" "))
                    continue
                }
                BULLET.matches(line) || NUMBER.matches(line) -> {
                    flush()
                    val ordered = NUMBER.matches(line)
                    val start = if (ordered) NUMBER.find(line)!!.groupValues[1].toIntOrNull() ?: 1 else 1
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val m = (if (ordered) NUMBER else BULLET).find(l)
                        if (m != null) items += m.groupValues.last()
                        else if (l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty()) items[items.lastIndex] = items.last() + " " + l.trim()
                        else break
                        i++
                    }
                    out += Block.Items(ordered, start, items)
                    continue
                }
                else -> para += line
            }
            i++
        }
        flush()
        return out
    }

    private val INLINE = Regex(
        "`([^`]+)`" +                                  // 1 code
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +           // 2, 3 bold
            "|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)" + // 4 text, 5 url
            "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)" + // 6, 7 italic
            "|(https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])"  // 8 bare url
    )

    fun spans(text: String, bold: Boolean = false, italic: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > at) out += Span(text.substring(at, m.range.first), bold, italic)
            val g = m.groups
            when {
                g[1] != null -> out += Span(g[1]!!.value, bold, italic, code = true)
                g[2] != null || g[3] != null -> out += spans((g[2] ?: g[3])!!.value, true, italic)
                g[4] != null -> out += Span(g[4]!!.value, bold, italic, url = g[5]!!.value)
                g[6] != null || g[7] != null -> out += spans((g[6] ?: g[7])!!.value, bold, true)
                g[8] != null -> out += Span(g[8]!!.value, bold, italic, url = g[8]!!.value)
            }
            at = m.range.last + 1
        }
        if (at < text.length) out += Span(text.substring(at), bold, italic)
        return out
    }
}
