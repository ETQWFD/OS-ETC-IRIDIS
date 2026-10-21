package com.iris.localai.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.11.0: where run_command and the 终端 page run commands.
 *  - BUILTIN: Android's own sh + toybox in the workspace, strict refusal rules (1.10.6/1.10.7).
 *  - TERMUX: a full Linux userland (bash, pkg/apt, python, git, node …) in the Termux app, reached
 *    through its RUN_COMMAND service.
 *  - PROOT (1.12.0): Alpine under PRoot inside IRIS — proot runs from nativeLibraryDir, which W^X
 *    allows (the 1.11.0 note that this was impossible was wrong).
 */
enum class CommandBackend(val label: String, val short: String) {
    BUILTIN("内置（toybox）", "内置 sh"), TERMUX("Termux（完整 Linux）", "Termux"),
    /** 1.12.0: Alpine Linux under PRoot inside IRIS (downloaded on request). */
    PROOT("内置 Linux（Alpine）", "Alpine");

    companion object {
        fun parse(s: String?): CommandBackend = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: BUILTIN
    }
}

object TermuxBridge {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"
    const val DOWNLOAD_URL = "https://github.com/termux/termux-app/releases/latest"
    /** ETC fork: ZoneTermux32 — Termux fork with active 32-bit (armeabi-v7a) builds.
     *  Termux official stopped 32-bit support in 2023; ZoneTermux32 is the recommended
     *  terminal backend on 32-bit phones. */
    const val ZONA_PACKAGE = "com.zonetermux"
    const val ZONA_PERMISSION = "com.zonetermux.permission.RUN_COMMAND"
    const val ZONA_BASH = "/data/data/com.zonetermux/files/usr/bin/bash"
    const val ZONA_HOME = "/data/data/com.zonetermux/files/home"
    const val ZONA_DOWNLOAD_URL = "https://github.com/ZoneTermux/ZoneTermux/releases/latest"
    /** The one line the user runs in Termux once so other apps may send it commands. */
    const val SETUP = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-setup-storage"

    /** ETC fork: true on 32-bit ARM devices (armeabi-v7a), where ZoneTermux32 is preferred. */
    private val is32Bit: Boolean =
        !(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").startsWith("arm64") &&
        !(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").startsWith("x86_64")

    private fun packageInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Resolved backend package: ZoneTermux32 on 32-bit if installed, else Termux. */
    fun activePackage(ctx: Context): String =
        if (is32Bit && packageInstalled(ctx, ZONA_PACKAGE)) ZONA_PACKAGE else PACKAGE
    fun activePermission(ctx: Context): String =
        if (activePackage(ctx) == ZONA_PACKAGE) ZONA_PERMISSION else PERMISSION
    fun activeBash(ctx: Context): String =
        if (activePackage(ctx) == ZONA_PACKAGE) ZONA_BASH else BASH
    fun activeHome(ctx: Context): String =
        if (activePackage(ctx) == ZONA_PACKAGE) ZONA_HOME else HOME
    val activeDownloadUrl: String get() = if (is32Bit) ZONA_DOWNLOAD_URL else DOWNLOAD_URL
    fun activeLabel(ctx: Context): String =
        if (activePackage(ctx) == ZONA_PACKAGE) "ZoneTermux32" else "Termux"

    fun isInstalled(ctx: Context): Boolean = packageInstalled(ctx, activePackage(ctx))

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(activePermission(ctx)) == PackageManager.PERMISSION_GRANTED

    /** Status line for the settings / terminal page; null = ready. */
    fun problem(ctx: Context): String? = when {
        !isInstalled(ctx) -> "未安装${activeLabel(ctx)}" + (if (is32Bit) "（32位手机请装 ZoneTermux32）" else "")
        !hasPermission(ctx) -> "未授权 IRIDIS 向 ${activeLabel(ctx)} 发送命令"
        else -> null
    }
}

/**
 * Runs one command in Termux and waits for its result (stdout, stderr, exit code), delivered to a
 * one-off broadcast receiver through the PendingIntent Termux fills in.
 */
class TermuxShell(context: Context) : ShellRunner {
    private val ctx = context.applicationContext
    private val ids = AtomicInteger(1)

    override fun run(command: String, cwd: File, timeoutMs: Long, maxBytes: Int): ShellOutput {
        TermuxBridge.problem(ctx)?.let { return ShellOutput(-1, "${TermuxBridge.activeLabel(ctx)}: $it", false, false) }
        val pkg = TermuxBridge.activePackage(ctx)
        val id = ids.getAndIncrement()
        val action = "${ctx.packageName}.TERMUX_RESULT.$id"
        val latch = CountDownLatch(1)
        val result = AtomicReference<android.os.Bundle?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                result.set(intent?.getBundleExtra("result"))
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            val workdir = if (cwd.path.startsWith("/storage/") || cwd.path.startsWith("/sdcard")) cwd.path else TermuxBridge.activeHome(ctx)
            val intent = Intent("$pkg.RUN_COMMAND")
                .setClassName(pkg, "$pkg.app.RunCommandService")
                .putExtra("$pkg.RUN_COMMAND_PATH", TermuxBridge.activeBash(ctx))
                .putExtra("$pkg.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("$pkg.RUN_COMMAND_WORKDIR", workdir)
                .putExtra("$pkg.RUN_COMMAND_BACKGROUND", true)
                .putExtra("$pkg.RUN_COMMAND_PENDING_INTENT", pi)
            runCatching { ctx.startService(intent) }.onFailure {
                return ShellOutput(-1, "${TermuxBridge.activeLabel(ctx)}: 无法启动（${it.message}）。请先打开一次 ${TermuxBridge.activeLabel(ctx)} 并运行：${TermuxBridge.SETUP}", false, false)
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return ShellOutput(-1, "", true, false)
            val b = result.get() ?: return ShellOutput(-1, "${TermuxBridge.activeLabel(ctx)}: 没有返回结果", false, false)
            val err = b.getInt("err", -1)
            val errmsg = b.getString("errmsg").orEmpty()
            val text = TermuxResult.merge(b.getString("stdout"), b.getString("stderr"), if (err > 0 && errmsg.isNotBlank()) errmsg else null)
            val bytes = text.toByteArray(Charsets.UTF_8)
            val truncated = bytes.size > maxBytes
            val out = if (truncated) String(bytes, 0, maxBytes, Charsets.UTF_8) else text
            return ShellOutput(b.getInt("exitCode", if (err > 0) -1 else 0), out, false, truncated)
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
}

/** Pure helpers (unit-tested). */
object TermuxResult {
    fun merge(stdout: String?, stderr: String?, error: String?): String = buildString {
        stdout?.trimEnd()?.takeIf { it.isNotEmpty() }?.let { append(it) }
        stderr?.trimEnd()?.takeIf { it.isNotEmpty() }?.let { if (isNotEmpty()) append('\n'); append(it) }
        error?.let { if (isNotEmpty()) append('\n'); append("[Termux] ").append(it) }
    }

    private val DANGEROUS = listOf(
        Regex("(^|[\\s;|&])(su|sudo|tsu|reboot|shutdown|poweroff)(\\s|$)"),
        Regex("rm\\s+(-[a-zA-Z]*\\s+)*(/|~|\\\$HOME|/sdcard|/storage\\S*)(\\s|/?\\*?\\s*$|$)"),
        Regex("(^|\\s)dd\\s.*of=/dev/"),
        Regex("(^|\\s)mkfs"),
        Regex(":\\(\\)\\s*\\{"),
    )

    /** Termux backend: full shell, only the plainly destructive shapes are refused. */
    fun refusal(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return "命令为空"
        if (c.length > 4000) return "命令过长"
        if ('\u0000' in c) return "命令含非法字符"
        if (DANGEROUS.any { it.containsMatchIn(c) }) return "这条命令可能破坏系统或删除大量文件，已拒绝"
        return null
    }
}
