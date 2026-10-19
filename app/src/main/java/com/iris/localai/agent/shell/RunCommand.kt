package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 1.10.6: result of one shell command. */
data class ShellOutput(val exitCode: Int, val output: String, val timedOut: Boolean, val truncated: Boolean)

/** Runs one command line; injected so the tool is testable. */
fun interface ShellRunner {
    fun run(command: String, cwd: File, timeoutMs: Long, maxBytes: Int): ShellOutput
}

/**
 * 1.10.6: Android's own `/system/bin/sh` (mksh + toybox: ls, cat, grep, sed, find, head, tail, wc,
 * sort, cp, mv, mkdir, rm, diff, md5sum, date …), started in the workspace folder. stdout and stderr
 * merged, capped at [maxBytes], killed after the timeout. It runs with the app's own rights only —
 * no root, no access to other apps' data.
 */
object AndroidShell : ShellRunner {
    override fun run(command: String, cwd: File, timeoutMs: Long, maxBytes: Int): ShellOutput {
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val p = ProcessBuilder(sh, "-c", command).directory(cwd).redirectErrorStream(true).apply {
            environment().keys.retainAll(setOf("PATH", "ANDROID_ROOT", "ANDROID_DATA", "LANG"))
            environment()["HOME"] = cwd.path
            environment()["PWD"] = cwd.path
            environment()["TMPDIR"] = cwd.path
        }.start()
        p.outputStream.close()
        // 1.12.0: keep the start and the end of long output (errors are usually last).
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
        val text = buf.text()
        val truncated = buf.truncated
        return ShellOutput(if (finished) p.exitValue() else -1, text, !finished, truncated)
    }
}

/**
 * 1.10.6 `run_command`: a shell command in the workspace folder, like a Linux terminal (toybox).
 * Asks first by default (it can change files and is not covered by 撤销). Commands that could
 * leave the workspace are refused by [refusal]; it is a strict check, not a jail (no root).
 */
object RunCommandTool : AgentTool {
    /** A single `&` (background): not part of `&&`, `2>&1`, `&>`. */
    private val LONE_AMP = Regex("(?<![&><])&(?![&>\\d])")

    override val group = ToolGroup.SHELL
    override val name = "run_command"
    override val label = "运行命令"
    override val description = "在工作区里运行 shell 命令（ls、cat、grep、sed、find、wc 等，类似 Linux 终端；不可撤销）"
    /** 1.11.0/1.12.0: the backend chosen now (set by AgentContainer.env). */
    @Volatile var kind: CommandBackend = CommandBackend.BUILTIN
    override val promptLine: String get() = when (kind) {
        CommandBackend.TERMUX -> "run_command(command): bash command in the workspace (full Linux: pkg, python, git, curl)"
        CommandBackend.PROOT -> "run_command(command): Alpine Linux shell, starts in /workspace; cd and export carry over (apk add python3 git curl)"
        CommandBackend.BUILTIN -> "run_command(command): shell command in the workspace (toybox: ls cat grep sed find wc cp mv rm)"
    }
    override val risk = ToolRisk.DELETE
    override fun permissionFor(args: JsonObject, base: Permission, env: ToolEnv): Permission = commandPermission(args, base, env)
    const val TIMEOUT_MS = 20_000L
    /** 1.11.0: Termux commands (pkg install, pip …) may take longer. */
    const val TERMUX_TIMEOUT_MS = 120_000L
    const val MAX_BYTES = 64 * 1024

    /** Every word that can leave the folder, start another shell, or change the system. */
    private val BLOCKED_WORDS = setOf(
        "cd", "pushd", "popd", "ln", "sh", "bash", "mksh", "ash", "zsh", "eval", "exec", "source", "env",
        "export", "chroot", "nsenter", "unshare", "su", "sudo", "nohup", "setsid", "xargs", "busybox", "toybox",
        "mount", "umount", "reboot", "setprop", "getprop", "pm", "am", "cmd", "settings", "svc", "dumpsys",
        "run-as", "logcat", "kill", "killall", "pkill", "chmod", "chown", "chgrp", "realpath",
        "readlink", "tar", "unzip", "cpio", "nc", "netcat", "curl", "wget", "ssh", "python", "perl",
    )
    private val SEPARATORS = Regex("[\\s;|&(){}<>!]+")

    /**
     * 1.10.6: why [command] is refused, or null when it may run. Pure, unit-tested. Android has no
     * chroot without root, so this is a strict allow-by-shape check rather than a jail: no `..`,
     * `~`, `$` or backticks (no variables or substitution), no absolute paths (except /dev/null),
     * no symlink creation, and none of [BLOCKED_WORDS] as a word.
     */
    /** 1.13.0: true when a quote opens or closes in the middle of a word (x"args", ev''al). */
    fun quoteInsideWord(c: String): Boolean {
        val bound = " \t;|&()<>"
        var q: Char? = null
        for (i in c.indices) {
            val ch = c[i]
            if (q == null) {
                if (ch == '\'' || ch == '"') {
                    if (i > 0 && c[i - 1] !in bound && c[i - 1] != '=') return true
                    q = ch
                }
            } else if (ch == q) {
                if (i + 1 < c.length && c[i + 1] !in bound) return true
                q = null
            }
        }
        return false
    }

    fun refusal(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return "命令为空"
        if (c.length > 2000) return "命令过长"
        if ('\u0000' in c || '\n' in c || '\r' in c) return "命令只能是一行"
        if (".." in c) return "不允许使用 ..（只能在工作区内）"
        if ('~' in c) return "不允许使用 ~"
        if ('$' in c || '`' in c) return "不允许使用变量或命令替换（\$、`）"
        // 1.13.0: quotes/backslashes inside a word hide blocked names (x"args", c\d, ev''al) and
        // printf/echo escapes can spell absolute paths without a "/".
        if ('\\' in c) return "不允许使用反斜杠 \\"
        if (quoteInsideWord(c)) return "引号只能包住整个参数（如 'a b'），不能夹在词中间"
        if (LONE_AMP.containsMatchIn(c)) return "不允许后台运行（&）"
        val noDevNull = c.replace("/dev/null", "")
        if (Regex("(^|[\\s;|&()<>=:'\"])/").containsMatchIn(noDevNull)) return "只能使用工作区内的相对路径（不允许绝对路径）"
        val words = c.split(SEPARATORS).map { it.trim('\'', '"') }.filter { it.isNotEmpty() }
        words.firstOrNull { it.lowercase() in BLOCKED_WORDS || it.substringAfterLast('/').lowercase() in BLOCKED_WORDS }?.let {
            return "不允许运行「$it」（可能离开工作区或改动系统）"
        }
        if (Regex("(^|\\s)cp\\s.*(-[a-zA-Z]*s\\b|--symbolic)").containsMatchIn(c)) return "不允许创建符号链接"
        if (Regex("(^|\\s)find\\s.*(-L\\b|-follow|-exec|-ok|-delete)").containsMatchIn(c) || Regex("(^|\\s)find\\s+-L\\b").containsMatchIn(c)) return "find 不允许 -L / -exec / -delete（删除请用 rm 或 delete_file）"
        return null
    }

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val command = Args.str(args, "command", "cmd", "shell", "script") ?: throw IllegalArgumentException("缺少参数 command")
        (if (env.shellKind == CommandBackend.BUILTIN) refusal(command) else TermuxResult.refusal(command))
            ?.let { return ToolResult(false, "Error: $it", it) }
        val shell = env.shell ?: return ToolResult(false, "Error: shell not available", "无法运行命令")
        val timeout = if (env.shellKind == CommandBackend.BUILTIN) TIMEOUT_MS else TERMUX_TIMEOUT_MS
        val logId = TerminalLog.start(command, byAgent = true)
        val out = try { shell.run(command, env.sandbox.root, timeout, MAX_BYTES) } catch (t: Throwable) {
            TerminalLog.finish(logId, "错误：${t.message}", -1); throw t
        }
        TerminalLog.finish(logId, out.output.trimEnd() + (if (out.timedOut) "\n[超时已终止]" else ""), out.exitCode)
        val body = buildString {
            append(out.output.ifBlank { "(no output)" }.trimEnd())
            if (out.truncated) append("\n…[output truncated]")
            if (out.timedOut) append("\n[killed after ${timeout / 1000}s]")
            append("\n[exit ${out.exitCode}]")
        }
        val ok = out.exitCode == 0 && !out.timedOut
        return ToolResult(ok, capTokens(body, env.outputTokens), if (out.timedOut) "超时已终止" else "退出码 ${out.exitCode}")
    }
}
