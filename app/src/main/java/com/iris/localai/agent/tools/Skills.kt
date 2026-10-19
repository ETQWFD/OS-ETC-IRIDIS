package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject
import java.io.File

/** 1.12.0: one skill: a folder with a SKILL.md (agentskills.io layout). */
data class SkillInfo(val name: String, val description: String, val source: String, val body: () -> String, val files: () -> List<String> = { emptyList() })

/**
 * 1.12.0 Skills: instructions the model loads only when a task needs them, so they cost almost no
 * prompt (one line each). Built-in skills ship in the APK (assets/skills); the user adds their
 * own as `skills/<name>/SKILL.md` in the workspace (a same-named workspace skill wins). Scripts
 * next to a SKILL.md run with run_command (best with the Alpine backend).
 */
class SkillCatalog(private val builtIn: () -> List<SkillInfo>, private val workspace: () -> File?) {
    @Volatile private var cache: Pair<Long, List<SkillInfo>>? = null

    /** Built-in + workspace skills; the folder is re-scanned at most every [TTL_MS]. */
    fun all(): List<SkillInfo> {
        val now = System.currentTimeMillis()
        cache?.let { (t, l) -> if (now - t < TTL_MS) return l }
        val user = workspace()?.let { scan(it) }.orEmpty()
        val names = user.map { it.name }.toSet()
        return (user + builtIn().filter { it.name !in names }).sortedBy { it.name }.also { cache = now to it }
    }

    fun invalidate() { cache = null }

    fun find(name: String): SkillInfo? {
        val n = name.trim().lowercase()
        return all().firstOrNull { it.name.lowercase() == n } ?: all().firstOrNull { it.name.lowercase().contains(n) }
    }

    companion object {
        const val DIR = "skills"
        const val FILE = "SKILL.md"
        const val TTL_MS = 15_000L

        fun scan(root: File): List<SkillInfo> {
            val dir = File(root, DIR)
            return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
                val f = File(d, FILE).takeIf { it.isFile && it.length() < 200_000 } ?: return@mapNotNull null
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                val (meta, _) = frontMatter(text)
                SkillInfo(
                    name = meta["name"]?.takeIf { it.isNotBlank() } ?: d.name,
                    description = meta["description"].orEmpty(),
                    source = "$DIR/${d.name}",
                    body = { runCatching { f.readText() }.getOrDefault(text) },
                    files = { d.walkTopDown().filter { it.isFile && it.name != FILE }.take(30).map { "$DIR/${d.name}/" + it.relativeTo(d).path }.toList() },
                )
            }.orEmpty()
        }

        /** `---\nname: x\ndescription: y\n---\nbody` → (meta, body). Pure. */
        fun frontMatter(text: String): Pair<Map<String, String>, String> {
            val t = text.removePrefix("\uFEFF")
            if (!t.startsWith("---")) return emptyMap<String, String>() to t
            val end = t.indexOf("\n---", 3).takeIf { it > 0 } ?: return emptyMap<String, String>() to t
            val meta = t.substring(3, end).lines().mapNotNull { l ->
                val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim().trim('"', '\'')
            }.toMap()
            return meta to t.substring(end + 4).trimStart('-', '\n', '\r')
        }

        fun parse(name: String, text: String, source: String): SkillInfo {
            val (meta, _) = frontMatter(text)
            return SkillInfo(meta["name"] ?: name, meta["description"].orEmpty(), source, { text })
        }
    }
}

object SkillTool : AgentTool {
    override val name = "skill"
    override val label = "技能"
    override val description = "按需读取技能说明（内置技能 + 工作区 skills 文件夹里的 SKILL.md）"
    override val promptLine = "skill(name): load a skill's instructions, then follow them"
    override val risk = ToolRisk.READ
    override val group = ToolGroup.SKILLS

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val cat = env.skills ?: return ToolResult(false, "Error: skills are not available", "不可用")
        val n = Args.str(args, "name", "skill", "id") ?: throw IllegalArgumentException("缺少参数 name")
        val s = cat.find(n) ?: return ToolResult(false, "Error: no skill \"$n\". Available: ${cat.all().joinToString { it.name }}", "没有这个技能")
        val (_, body) = SkillCatalog.frontMatter(s.body())
        val files = s.files()
        val out = buildString {
            append("Skill ").append(s.name).append(" (").append(s.source).append(")\n").append(body.trim())
            if (files.isNotEmpty()) append("\n\nFiles: ").append(files.joinToString())
        }
        return ToolResult(true, capTokens(out, env.outputTokens * 2), s.name)
    }
}
