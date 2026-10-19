package com.iris.localai.agent

import java.io.File

/** 1.10.0: where the agent workspace lives. */
enum class WorkspaceLocation(val label: String) {
    /** Public `Download/IRIDIS` (2.1.0; `Download/IRISLocalAI` before) — visible in the phone's file manager (default). */
    DOWNLOADS("下载/IRIDIS"),
    /** App-private `filesDir/workspace` (the 1.9.0 location). */
    PRIVATE("应用私有目录");

    companion object {
        fun parse(s: String?): WorkspaceLocation = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: DOWNLOADS
    }
}

/** The workspace really in use: [location] can differ from [requested] when it had to fall back. */
data class WorkspaceChoice(
    val requested: WorkspaceLocation,
    val location: WorkspaceLocation,
    val root: File,
    /** Why the requested location could not be used (Chinese, shown as a notice); null = no fallback. */
    val fallbackReason: String? = null,
)

/**
 * Pure path resolution for the workspace root (unit-tested). The app passes
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`, `Environment.getExternalStorageState()`
 * and `filesDir/workspace`; tests pass temp folders.
 */
object WorkspaceResolver {
    const val FOLDER = "IRIDIS"
    /** 1.10.0 – 2.0.0 folder name (package com.iris.localai); moved once by [LegacyWorkspace]. */
    const val LEGACY_FOLDER = "IRISLocalAI"
    const val MOUNTED = "mounted" // Environment.MEDIA_MOUNTED
    /** SAF document id of `Download/IRIDIS` on the primary volume. */
    const val TREE_DOC_ID = "primary:Download/$FOLDER"
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun downloadsRoot(downloadsDir: File): File = File(downloadsDir, FOLDER)

    fun resolve(
        requested: WorkspaceLocation,
        downloadsDir: File?,
        storageState: String?,
        privateRoot: File,
        usable: (File) -> Boolean = ::probe,
    ): WorkspaceChoice {
        if (requested == WorkspaceLocation.PRIVATE) return WorkspaceChoice(requested, WorkspaceLocation.PRIVATE, privateRoot)
        val reason = when {
            storageState != MOUNTED -> "外部存储不可用（$storageState）"
            downloadsDir == null -> "找不到系统「下载」文件夹"
            !usable(downloadsRoot(downloadsDir)) -> "无法在「下载」里创建 $FOLDER 文件夹"
            else -> null
        }
        return if (reason == null) WorkspaceChoice(requested, WorkspaceLocation.DOWNLOADS, downloadsRoot(downloadsDir!!))
        else WorkspaceChoice(requested, WorkspaceLocation.PRIVATE, privateRoot, "$reason，智能体工作区暂时改用应用私有目录")
    }

    /** The folder exists (created if needed) and a file can be written and removed in it. */
    fun probe(dir: File): Boolean = runCatching {
        dir.mkdirs()
        if (!dir.isDirectory) return false
        val f = File(dir, ".iris-probe")
        f.writeText("ok")
        val ok = f.readText() == "ok"
        f.delete()
        ok
    }.getOrDefault(false)

    /** Whether a SAF tree grant is exactly `Download/IRIDIS` (any other folder is refused: the sandbox root must not change). */
    fun isWorkspaceTree(treeDocumentId: String?): Boolean =
        treeDocumentId != null && treeDocumentId.trimEnd('/').equals(TREE_DOC_ID, ignoreCase = false)

    /** Path shown to the user: `/storage/emulated/0/Download/IRIDIS`. */
    fun display(choice: WorkspaceChoice): String = choice.root.path
}

/**
 * 2.1.0: one-time move of `Download/IRISLocalAI` (1.10.0 – 2.0.0) to `Download/IRIDIS`. With scoped
 * storage the new package (com.iridis) only sees files the old package created when it holds
 * 全部文件访问; otherwise the old folder looks empty and the user is told to move it by hand.
 * Pure decision logic, unit-tested; the app does the file work with [WorkspaceMigration].
 */
object LegacyWorkspace {
    enum class Action {
        /** No old folder (or the workspace is not in Download): nothing to do. */
        NONE,
        /** Old folder has readable files: copy them over, delete the originals after a verified copy. */
        MIGRATE,
        /** Old folder is really empty (all-files access says so): just remove it. */
        DELETE_OLD,
        /** Old folder exists but its files can't be read (or IRIDIS already has files): show [HINT]. */
        HINT,
    }

    const val HINT = "旧的「下载/${WorkspaceResolver.LEGACY_FOLDER}」文件夹还在，但新版无法直接读取。请用文件管理器把里面的文件移到「下载/${WorkspaceResolver.FOLDER}」。"

    fun legacyRoot(downloadsDir: File): File = File(downloadsDir, WorkspaceResolver.LEGACY_FOLDER)

    /**
     * @param inDownloads the workspace in use is Download/IRIDIS
     * @param oldExists Download/IRISLocalAI exists
     * @param oldVisibleFiles files this app can actually see (and read) in the old folder
     * @param allFilesAccess 全部文件访问 granted (then an empty listing really means empty)
     * @param newHasFiles Download/IRIDIS already holds user files (never merged automatically)
     */
    fun decide(inDownloads: Boolean, oldExists: Boolean, oldVisibleFiles: Int, allFilesAccess: Boolean, newHasFiles: Boolean): Action = when {
        !inDownloads || !oldExists -> Action.NONE
        newHasFiles -> Action.HINT
        oldVisibleFiles > 0 -> Action.MIGRATE
        allFilesAccess -> Action.DELETE_OLD
        else -> Action.HINT
    }

    /** Hint after a [Action.MIGRATE] run: null when everything moved. */
    fun afterMigrate(r: MigrationResult, oldStillHasFiles: Boolean): String? = if (r.ok && !oldStillHasFiles) null else HINT
}

data class MigrationResult(val copied: Int, val skipped: Int, val failed: Int, val renamed: Int) {
    val ok: Boolean get() = failed == 0
}

/**
 * Copies every file of the old private workspace into the new root. An original is deleted only
 * after its copy is verified byte for byte; anything that fails stays where it was (and the
 * migration is tried again next start). A different file already at the target keeps its name and
 * the old one is copied next to it as `name（私有目录）.ext`. Pure JVM, unit-tested.
 */
object WorkspaceMigration {
    const val SUFFIX = "（私有目录）"

    fun migrate(from: File, to: File): MigrationResult {
        if (!from.isDirectory) return MigrationResult(0, 0, 0, 0)
        val src = from.canonicalFile
        val dst = to.canonicalFile
        if (src == dst || dst.path.startsWith(src.path + File.separator)) return MigrationResult(0, 0, 0, 0)
        var copied = 0; var skipped = 0; var failed = 0; var renamed = 0
        src.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.toList().forEach { f ->
            val rel = f.canonicalFile.path.removePrefix(src.path + File.separator)
            if (rel == f.canonicalFile.path) { skipped++; return@forEach } // symlinked out of the old workspace: leave it
            var target = File(dst, rel)
            try {
                if (target.isFile && sameContent(f, target)) {
                    f.delete(); skipped++; return@forEach
                }
                if (target.exists()) { target = alternative(target); renamed++ }
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = false)
                target.setLastModified(f.lastModified())
                if (sameContent(f, target)) { f.delete(); copied++ } else { target.delete(); failed++ }
            } catch (_: Throwable) {
                failed++
            }
        }
        // Empty folders left behind (only when everything moved).
        if (failed == 0) src.walkBottomUp().filter { it.isDirectory && it != src }.forEach { if (it.list().isNullOrEmpty()) it.delete() }
        return MigrationResult(copied, skipped, failed, renamed)
    }

    private fun alternative(target: File): File {
        val name = target.nameWithoutExtension
        val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(target.parentFile, name + SUFFIX + (if (i > 1) i.toString() else "") + ext)
            if (!c.exists()) return c
            i++
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val p = x.read(); val q = y.read()
                    if (p != q) return false
                    if (p < 0) return true
                }
            }
        }
    }
}

/**
 * Read-only view of files in the workspace folder that the app's own File access cannot see
 * (created by another app, or by an earlier install), through the user's one-time SAF grant of
 * `Download/IRIDIS`. Paths are workspace-relative and already checked by the sandbox.
 */
interface ExternalFiles {
    data class Entry(val name: String, val isDir: Boolean, val size: Long)
    fun list(relDir: String): List<Entry>?
    fun read(relPath: String, maxBytes: Long): ByteArray?
    /** 1.10.6: overwrite (or create) a file through the grant; false = not possible. */
    fun write(relPath: String, bytes: ByteArray): Boolean = false
    /** 1.10.6: delete a file through the grant; false = not possible. */
    fun delete(relPath: String): Boolean = false
}
