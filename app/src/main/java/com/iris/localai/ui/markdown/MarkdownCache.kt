package com.iris.localai.ui.markdown

import android.util.LruCache

/**
 * Parse results for finished messages, shared across the whole list. A message scrolled off
 * screen and back (LazyColumn disposes its composition) is not parsed again.
 */
object MarkdownCache {
    private val cache = LruCache<String, List<MdBlock>>(96)

    fun get(text: String): List<MdBlock> {
        cache.get(text)?.let { return it }
        val parsed = MarkdownParser.parse(text)
        cache.put(text, parsed)
        return parsed
    }
}

/**
 * Incremental parser for a message that is still streaming.
 *
 * Text only ever grows at the end, so everything before the last "safe" blank line is parsed
 * once and its block instances are reused; each new token only re-parses the tail. Reusing the
 * same instances lets Compose skip recomposing the blocks that did not change.
 *
 * A blank line is a safe split when no fenced code / $$ math block is open across it and the
 * next line is not indented (an indented line could be a lazy list continuation). Under those
 * conditions parse(a + "\n\n" + b) == parse(a) + parse(b) for [MarkdownParser].
 */
class IncrementalMarkdown {
    private var stableLen = 0
    private var stableSource = ""
    private var stableBlocks: List<MdBlock> = emptyList()

    fun parse(raw: String): List<MdBlock> {
        val text = if (raw.indexOf('\r') >= 0) raw.replace("\r\n", "\n") else raw
        if (stableLen > text.length || !text.startsWith(stableSource)) reset()

        val split = text.lastIndexOf("\n\n")
        if (split > stableLen) {
            val segStart = if (stableLen == 0) 0 else stableLen + 2
            if (segStart <= split) {
                val segment = text.substring(segStart, split)
                val next = text.getOrNull(split + 2)
                // Only advance when the line after the blank is known and not indented.
                if (next != null && next != ' ' && next != '\t' && next != '\n' && balanced(segment)) {
                    stableBlocks = stableBlocks + MarkdownParser.parse(segment)
                    stableLen = split
                    stableSource = text.substring(0, split)
                }
            }
        }
        val tailStart = if (stableLen == 0) 0 else stableLen + 2
        val tail = if (tailStart >= text.length) "" else text.substring(tailStart)
        return if (tail.isBlank()) stableBlocks else stableBlocks + MarkdownParser.parse(tail)
    }

    private fun reset() {
        stableLen = 0
        stableSource = ""
        stableBlocks = emptyList()
    }

    /** True when [segment] closes every code fence and $$ block it opens. Mirrors the parser's rules. */
    private fun balanced(segment: String): Boolean {
        var fence: String? = null
        var math = false
        for (line in segment.split('\n')) {
            val t = line.trim()
            val open = fence
            if (open != null) {
                if (t.startsWith(open.take(3)) && t.all { it == open[0] }) fence = null
                continue
            }
            if (math) {
                if (t == "$$") math = false
                continue
            }
            val m = FENCE.matchEntire(line)
            when {
                m != null -> fence = m.groupValues[1]
                t == "$$" -> math = true
            }
        }
        return fence == null && !math
    }

    private companion object {
        val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    }
}
