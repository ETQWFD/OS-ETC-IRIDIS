package com.iris.localai.agent

/**
 * 1.9.0: search/replace patch for edit_file. Tries, in order: the exact text; the text with
 * line endings normalised; a line-by-line match ignoring leading/trailing whitespace (small
 * models often get indentation wrong). The match must be unique unless [all] is set.
 * Pure, unit-tested.
 */
object EditPatch {
    sealed interface Result {
        data class Ok(val text: String, val replacements: Int, val fuzzy: Boolean) : Result
        data class Err(val message: String) : Result
    }

    fun apply(original: String, old: String, new: String, all: Boolean = false): Result {
        if (old.isEmpty()) return Result.Err("old 不能为空；要新建或整体覆盖文件请用 write_file")
        if (old == new) return Result.Err("old 与 new 相同，没有要修改的内容")

        exact(original, old, new, all)?.let { return it }

        // CRLF file, LF patch (or the other way round).
        val crlf = original.contains("\r\n")
        if (crlf || old.contains("\r\n")) {
            val o = original.replace("\r\n", "\n")
            val r = exact(o, old.replace("\r\n", "\n"), new.replace("\r\n", "\n"), all)
            if (r is Result.Ok) return r.copy(text = if (crlf) r.text.replace("\n", "\r\n") else r.text)
            if (r is Result.Err) return r
        }

        return trimmedLines(original, old, new, all)
            ?: Result.Err("在文件中找不到 old 指定的文本。请先用 read_file 读取文件，再原样复制要替换的部分")
    }

    private fun exact(text: String, old: String, new: String, all: Boolean): Result? {
        val count = occurrences(text, old)
        if (count == 0) return null
        if (count > 1 && !all) return Result.Err("old 在文件中出现了 $count 次；请多带几行上下文使其唯一，或设置 \"all\": true 全部替换")
        val out = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        return Result.Ok(out, if (all) count else 1, fuzzy = false)
    }

    fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
        return n
    }

    /** Matches whole lines with whitespace trimmed; replaced lines keep the first line's indent. */
    private fun trimmedLines(original: String, old: String, new: String, all: Boolean): Result? {
        val nl = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.split(nl)
        val want = old.replace("\r\n", "\n").trim('\n').split("\n").map { it.trim() }
        if (want.all { it.isEmpty() }) return null
        val hits = ArrayList<Int>()
        var i = 0
        while (i + want.size <= lines.size) {
            if ((want.indices).all { k -> lines[i + k].trim() == want[k] }) { hits.add(i); i += want.size } else i++
        }
        if (hits.isEmpty()) return null
        if (hits.size > 1 && !all) return Result.Err("old 在文件中出现了 ${hits.size} 次（忽略缩进后）；请多带几行上下文使其唯一，或设置 \"all\": true")
        val out = ArrayList<String>(lines.size)
        var j = 0
        val newLines = if (new.isEmpty()) emptyList() else new.replace("\r\n", "\n").split("\n")
        // Re-indent: the block keeps its own relative indentation under the matched line's indent.
        val minNew = newLines.filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        for (h in hits) {
            while (j < h) out.add(lines[j++])
            val indent = lines[h].takeWhile { it == ' ' || it == '\t' }
            newLines.forEach { l -> out.add(if (l.isBlank()) "" else indent + l.substring(minNew)) }
            j = h + want.size
        }
        while (j < lines.size) out.add(lines[j++])
        return Result.Ok(out.joinToString(nl), hits.size, fuzzy = true)
    }
}
