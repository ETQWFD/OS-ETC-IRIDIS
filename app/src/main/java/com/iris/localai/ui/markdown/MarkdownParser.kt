package com.iris.localai.ui.markdown

/**
 * A small, dependency-free Markdown block parser tuned for LLM output.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 *
 * Supported: ATX headings, paragraphs, fenced code (``` and ~~~, unterminated fences render
 * as code while streaming), block quotes, ordered/unordered/task lists with nesting,
 * GitHub tables, thematic breaks, and $$ display math (shown as code).
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val text: String, val checked: Boolean?) : MdBlock
    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock

    enum class Align { START, CENTER, END }
}

object MarkdownParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val UL = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val OL = Regex("^(\\s*)(\\d{1,9})([.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<MdBlock> = parseLines(source.replace("\r\n", "\n").split('\n'))

    private fun parseLines(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trim().startsWith(marker.take(3)) && l.trim().all { it == marker[0] }) { closed = true; break }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(l)
                    i++
                }
                out += MdBlock.Code(lang, code.toString(), closed)
                i++
                continue
            }

            if (t == "$$") {
                flush()
                val math = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != "$$") {
                    if (math.isNotEmpty()) math.append('\n')
                    math.append(lines[i]); i++
                }
                out += MdBlock.Code("math", math.toString(), closed = i < lines.size)
                i++
                continue
            }

            when {
                t.isEmpty() -> flush()
                HEADING.matches(t) -> {
                    flush()
                    val m = HEADING.matchEntire(t)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                RULE.matches(line) -> { flush(); out += MdBlock.Rule }
                t.startsWith(">") -> {
                    flush()
                    val inner = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += MdBlock.Quote(parseLines(inner))
                    continue
                }
                t.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    val align = cells(lines[i + 1]).map {
                        when {
                            it.startsWith(":") && it.endsWith(":") -> MdBlock.Align.CENTER
                            it.endsWith(":") -> MdBlock.Align.END
                            else -> MdBlock.Align.START
                        }
                    }
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i]); i++
                    }
                    out += MdBlock.Table(header, align, rows)
                    continue
                }
                UL.matches(line) -> {
                    flush()
                    val m = UL.matchEntire(line)!!
                    val (checked, text) = task(m.groupValues[3])
                    out += MdBlock.ListItem(depth(m.groupValues[1]), "•", text, checked)
                }
                OL.matches(line) -> {
                    flush()
                    val m = OL.matchEntire(line)!!
                    out += MdBlock.ListItem(depth(m.groupValues[1]), m.groupValues[2] + ".", m.groupValues[4], null)
                }
                // Lazy continuation of a list item.
                para.isEmpty() && out.lastOrNull() is MdBlock.ListItem && line.startsWith("  ") -> {
                    val last = out.removeAt(out.lastIndex) as MdBlock.ListItem
                    out += last.copy(text = last.text + "\n" + t)
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(t)
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun depth(indent: String): Int {
        val spaces = indent.sumOf { if (it == '\t') 4L else 1L }.toInt()
        return (spaces / 2).coerceAtMost(6)
    }

    private fun task(text: String): Pair<Boolean?, String> {
        val m = TASK.matchEntire(text) ?: return null to text
        return (m.groupValues[1] != " ") to m.groupValues[2]
    }

    internal fun cells(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (c == '|') { out += cur.toString().trim(); cur.setLength(0) } else cur.append(c)
            i++
        }
        out += cur.toString().trim()
        return out
    }
}

/** Inline spans: **bold**, *italic*, `code`, ~~strike~~, [link](url), <https://autolink>, $math$. */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

object InlineParser {
    private val TOKEN = Regex(
        "`([^`\\n]+)`" +                                   // 1 code
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +              // 2,3 bold
            "|~~(.+?)~~" +                                // 4 strike
            "|\\[([^\\]\\n]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)" + // 5,6 link
            "|<(https?://[^>\\s]+)>" +                    // 7 autolink
            "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])" + // 8 italic *
            "|(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])" +     // 9 italic _
            "|(?<![\\w$])\\$(?![\\s$])([^$\\n]+?)(?<!\\s)\\$(?![\\w$])", // 10 inline math
    )

    fun parse(text: String): List<MdInline> {
        val out = ArrayList<MdInline>()
        var last = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > last) out += MdInline.Text(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> MdInline.Code(g[1])
                g[2].isNotEmpty() -> MdInline.Bold(parse(g[2]))
                g[3].isNotEmpty() -> MdInline.Bold(parse(g[3]))
                g[4].isNotEmpty() -> MdInline.Strike(parse(g[4]))
                g[5].isNotEmpty() -> MdInline.Link(g[5], g[6])
                g[7].isNotEmpty() -> MdInline.Link(g[7], g[7])
                g[8].isNotEmpty() -> MdInline.Italic(parse(g[8]))
                g[9].isNotEmpty() -> MdInline.Italic(parse(g[9]))
                g[10].isNotEmpty() -> MdInline.Code(g[10])
                else -> MdInline.Text(m.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdInline.Text(text.substring(last))
        return out
    }
}
