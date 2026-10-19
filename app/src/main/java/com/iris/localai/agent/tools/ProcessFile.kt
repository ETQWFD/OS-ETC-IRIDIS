package com.iris.localai.agent

import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject

/**
 * 1.12.0 `process_file`: translate / summarize / convert a file far larger than the context window.
 *
 * An on-device model has 4k–8k tokens of context, so "translate this 600 KB file" used to read the
 * first part only and translate that (even the truncation note). Here the app — not the model —
 * walks the file: it cuts it into parts that fit, sends each part with the task in a fresh prompt
 * (no chat history, so every part gets the whole window), and appends each result to an output
 * file. The agent only sees a short report. 停止 stops between parts; `start` resumes.
 */
object ProcessFileTool : SuspendTool {
    override val group = ToolGroup.FILES
    override val name = "process_file"
    override val label = "分段处理文件"
    override val description = "长文件分段翻译、总结或改写，结果写入新文件（可撤销）"
    override val promptLine = "process_file(path, task, out?, start?: part): translate/summarize/convert a long file part by part into out"
    override val risk = ToolRisk.WRITE

    /** Reads at most this much (larger files: ask the user to split them). */
    const val MAX_BYTES = 8_000_000L

    // 1.13.0: file I/O (up to 8 MB read, snapshot, appends) off the main thread.
    override suspend fun runSuspend(args: JsonObject, env: ToolEnv, progress: (String) -> Unit): ToolResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runIo(args, env, progress) }

    private suspend fun runIo(args: JsonObject, env: ToolEnv, progress: (String) -> Unit): ToolResult {
        val path = Args.str(args, "path", "file", "file_path") ?: throw IllegalArgumentException("缺少参数 path")
        val task = Args.str(args, "task", "instruction", "prompt", "what")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺少参数 task")
        val llm = env.llm ?: return ToolResult(false, "Error: no model available for process_file", "不可用")
        val src = env.sandbox.resolve(path)
        val text = when {
            src.isFile && src.canRead() -> {
                if (src.length() > MAX_BYTES) return ToolResult(false, "Error: file too large (${src.length()} bytes)", "文件过大")
                src.readText(Charsets.UTF_8)
            }
            else -> env.external?.read(env.sandbox.relative(src), MAX_BYTES)?.toString(Charsets.UTF_8) ?: return notFound(path, env)
        }
        if (text.take(4096).any { it == '\u0000' }) return ToolResult(false, "Error: binary file", "二进制文件")
        val outFile = env.sandbox.resolve(Args.str(args, "out", "output", "dest", "to") ?: defaultOut(env.sandbox.relative(src), task))
        if (outFile == src) return ToolResult(false, "Error: out must differ from path", "输出不能覆盖原文件")
        val parts = split(text, env.chunkTokens)
        val start = (Args.int(args, "start", "from", "part") ?: 1).coerceIn(1, parts.size.coerceAtLeast(1))
        val rel = env.sandbox.relative(outFile)
        env.undo.snapshot(env.sandbox, outFile, if (outFile.isFile) "覆盖" else "新建", env.clock())
        outFile.parentFile?.mkdirs()
        if (start == 1) outFile.writeText("", Charsets.UTF_8)
        val system = systemFor(task)
        var done = start - 1
        var failed = 0
        for (i in (start - 1) until parts.size) {
            currentCoroutineContext().ensureActive()
            progress("第 ${i + 1}/${parts.size} 段")
            val out = runCatching { llm.complete(system, "Part ${i + 1} of ${parts.size}:\n\n" + parts[i]) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let(::clean)
            if (out.isNullOrBlank()) failed++
            outFile.appendText((out ?: "[part ${i + 1} failed]") + "\n\n", Charsets.UTF_8)
            done = i + 1
        }
        val preview = outFile.readText(Charsets.UTF_8).take(300).trim()
        val report = "OK: processed $done/${parts.size} parts of ${env.sandbox.relative(src)} into $rel (${outFile.length()} bytes)" +
            (if (failed > 0) ", $failed parts failed" else "") +
            ". Tell the user the result is in $rel; do not repeat it. Start:\n$preview"
        return ToolResult(failed < parts.size, report, "$done 段 → $rel" + if (failed > 0) " · $failed 段失败" else "")
    }

    /** ATRI.txt + "translate to English" → ATRI.out.txt. Pure. */
    fun defaultOut(rel: String, task: String): String {
        val dot = rel.lastIndexOf('.').takeIf { it > rel.lastIndexOf('/') } ?: rel.length
        val tag = if (Regex("(?i)translat|翻译|译").containsMatchIn(task)) "translated" else "out"
        return rel.substring(0, dot) + ".$tag" + rel.substring(dot).ifEmpty { ".txt" }
    }

    fun systemFor(task: String): String =
        "You process one part of a long file for the user. Task: $task\n" +
            "Output ONLY the result for this part: no greeting, no notes, no \"Part N\" header, no summary of what you did. " +
            "Keep line breaks and names. Do not stop early."

    /** Removes a reasoning block or a code fence the model wrapped the result in. Pure. */
    fun clean(s: String): String {
        var t = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        Regex("(?s)^```[a-zA-Z]*\\n(.*)\\n```$").find(t)?.let { t = it.groupValues[1] }
        return t.trim()
    }

    /**
     * Cuts [text] into parts of about [tokens] estimated tokens, at line breaks (a very long line is
     * cut at a sentence end or, failing that, hard). Pure.
     */
    fun split(text: String, tokens: Int): List<String> {
        val max = tokens.coerceAtLeast(100)
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var used = 0
        fun flush() { if (cur.isNotBlank()) parts += cur.toString().trimEnd(); cur.setLength(0); used = 0 }
        for (raw in text.split("\n")) {
            var line = raw
            while (TokenEstimator.estimate(line) > max) {
                flush()
                var cut = cutPoint(line, max)
                if (cut <= 0) cut = line.length
                parts += line.substring(0, cut)
                line = line.substring(cut)
            }
            val t = TokenEstimator.estimate(line) + 1
            if (used + t > max && cur.isNotEmpty()) flush()
            cur.append(line).append('\n'); used += t
        }
        flush()
        return parts
    }

    private fun cutPoint(line: String, max: Int): Int {
        var lo = 1; var hi = line.length
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (TokenEstimator.estimate(line.substring(0, mid)) <= max) lo = mid else hi = mid - 1 }
        val window = line.substring(0, lo)
        val end = window.indexOfLast { it in "。！？.!?；;" }
        return if (end > lo / 2) end + 1 else lo
    }
}
