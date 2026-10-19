package com.iris.localai.v210plan

import com.iris.localai.agent.StepTitle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** 2.1.0: step card headers for people, one mapping with a label fallback. */
class StepTitleTest {
    private fun t(tool: String, vararg kv: Pair<String, String>) = StepTitle.of(tool, buildJsonObject { kv.forEach { put(it.first, it.second) } })

    @Test fun mapping() {
        assertEquals("搜索「今日天气」", t("web_search", "query" to "今日天气"))
        assertEquals("读取 notes.md", t("read_file", "path" to "docs/notes.md"))
        assertEquals("写入 out.txt", t("write_file", "path" to "a/out.txt", "content" to "x".repeat(500)))
        assertEquals("修改 a.kt", t("edit_file", "path" to "a.kt", "old" to "x", "new" to "y"))
        assertEquals("打开网页 example.com", t("web_fetch", "url" to "https://www.example.com/a/b?c=1"))
        assertEquals("运行命令 ls -la", t("run_command", "command" to "ls\n-la"))
        assertEquals("移动 a.txt → b.txt", t("move_file", "from" to "a.txt", "to" to "x/b.txt"))
        assertEquals("搜索文件「todo」", t("search_files", "query" to "todo"))
        assertEquals("计划 · 写周报", t("plan", "action" to "set", "title" to "写周报"))
        assertEquals("计划", t("plan", "action" to "done"))
        assertEquals("列出目录", t("list_dir"))
        assertEquals("打开网页 「天气 北京」", t("browser", "action" to "open", "url" to "天气 北京"))
    }

    @Test fun noKeyValueAndFallback() {
        assertFalse("=" in t("web_search", "query" to "a"))
        assertEquals("计算器", t("calculator"))
        assertEquals("读取文件", t("read_file"))
        assertEquals("some_tool", t("some_tool", "x" to "1"))
        assertTrue(t("web_search", "query" to "很".repeat(100)).length <= 44)
    }
}
