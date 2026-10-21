package com.iris.localai.data

import android.os.StatFs
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Downloads a whole MNN model repository (e.g. taobao-mnn/Qwen3-1.7B-MNN) from Hugging Face or its
 * mirror into `models/<repo name>/`. Files land in `<repo name>.part/` (each one resumable) and the
 * folder is renamed only after every file has its exact size, so a half download is never listed.
 */
class MnnHub(private val repo: ModelRepository) {

    data class State(
        val repoId: String = "",
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val file: String = "",
        val active: Boolean = false,
        val error: String? = null,
    ) {
        val progress: Float get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Serializable
    private data class TreeEntry(val type: String = "", val path: String = "", val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun download(input: String, source: DownloadSource): LocalModel = withContext(Dispatchers.IO) {
        val repoId = MnnModels.parseRepoId(input) ?: throw IOException("仓库名格式应为 作者/名称，例如 taobao-mnn/Qwen3-1.7B-MNN")
        val name = ModelFiles.sanitizeFileName(repoId.substringAfter('/'))
        val modelsDir = repo.modelsDir // resolved once: shared Download/IRIDIS/models when available
        val target = File(modelsDir, name)
        if (target.exists() || repo.find(name) != null) throw IOException("已存在同名模型：$name")
        val partName = name + ModelRepository.PART_SUFFIX
        if (!repo.claimPart(partName)) throw IOException("$repoId 正在下载中")
        _state.value = State(repoId = repoId, active = true, file = "读取文件列表…")
        try {
            // Mirror first when chosen; fall back to the other host (mirrors sometimes lag on new repos).
            val bases = listOf(source.baseUrl) + DownloadSource.entries.map { it.baseUrl }.filter { it != source.baseUrl }
            var listing: Pair<String, List<TreeEntry>>? = null
            var lastErr: Exception? = null
            for (b in bases) {
                try { listing = b to listFiles(b, repoId); break } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastErr = e
                }
            }
            val (base, all) = listing ?: throw IOException("无法获取 $repoId 的文件列表：${lastErr?.message ?: "网络错误"}")
            // 1.12.1: a LiteRT-LM repo (litert-community/…) — download its one generic .litertlm file.
            pickLiteRtFile(all.filter { it.type == "file" }.map { it.path to it.size })?.let { (path, size) ->
                repo.releasePart(partName)
                return@withContext downloadSingle(base, repoId, path, size)
            }
            val files = all.filter { it.type == "file" && MnnModels.shouldCopy(it.path) }
            if (!MnnModels.isModelFileSet(files.filter { '/' !in it.path }.map { it.path }.toSet())) {
                throw IOException("$repoId 不是 MNN 大模型仓库（缺少 config.json / llm.mnn）。请选 taobao-mnn 下以 -MNN 结尾的仓库")
            }
            val total = files.sumOf { it.size }
            val part = File(modelsDir, partName).apply { mkdirs() }
            val have = files.sumOf { f -> File(part, f.path).takeIf { it.exists() }?.length()?.coerceAtMost(f.size) ?: 0L }
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < total - have + 256L * 1024 * 1024) {
                throw IOException("存储空间不足：需要 ${Format.bytes(total - have)}，可用 ${Format.bytes(free)}")
            }
            var done = have
            _state.value = _state.value.copy(totalBytes = total, doneBytes = done)
            for (f in files) {
                coroutineContext.ensureActive()
                val out = File(part, f.path).apply { parentFile?.mkdirs() }
                if (out.exists() && out.length() == f.size) continue
                val before = done - (HttpDownload.partFile(out).takeIf { it.exists() }?.length() ?: 0L)
                _state.value = _state.value.copy(file = f.path)
                val url = "$base/$repoId/resolve/main/${encodePath(f.path)}"
                fetch(url, out, f.size) { got -> done = before + got; _state.value = _state.value.copy(doneBytes = done) }
                done = before + f.size
            }
            if (!part.renameTo(target)) throw IOException("无法写入模型目录")
            _state.value = State()
        } catch (t: Throwable) {
            _state.value = State(repoId = repoId, error = if (t is kotlinx.coroutines.CancellationException) null else (t.message ?: "下载失败"))
            throw t
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        LocalModel(target.name, target.absolutePath, MnnModels.folderSize(target), ModelFormat.MNN)
    }

    /** One .litertlm file into models/ (resumable through its .part), listed only when complete. */
    private suspend fun downloadSingle(base: String, repoId: String, path: String, size: Long): LocalModel {
        val fileName = ModelFiles.sanitizeFileName(path.substringAfterLast('/'))
        val modelsDir = repo.modelsDir
        val target = File(modelsDir, fileName)
        if (target.exists() || repo.find(fileName) != null) throw IOException("已存在同名模型：$fileName")
        val partName = fileName + ModelRepository.PART_SUFFIX
        if (!repo.claimPart(partName)) throw IOException("$repoId 正在下载中")
        try {
            val have = HttpDownload.partFile(target).takeIf { it.exists() }?.length() ?: 0L
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < size - have + 256L * 1024 * 1024) throw IOException("存储空间不足：需要 ${Format.bytes(size - have)}，可用 ${Format.bytes(free)}")
            _state.value = _state.value.copy(totalBytes = size, doneBytes = have, file = fileName)
            // fetchOnce writes target.part and renames it when complete, so a half file is never listed.
            fetch("$base/$repoId/resolve/main/${encodePath(path)}", target, size) { got -> _state.value = _state.value.copy(doneBytes = got) }
            _state.value = State()
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        return LocalModel(target.name, target.absolutePath, target.length(), ModelFormat.LITERT)
    }

    private fun listFiles(base: String, repoId: String): List<TreeEntry> {
        val conn = HttpDownload.open("$base/api/models/$repoId/tree/main?recursive=true", 0)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == 401) throw IOException("找不到仓库 $repoId")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString<List<TreeEntry>>(body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun fetch(url: String, target: File, expected: Long, onBytes: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                HttpDownload.fetchOnce(url, target, expected, onBytes)
                return
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                delay(1500L * attempt)
            }
        }
    }

    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** Runs in the app scope so the download survives leaving the models screen. */
    fun start(scope: kotlinx.coroutines.CoroutineScope, input: String, source: DownloadSource, onDone: suspend (LocalModel) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try { onDone(download(input, source)) } catch (_: Throwable) { /* reported through state */ }
        }
    }

    fun cancel() { job?.cancel() }

    fun clearError() { if (!_state.value.active) _state.value = State() }

    companion object {
        /**
         * ETC fork 2.3.0: expanded catalog — 16 entries verified live on hf-mirror.com 2026-10-21.
         * Small models first so 32-bit / low-RAM phones always find something that runs.
         * LiteRT first (GPU). GGUF = llama.cpp backend; MNN = MNN backend.
         */
        val RECOMMENDED = listOf(
            "litert-community/gemma-4-E2B-it-litert-lm" to "LiteRT · 约 2.4 GB · 推荐先试",
            "litert-community/gemma-4-E4B-it-litert-lm" to "LiteRT · 约 3.4 GB · 更聪明",
            "taobao-mnn/Qwen3-0.6B-MNN" to "MNN · 约 0.6 GB · 最轻量，32 位也流畅",
            "Qwen/Qwen2.5-0.5B-Instruct-GGUF" to "GGUF · 约 0.5 GB · 32 位极速",
            "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF" to "GGUF · 约 1.1 GB · 推理强，32 位首选",
            "Qwen/Qwen2.5-1.5B-Instruct-GGUF" to "GGUF · 约 1 GB · 32 位首选",
            "taobao-mnn/Qwen3-1.7B-MNN" to "MNN · 约 1.3 GB",
            "bartowski/Llama-3.2-1B-Instruct-GGUF" to "GGUF · 约 1 GB",
            "Qwen/Qwen2.5-3B-Instruct-GGUF" to "GGUF · 约 2 GB · 均衡之选",
            "bartowski/Llama-3.2-3B-Instruct-GGUF" to "GGUF · 约 2 GB",
            "taobao-mnn/Qwen3-4B-MNN" to "MNN · 约 2.7 GB",
            "taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN" to "MNN · 约 2.7 GB · 推理强",
            "bartowski/gemma-2-2b-it-GGUF" to "GGUF · 约 1.6 GB · 谷歌 Gemma",
            "bartowski/Phi-3-mini-4k-instruct-GGUF" to "GGUF · 约 2.2 GB · 微软 Phi-3",
            "Qwen/Qwen2.5-7B-Instruct-GGUF" to "GGUF · 约 4.5 GB · 需大内存",
            "TheBloke/Mistral-7B-Instruct-v0.2-GGUF" to "GGUF · 约 4.4 GB · Mistral 7B",
            "taobao-mnn/Qwen3-14B-MNN" to "MNN · 约 10 GB · 最强，需大内存",
            "taobao-mnn/Qwen3.5-9B-MNN" to "MNN · 约 6.8 GB · 需大内存",
        )

        /**
         * The generic .litertlm of a LiteRT-LM repo: not -web/-gpu/.task, not a chip-specific build
         * (`_qualcomm_…`, `_Google_Tensor_…`); null when the repo has none. Pure.
         */
        fun pickLiteRtFile(files: List<Pair<String, Long>>): Pair<String, Long>? {
            val lm = files.filter { it.first.endsWith(".litertlm", ignoreCase = true) && '/' !in it.first }
            if (lm.isEmpty()) return null
            val generic = lm.filter { (p, _) -> val stem = p.removeSuffix(".litertlm"); !stem.contains('_') && !Regex("(?i)-(web|gpu)$").containsMatchIn(stem) }
            return (generic.ifEmpty { lm }).minByOrNull { it.first.length }
        }

        fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }
}
