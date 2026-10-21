package com.iris.localai.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/**
 * ETC fork (OS-ETC-IRIDIS) 2.3.0: 内置 Termux 中文核心（32 位 / armeabi-v7a）。
 *
 * The original ZoneTermux32 project has gone offline, so instead of asking the user to install
 * an external Termux fork, we ship a Chinese-localized Termux core tarball (official Termux
 * `bootstrap-arm` rebuilt by ETQWFD, with a Chinese motd / PS1 / aliases / docs) as a GitHub
 * Release asset of this repo and install it into filesDir/termux. Commands then run through the
 * same proot machinery as AlpineRootfs — no external Termux APK needed anymore.
 *
 * Path layout: filesDir/termux/rootfs/{bin,etc,lib,…} (unpacked tarball).
 */
object TermuxCore {
    const val VERSION = "1.0.0"
    const val TAG = "v1.0.0"

    /** ETC fork: hosted on our own GitHub Release so the link never dies. */
    val URL: String =
        "https://github.com/ETQWFD/OS-ETC-IRIDIS/releases/download/$TAG/os-etc-termux-core-arm-zh.tar.gz"

    const val SHA256 = "5b085943c6de39772709c786543cbe072e9ea0e7511421ea01f95b9d78ae2d43"
    val SIZE: Long = 28_264_265L

    fun base(ctx: Context) = File(ctx.filesDir, "termux")
    fun rootfs(ctx: Context) = File(base(ctx), "rootfs")
    private fun marker(ctx: Context) = File(base(ctx), "installed-$VERSION")

    fun isInstalled(ctx: Context): Boolean = marker(ctx).isFile && File(rootfs(ctx), "bin/bash").exists()

    fun proot(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    fun loader(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    fun supported(ctx: Context): Boolean = proot(ctx).isFile && loader(ctx).isFile

    sealed interface State {
        data object Idle : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Unpacking : State
        data class Failed(val message: String) : State
        data object Ready : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh(ctx: Context) {
        if (_state.value !is State.Downloading && _state.value != State.Unpacking)
            _state.value = if (isInstalled(ctx)) State.Ready else State.Idle
    }

    /** Downloads, verifies and unpacks the core tarball. Blocking; call off the main thread. */
    fun install(ctx: Context) {
        if (_state.value is State.Downloading || _state.value == State.Unpacking) return
        val dir = base(ctx).apply { mkdirs() }
        val tgz = File(dir, "core.tar.gz")
        try {
            _state.value = State.Downloading(0, SIZE)
            download(tgz)
            val sum = sha256(tgz)
            if (!sum.equals(SHA256, true)) throw SecurityException("校验失败（SHA-256 不一致），已删除下载的文件")
            _state.value = State.Unpacking
            val root = rootfs(ctx)
            val tmp = File(dir, "rootfs.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            ProotTarUnpack.unpackGz(tgz, tmp)
            setupRoot(tmp)
            root.deleteRecursively()
            if (!tmp.renameTo(root)) throw IllegalStateException("无法移动核心目录")
            dir.listFiles()?.filter { it.name.startsWith("installed-") }?.forEach { it.delete() }
            marker(ctx).writeText(SHA256)
            _state.value = State.Ready
        } catch (t: Throwable) {
            android.util.Log.w("IrisTermuxCore", "install failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            tgz.delete()
        }
    }

    fun uninstall(ctx: Context) { base(ctx).deleteRecursively(); _state.value = State.Idle }

    private fun download(to: File) {
        val c = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
        }
        try {
            if (c.responseCode != 200) throw java.io.IOException("下载失败：HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: SIZE
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val b = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(b); if (n < 0) break
                        out.write(b, 0, n); done += n
                        if (done > 256L * 1024 * 1024) throw java.io.IOException("文件过大")
                        _state.value = State.Downloading(done, total)
                    }
                }
            }
        } finally { c.disconnect() }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** HOME + workspace + resolv.conf so apt works and the user lands in /home. */
    private fun setupRoot(root: File) {
        File(root, "home").mkdirs()
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "workspace").mkdirs()
        // bash needs a home; also make sure a login-ish environment picks up our zh profile
        File(root, "home/.profile").apply { if (!exists()) writeText("") }
    }
}

/** TarUnpack with a broader symlink guard (the Termux core has many links into libexec/…). */
object ProotTarUnpack {
    fun unpackGz(tgz: File, into: File) {
        val canon = into.canonicalPath
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            java.util.zip.GZIPInputStream(tgz.inputStream().buffered()),
        ).use { tar ->
            val hardLinks = ArrayList<Pair<File, String>>()
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(into, e.name)
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); out.delete()
                        runCatching { android.system.Os.symlink(e.linkName, out.path) }
                    }
                    e.isLink -> hardLinks += out to e.linkName
                    else -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
                if (!e.isSymbolicLink && !e.isLink) runCatching { android.system.Os.chmod(out.path, e.mode and 0x1FF or (if (e.isDirectory) 0x1C0 else 0x180)) }
            }
            hardLinks.forEach { (out, target) ->
                val src = File(into, target)
                if (src.isFile) { out.parentFile?.mkdirs(); src.copyTo(out, overwrite = true); runCatching { android.system.Os.chmod(out.path, android.system.Os.stat(src.path).st_mode and 0x1FF) } }
            }
        }
    }
}

/**
 * ETC fork: runs one command inside the built-in Chinese Termux core through proot.
 * Session state (cwd + exported vars) is kept like [ProotShell] via ProotSession at /root/.iris
 * (the core ships with a root home; the interactive HOME is /home, but commands use /root
 * session dir to mirror Alpine behavior).
 */
class TermuxCoreShell(context: Context, private val workspace: () -> File) : ShellRunner {
    private val ctx = context.applicationContext

    override fun run(command: String, cwd: File, timeoutMs: Long, maxBytes: Int): ShellOutput {
        if (!TermuxCore.supported(ctx)) return ShellOutput(127, "内置 Termux 不可用：安装包里缺少 proot", false, false)
        if (!TermuxCore.isInstalled(ctx)) return ShellOutput(127, "内置 Termux（中文核心）还没安装：在 设置 › 智能体 › 终端与浏览器 里点「安装内置 Termux」", false, false)
        val base = TermuxCore.base(ctx)
        val tmp = File(base, "tmp").apply { mkdirs() }
        val ws = workspace()
        val args = listOf(
            TermuxCore.proot(ctx).path,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", TermuxCore.rootfs(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${ws.path}:/workspace",
            "-w", "/workspace",
            "/bin/bash", "-c", ProotSession.wrap(command),
        )
        val env = mapOf(
            "PROOT_TMP_DIR" to tmp.path,
            "PROOT_LOADER" to TermuxCore.loader(ctx).path,
            "LD_LIBRARY_PATH" to ctx.applicationInfo.nativeLibraryDir,
            "HOME" to "/home",
            "USER" to "u0_a${ctx.applicationInfo.uid % 100000}",
            "PATH" to "/bin:/usr/bin:/sbin:/usr/sbin:/bin/../bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "PREFIX" to "/",
        )
        return ProcessRun.run(args, ws, env, clearEnv = true, timeoutMs = timeoutMs, maxBytes = maxBytes)
    }
}
