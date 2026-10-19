package com.iris.localai.knowledge

import com.iris.localai.agent.ExternalFiles
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 2.1.0 知识库: `knowledge/` in the agent workspace (Download/IRIDIS/knowledge). The index is a
 * JSON file in app storage ([indexFile]); it is brought up to date on demand (before a search, or
 * from 重建索引) by comparing each file's size and mtime. Files only visible through the SAF grant
 * ([external]) are read that way (no mtime: changes are noticed by size, or by 重建索引).
 */
class KnowledgeBase(
    private val root: () -> File,
    private val indexFile: File,
    private val external: () -> ExternalFiles? = { null },
) {
    data class Status(val files: Int, val chunks: Int, val unsupported: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()
    private var data: KbIndexData? = null
    private var ranker: KbRanker? = null
    private var lastScan = 0L

    fun dir(): File = File(root(), FOLDER)

    private fun load(): KbIndexData = data ?: runCatching {
        json.decodeFromString(KbIndexData.serializer(), indexFile.readText())
    }.getOrNull().let { it ?: KbIndexData() }.also { data = it }

    /** Files in the folder (direct File access, plus the SAF view for files File cannot see). */
    fun scan(): Pair<List<KbSourceFile>, Int> {
        val out = LinkedHashMap<String, KbSourceFile>()
        var unsupported = 0
        val d = dir()
        d.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(d).path.replace(File.separatorChar, '/')
            if (KbFormats.supported(f.name) && f.length() <= MAX_BYTES) out[rel] = KbSourceFile(rel, f.length(), f.lastModified())
            else if (!f.name.startsWith(".")) unsupported++
        }
        external()?.let { ext ->
            fun walk(rel: String, depth: Int) {
                val entries = ext.list(if (rel.isEmpty()) FOLDER else "$FOLDER/$rel") ?: return
                for (e in entries) {
                    val p = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                    if (e.isDir) { if (depth < 4) walk(p, depth + 1) }
                    else if (p !in out) {
                        if (KbFormats.supported(e.name) && e.size <= MAX_BYTES) out[p] = KbSourceFile(p, e.size, 0L)
                        else if (!e.name.startsWith(".")) unsupported++
                    }
                }
            }
            walk("", 0)
        }
        return out.values.sortedBy { it.path.lowercase() } to unsupported
    }

    private fun read(rel: String): ByteArray? {
        val f = File(dir(), rel)
        if (f.isFile && f.canRead()) return runCatching { f.readBytes() }.getOrNull()
        return external()?.read("$FOLDER/$rel", MAX_BYTES)
    }

    /** Brings the index up to date. [force] = 重建索引 (reads every file again). Returns the status. */
    fun refresh(force: Boolean = false): Status = synchronized(lock) {
        val (files, unsupported) = scan()
        val old = load()
        val plan = KbChanges.plan(old.files, files, force)
        if (plan.changed || force) {
            val fresh = plan.reindex.map { f ->
                val text = read(f.path)?.let { KbFormats.text(f.path, it) }.orEmpty()
                KbFileEntry(f.path, f.size, f.mtime, KbChunker.chunk(text))
            }
            val next = KbIndexData(files = (plan.keep + fresh).sortedBy { it.path.lowercase() })
            data = next
            ranker = null
            runCatching {
                indexFile.parentFile?.mkdirs()
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(json.encodeToString(KbIndexData.serializer(), next))
                if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
            }
        }
        lastScan = System.currentTimeMillis()
        val d = load()
        nonEmpty = d.files.any { it.chunks.isNotEmpty() }
        Status(d.files.size, d.files.sumOf { it.chunks.size }, unsupported)
    }

    /** Whether the folder holds at least one indexable file (lists the folder; no reading). */
    fun hasFiles(): Boolean = runCatching { scan().first.isNotEmpty() }.getOrDefault(false).also { nonEmpty = it }

    @Volatile private var nonEmpty: Boolean? = null

    /**
     * Last known 「has files」 (an empty folder is listed again at most once a minute): decides whether
     * kb_search is listed and 引用知识库 runs, so the prompt does not flip on every turn.
     */
    fun nonEmptyCached(): Boolean {
        val known = nonEmpty
        if (known == true) return true
        // An empty folder is looked at again now and then (files dropped in from outside the app).
        if (known == false && System.currentTimeMillis() - lastEmptyCheck < 60_000L) return false
        lastEmptyCheck = System.currentTimeMillis()
        return hasFiles()
    }
    @Volatile private var lastEmptyCheck = 0L

    /** Top [limit] chunks for [query]; refreshes the index first (at most every few seconds). */
    fun search(query: String, limit: Int): List<KbHit> {
        val r = synchronized(lock) {
            if (System.currentTimeMillis() - lastScan > RESCAN_MS) refresh()
            ranker ?: KbRanker(load().files.flatMap { f -> f.chunks.map { KbChunk(f.path, it) } }).also { ranker = it }
        }
        return r.search(query, limit)
    }

    /** Copies a picked file into knowledge/ (a free name when it exists). Returns its relative path. */
    fun add(name: String, bytes: ByteArray): String? = synchronized(lock) {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().ifEmpty { "file.txt" }
        val d = dir().apply { mkdirs() }
        var target = File(d, clean)
        var i = 2
        while (target.exists()) {
            val base = clean.substringBeforeLast('.', clean); val ext = clean.substringAfterLast('.', "")
            target = File(d, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++
        }
        val ok = runCatching { target.writeBytes(bytes); true }.getOrDefault(false)
        val rel = if (ok) target.name else if (external()?.write("$FOLDER/${target.name}", bytes) == true) target.name else null
        lastScan = 0
        rel
    }

    companion object {
        const val FOLDER = "knowledge"
        const val MAX_BYTES = 4_000_000L
        private const val RESCAN_MS = 5_000L
    }
}
