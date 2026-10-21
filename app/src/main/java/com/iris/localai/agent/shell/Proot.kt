package com.iris.localai.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 1.12.0: output collector shared by the shells. Keeps the first and the last half of [maxBytes]
 * (an error at the end of a long build log is not lost), and says how much was cut.
 */
class HeadTailBuffer(private val maxBytes: Int) {
    private val head = java.io.ByteArrayOutputStream()
    private val tail = ArrayDeque<Byte>()
    private val half = maxBytes / 2
    var total = 0L; private set

    @Synchronized fun write(b: ByteArray, n: Int) {
        for (i in 0 until n) {
            total++
            if (head.size() < half) head.write(b[i].toInt()) else {
                tail.addLast(b[i]); if (tail.size > maxBytes - half) tail.removeFirst()
            }
        }
    }

    val truncated: Boolean @Synchronized get() = total > maxBytes

    @Synchronized fun text(): String {
        val h = head.toString(Charsets.UTF_8.name())
        val t = String(tail.toByteArray(), Charsets.UTF_8)
        if (!truncated) return h + t
        return h + "\n…[省略中间 ${total - maxBytes} 字节]…\n" + t
    }
}

/** 1.12.0: runs one process with a merged stdout/stderr, a timeout and a head+tail cap. */
object ProcessRun {
    fun run(cmd: List<String>, cwd: File, env: Map<String, String>, clearEnv: Boolean, timeoutMs: Long, maxBytes: Int): ShellOutput {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        if (clearEnv) pb.environment().keys.retainAll(setOf("ANDROID_ROOT", "ANDROID_DATA"))
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val buf = HeadTailBuffer(maxBytes)
        val reader = Thread {
            runCatching {
                p.inputStream.use { s ->
                    val b = ByteArray(8192)
                    while (true) { val n = s.read(b); if (n < 0) break; buf.write(b, n) }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(1_000)
        return ShellOutput(if (finished) p.exitValue() else -1, buf.text(), !finished, buf.truncated)
    }
}

/**
 * 1.12.0 built-in Linux: Alpine minirootfs under PRoot. Android forbids exec of files in app data
 * (W^X), but not of the app's own native libraries, so proot ships in jniLibs as `libproot.so`
 * with its loader as `libproot-loader.so` (Termux's proot 5.1.107, GPL-2.0, NEEDED patched to the
 * jniLibs names). The loader maps the rootfs' ELF files itself, so Alpine programs run. The
 * rootfs (~4 MB download, ~10 MB unpacked) is downloaded on request, checked against its SHA-256
 * and unpacked into filesDir/alpine/rootfs. The workspace is bound at /workspace.
 */
object AlpineRootfs {
    const val VERSION = "3.24.2"

    /** ETC fork (OS-ETC-IRIDIS): aarch64 rootfs on 64-bit devices, armv7 rootfs on 32-bit ones. */
    private val arch: String =
        if ((android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").startsWith("arm64")) "aarch64" else "armv7"

    // ETC fork: Tsinghua (北京清华) mirror for faster downloads in China.
    val URL: String =
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/$arch/alpine-minirootfs-3.24.2-$arch.tar.gz"

    // aarch64: 9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773 (4_028_030 bytes)
    // armv7:   5552f1ef2398cee46ef2cf733f8f1258f39a77ad065dfd47282fc0df9fe1976c (3_257_716 bytes)
    val SHA256: String =
        if (arch == "aarch64") "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
        else "5552f1ef2398cee46ef2cf733f8f1258f39a77ad065dfd47282fc0df9fe1976c"

    val SIZE: Long =
        if (arch == "aarch64") 4_028_030L else 3_180_242L

    fun base(ctx: Context) = File(ctx.filesDir, "alpine")
    fun rootfs(ctx: Context) = File(base(ctx), "rootfs")
    private fun marker(ctx: Context) = File(base(ctx), "installed-$VERSION")

    fun isInstalled(ctx: Context): Boolean = marker(ctx).isFile && File(rootfs(ctx), "bin/busybox").exists()

    /** proot and its loader as the installer extracted them (nativeLibraryDir). */
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

    fun refresh(ctx: Context) { if (_state.value !is State.Downloading && _state.value != State.Unpacking) _state.value = if (isInstalled(ctx)) State.Ready else State.Idle }

    /** Downloads, verifies and unpacks the rootfs. Blocking; call off the main thread. */
    fun install(ctx: Context) {
        if (_state.value is State.Downloading || _state.value == State.Unpacking) return
        val dir = base(ctx).apply { mkdirs() }
        val tgz = File(dir, "minirootfs.tar.gz")
        try {
            _state.value = State.Downloading(0, SIZE)
            download(tgz)
            val sum = sha256(tgz)
            if (!sum.equals(SHA256, true)) throw SecurityException("校验失败（SHA-256 不一致），已删除下载的文件")
            _state.value = State.Unpacking
            val root = rootfs(ctx)
            val tmp = File(dir, "rootfs.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            TarUnpack.unpackGz(tgz, tmp)
            setupNetwork(tmp)
            root.deleteRecursively()
            if (!tmp.renameTo(root)) throw IllegalStateException("无法移动 rootfs")
            File(dir, "tmp").mkdirs()
            dir.listFiles()?.filter { it.name.startsWith("installed-") }?.forEach { it.delete() }
            marker(ctx).writeText(SHA256)
            _state.value = State.Ready
        } catch (t: Throwable) {
            android.util.Log.w("IrisProot", "install failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            tgz.delete()
        }
    }

    fun uninstall(ctx: Context) { base(ctx).deleteRecursively(); _state.value = State.Idle }

    private fun download(to: File) {
        val c = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000 }
        try {
            if (c.responseCode != 200) throw java.io.IOException("下载失败：HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: SIZE
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val b = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(b); if (n < 0) break
                        out.write(b, 0, n); done += n
                        if (done > 64L * 1024 * 1024) throw java.io.IOException("文件过大")
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

    /** DNS for apk/pip (Android has no /etc/resolv.conf for the rootfs to inherit). */
    fun setupNetwork(root: File) {
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "workspace").mkdirs()
        File(root, "root").mkdirs()
    }
}

/** Unpacks a .tar.gz with symlinks and modes kept (commons-compress). */
object TarUnpack {
    fun unpackGz(tgz: File, into: File) {
        val canon = into.canonicalPath
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            java.util.zip.GZIPInputStream(tgz.inputStream().buffered()),
        ).use { tar ->
            val hardLinks = ArrayList<Pair<File, String>>()
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(canon) && !e.isSymbolicLink) continue
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
 * 1.12.0: run_command inside Alpine. Each command runs in a fresh `proot … /bin/sh`, but the shell
 * session is kept between commands: the working directory and exported variables are saved in
 * /root/.iris/ after each command and restored before the next (so `cd src` and `export X=1`
 * carry over). Background jobs end with the command (--kill-on-exit).
 */
class ProotShell(context: Context, private val workspace: () -> File) : ShellRunner {
    private val ctx = context.applicationContext

    override fun run(command: String, cwd: File, timeoutMs: Long, maxBytes: Int): ShellOutput {
        if (!AlpineRootfs.supported(ctx)) return ShellOutput(127, "内置 Linux 不可用：安装包里缺少 proot", false, false)
        if (!AlpineRootfs.isInstalled(ctx)) return ShellOutput(127, "内置 Linux（Alpine）还没安装：在 设置 › 智能体 › 终端与浏览器 里点「安装 Alpine」", false, false)
        val base = AlpineRootfs.base(ctx)
        val tmp = File(base, "tmp").apply { mkdirs() }
        val ws = workspace()
        val args = listOf(
            AlpineRootfs.proot(ctx).path,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", AlpineRootfs.rootfs(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${ws.path}:/workspace",
            "-w", "/workspace",
            "/bin/sh", "-c", ProotSession.wrap(command),
        )
        val env = mapOf(
            "PROOT_TMP_DIR" to tmp.path,
            "PROOT_LOADER" to AlpineRootfs.loader(ctx).path,
            "LD_LIBRARY_PATH" to ctx.applicationInfo.nativeLibraryDir,
            "HOME" to "/root",
            "USER" to "root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )
        return ProcessRun.run(args, ws, env, clearEnv = true, timeoutMs = timeoutMs, maxBytes = maxBytes)
    }
}

/** 1.12.0: keeps cwd and exported variables between commands. Pure (unit-tested). */
object ProotSession {
    const val DIR = "/root/.iris"

    fun wrap(command: String): String = buildString {
        append("mkdir -p $DIR; ")
        append("[ -f $DIR/env ] && . $DIR/env 2>/dev/null; ")
        append("cd \"\$(cat $DIR/cwd 2>/dev/null || echo /workspace)\" 2>/dev/null || cd /workspace; ")
        append("{ ").append(command.trim().trimEnd(';')).append("\n} ; __iris_rc=\$?; ")
        append("pwd > $DIR/cwd; export -p > $DIR/env 2>/dev/null; ")
        append("exit \$__iris_rc")
    }
}
