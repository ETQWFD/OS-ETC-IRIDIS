package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject

/**
 * 1.12.0: run_command permissions by command prefix (Claude-Code-style "Bash(git status:*)").
 * Read-only commands such as `ls`, `cat` or `grep` run without asking; a user rule
 * ("git status" → 允许, "rm" → 拒绝) wins over the defaults, the longest matching prefix first.
 * A line with several commands (`a && b | c`) is only auto-allowed when every part is, and any
 * output redirection (`>`) asks. Pure, unit-tested.
 */
object CommandRules {
    /** Read-only commands that never change anything. */
    val READ_ONLY = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "find", "pwd", "echo", "printf", "date", "stat",
        "du", "df", "sort", "uniq", "diff", "cmp", "md5sum", "sha1sum", "sha256sum", "file", "which", "tree", "uname",
        "whoami", "id", "true", "basename", "dirname", "cut", "tr", "nl", "od", "hexdump", "xxd", "less", "more",
        "rg", "jq", "column", "seq", "expr", "test", "free", "uptime", "ps",
    )
    /** Commands whose rule is remembered by their first two words (`git status`, `apk add`). */
    val TWO_WORD = setOf("git", "apk", "apt", "apt-get", "pkg", "pip", "pip3", "npm", "pnpm", "yarn", "cargo", "go", "docker", "termux-setup-storage")

    private val SPLIT = Regex("\\s*(?:&&|\\|\\||;|\\|)\\s*")
    private val FIND_WRITES = Regex("(^|\\s)-(exec|execdir|ok|delete|fprint\\w*)\\b")

    fun segments(command: String): List<String> = command.trim().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** The prefix 总是允许 remembers for [command] (first command of the line). */
    fun prefixOf(command: String): String {
        val words = segments(command).firstOrNull().orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.dropWhile { it.contains('=') && !it.startsWith("-") }
        if (words.isEmpty()) return ""
        val first = words[0].substringAfterLast('/')
        return if (first in TWO_WORD && words.size > 1 && !words[1].startsWith("-")) "$first ${words[1]}" else first
    }

    private fun matches(segment: String, prefix: String): Boolean {
        val s = segment.trim().replace(Regex("\\s+"), " ")
        val p = prefix.trim().replace(Regex("\\s+"), " ")
        return p.isNotEmpty() && (s == p || s.startsWith("$p "))
    }

    private fun readOnly(segment: String): Boolean {
        if ('>' in segment || '`' in segment || "$(" in segment || "<(" in segment || '&' in segment) return false
        val w = segment.split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return false
        if (w !in READ_ONLY) return false
        if (w == "find" && FIND_WRITES.containsMatchIn(segment)) return false
        // 1.13.0: read-only tools that can still write a file.
        val args = segment.split(Regex("\\s+")).drop(1)
        if (w == "sort" && args.any { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && 'o' in it) }) return false
        if (w == "uniq" && args.count { !it.startsWith("-") } > 1) return false
        if (w == "tree" && args.any { it == "-o" }) return false
        return true
    }

    /** The rule for one segment: the user's longest matching prefix, else read-only → ALLOW, else null. */
    fun ruleFor(segment: String, user: Map<String, Permission>): Permission? =
        user.entries.filter { matches(segment, it.key) }.maxByOrNull { it.key.length }?.value
            ?: if (readOnly(segment)) Permission.ALLOW else null

    /**
     * The permission of [command] given the tool setting [base]: a DENY rule on any part denies;
     * with [base] ASK the line runs without asking only when every part is ALLOW.
     */
    fun decide(command: String, base: Permission, user: Map<String, Permission>): Permission {
        if (base == Permission.DENY) return Permission.DENY
        val parts = segments(command)
        if (parts.isEmpty()) return base
        val rules = parts.map { ruleFor(it, user) }
        if (rules.any { it == Permission.DENY }) return Permission.DENY
        if (base == Permission.ALLOW) return Permission.ALLOW
        // Substitution can hide any command inside an allowed one.
        if ('`' in command || "$(" in command || "<(" in command) return Permission.ASK
        return if (rules.all { it == Permission.ALLOW }) Permission.ALLOW else Permission.ASK
    }

    fun parse(map: Map<String, String>): Map<String, Permission> =
        map.mapNotNull { (k, v) -> Permission.parse(v)?.let { k.trim() to it } }.filter { it.first.isNotEmpty() }.toMap()
}

/** Shared by run_command: the per-call permission. */
internal fun commandPermission(args: JsonObject, base: Permission, env: ToolEnv): Permission {
    val cmd = Args.str(args, "command", "cmd", "shell", "script") ?: return base
    return CommandRules.decide(cmd, base, env.commandRules)
}
