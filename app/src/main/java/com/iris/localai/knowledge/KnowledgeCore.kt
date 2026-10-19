package com.iris.localai.knowledge

import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * 2.1.0 知识库: the pure part (JVM-tested). Files the user drops into the workspace's
 * `knowledge/` folder are cut into ~500-character chunks and ranked with BM25 over a tokenizer
 * that splits Latin text into words and CJK text into overlapping bigrams. No embeddings.
 */
object KbTokenizer {
    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /** "IRIDIS 本地知识库v2" → [iridis, 本地, 地知, 知识, 识库, v2]. A lone CJK character stays a unigram. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        val run = StringBuilder()
        fun flushWord() { if (word.isNotEmpty()) { out += word.toString(); word.setLength(0) } }
        fun flushRun() {
            if (run.length == 1) out += run.toString()
            else for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
            run.setLength(0)
        }
        for (ch in text) {
            when {
                isCjk(ch) -> { flushWord(); run.append(ch) }
                ch.isLetterOrDigit() -> { flushRun(); word.append(ch.lowercaseChar()) }
                else -> { flushWord(); flushRun() }
            }
        }
        flushWord(); flushRun()
        return out
    }
}

object KbChunker {
    const val SIZE = 500
    const val OVERLAP = 80

    /**
     * Cuts [text] into pieces of at most [size] characters, preferring a paragraph, then a sentence
     * end, then a space; consecutive pieces share about [overlap] characters. Pure.
     */
    fun chunk(text: String, size: Int = SIZE, overlap: Int = OVERLAP): List<String> {
        val t = text.replace("\r\n", "\n").replace(Regex("[ \\t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= size) return listOf(t)
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = (start + size).coerceAtMost(t.length)
            if (end < t.length) {
                val window = t.substring(start, end)
                val min = size / 2
                val cut = listOf(window.lastIndexOf("\n\n"), window.indexOfLast { it in "。！？.!?；;\n" }, window.lastIndexOf(' '))
                    .firstOrNull { it >= min }
                if (cut != null) end = start + cut + 1
            }
            out += t.substring(start, end).trim()
            if (end >= t.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out.filter { it.isNotEmpty() }
    }
}

@Serializable
data class KbChunk(val file: String, val text: String)

/** One indexed file: its path in knowledge/, and size + mtime to notice changes. */
@Serializable
data class KbFileEntry(val path: String, val size: Long, val mtime: Long, val chunks: List<String> = emptyList())

@Serializable
data class KbIndexData(val version: Int = 1, val files: List<KbFileEntry> = emptyList())

/** A file as seen in the folder (mtime 0 when unknown, e.g. through the SAF grant). */
data class KbSourceFile(val path: String, val size: Long, val mtime: Long)

data class KbHit(val file: String, val text: String, val score: Double)

object KbChanges {
    data class Plan(val keep: List<KbFileEntry>, val reindex: List<KbSourceFile>, val removed: List<String>) {
        val changed: Boolean get() = reindex.isNotEmpty() || removed.isNotEmpty()
    }

    /** Which files must be (re)read: new, or size / mtime differ. Pure. */
    fun plan(old: List<KbFileEntry>, now: List<KbSourceFile>, force: Boolean = false): Plan {
        val byPath = old.associateBy { it.path }
        val keep = ArrayList<KbFileEntry>()
        val re = ArrayList<KbSourceFile>()
        for (f in now) {
            val o = byPath[f.path]
            if (!force && o != null && o.size == f.size && o.mtime == f.mtime) keep += o else re += f
        }
        val present = now.map { it.path }.toSet()
        return Plan(keep, re, old.map { it.path }.filter { it !in present })
    }
}

/** BM25 over the chunks of an index. Build once per index version; [search] is pure. */
class KbRanker(chunks: List<KbChunk>, private val k1: Double = 1.2, private val b: Double = 0.75) {
    private val docs = chunks
    private val tf: List<Map<String, Int>> = chunks.map { c -> KbTokenizer.tokens(c.file.substringAfterLast('/') + " " + c.text).groupingBy { it }.eachCount() }
    private val len = tf.map { m -> m.values.sum() }
    private val avg = if (len.isEmpty()) 1.0 else len.average().coerceAtLeast(1.0)
    private val df: Map<String, Int> = HashMap<String, Int>().also { d -> tf.forEach { m -> m.keys.forEach { d[it] = (d[it] ?: 0) + 1 } } }

    val size: Int get() = docs.size

    fun search(query: String, limit: Int): List<KbHit> {
        val q = KbTokenizer.tokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val scored = ArrayList<KbHit>()
        for (i in docs.indices) {
            var s = 0.0
            val m = tf[i]
            for (t in q) {
                val f = m[t] ?: continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len[i] / avg))
            }
            if (s > 0) scored += KbHit(docs[i].file, docs[i].text, s)
        }
        // One file may fill the list only when nothing else matches.
        val sorted = scored.sortedByDescending { it.score }
        val out = ArrayList<KbHit>()
        val perFile = HashMap<String, Int>()
        for (h in sorted) {
            if ((perFile[h.file] ?: 0) >= 2) continue
            out += h; perFile[h.file] = (perFile[h.file] ?: 0) + 1
            if (out.size >= limit) return out
        }
        for (h in sorted) { if (out.size >= limit) break; if (h !in out) out += h }
        return out
    }
}

/** What the model reads. Pure. */
object KbPrompt {
    const val INJECT_SNIPPETS = 3
    const val INJECT_CHARS = 1200
    const val TOOL_SNIPPETS = 4

    /** Block added to the user's turn in 对话 mode: at most [maxChars] characters of snippet text. */
    fun inject(hits: List<KbHit>, maxChars: Int = INJECT_CHARS): String {
        if (hits.isEmpty()) return ""
        val per = maxChars / hits.size
        return buildString {
            append("<knowledge>\n参考资料（来自用户的知识库，相关时引用并注明文件名，无关就忽略）：\n")
            hits.forEachIndexed { i, h -> append("[${i + 1}] ").append(h.file).append(": ").append(clip(h.text, per)).append('\n') }
            append("</knowledge>")
        }
    }

    fun toolOutput(query: String, hits: List<KbHit>, perChars: Int = 400): String =
        if (hits.isEmpty()) "No knowledge snippets match \"$query\"."
        else hits.mapIndexed { i, h -> "${i + 1}. [${h.file}] ${clip(h.text, perChars)}" }.joinToString("\n")

    /** Label under a reply that was given knowledge snippets. */
    fun chip(n: Int): String = "引用 $n 段知识库"

    fun clip(s: String, n: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= n) t else t.substring(0, (n - 1).coerceAtLeast(1)) + "…"
    }
}

/** Which files go into the index, and how their bytes become text. Pure. */
object KbFormats {
    val TEXT = setOf("txt", "md", "markdown", "csv", "json", "log", "srt")
    val HTML = setOf("html", "htm")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun supported(name: String) = !name.startsWith(".") && ext(name).let { it in TEXT || it in HTML }

    fun text(name: String, bytes: ByteArray): String? {
        if (bytes.take(4096).any { it == 0.toByte() }) return null
        val s = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (s.isNotEmpty() && s.count { it == '\uFFFD' } > s.length / 20) return null
        return if (ext(name) in HTML) com.iris.localai.agent.HtmlText.toText(s) else s
    }
}

/** 2.1.0 引用知识库 in 对话 mode. */
object KbChat {
    /** Snippets for [question], or none when the folder is empty or nothing matches well enough. */
    fun hits(kb: KnowledgeBase, question: String): List<KbHit> = runCatching {
        if (!kb.hasFiles()) return emptyList()
        kb.search(question, KbPrompt.INJECT_SNIPPETS).filter { it.score >= MIN_SCORE }
    }.getOrDefault(emptyList())

    /** Below this BM25 score a snippet shares only a common word or two with the question. */
    const val MIN_SCORE = 1.0

    /** Puts [block] in front of the message [id]'s text (prompt only; the stored message is untouched). Pure. */
    fun attach(msgs: List<com.iris.localai.util.CtxMessage>, id: String, block: String): List<com.iris.localai.util.CtxMessage> =
        msgs.map { if (it.id == id) com.iris.localai.util.CtxMessage(it.id, it.role, block + "\n\n" + it.content) else it }
}
