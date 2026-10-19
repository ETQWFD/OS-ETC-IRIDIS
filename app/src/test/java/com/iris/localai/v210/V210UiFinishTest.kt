package com.iris.localai.v210

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1 UI finish: tween-free ui/, IRIDIS brand in visible copy, action-bar overflow, one-row drawer nav. */
class V210UiFinishTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()

    @Test fun noTweenOutsideMotion() {
        val ui = file("src/main/java/com/iris/localai/ui")
        assertTrue(ui.isDirectory)
        val offenders = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/ui/motion/") }
            .filter { it.readText().contains("tween(") }
            .map { it.name }.toList()
        assertEquals("animations only via IrisMotion", emptyList<String>(), offenders)
    }

    /** String literals that contain the old brand as a standalone word (IRIDIS / IRISLocalAI / IRIS_ don't count). */
    private fun staleBrand(text: String): List<String> =
        Regex("\"([^\"\\n]*)\"").findAll(text).map { it.groupValues[1] }
            .filter { Regex("(?<![A-Za-z])IRIS(?![A-Za-z_])").containsMatchIn(it) }.toList()

    @Test fun visibleCopySaysIridis() {
        val touched = listOf(
            "ui/MainActivity.kt", "ui/agent/ToolPages.kt", "ui/settings/SettingsViewModel.kt",
            "ui/chat/MessageItem.kt", "ui/chat/HistoryDrawer.kt",
            "agent/shell/Termux.kt", "agent/tools/Schedules.kt", "engine/load/GpuCacheDiag.kt",
            "service/LoadKeepAliveService.kt", "util/io/BackgroundGuard.kt",
        )
        for (p in touched) assertEquals(p, emptyList<String>(), staleBrand(src(p)))
        val strings = file("src/main/res/values/strings.xml").readText()
        assertFalse(Regex(">[^<]*(?<![A-Za-z])IRIS(?![A-Za-z_])[^<]*<").containsMatchIn(strings))
        // Compatibility names stay as they were.
        assertTrue(src("ui/agent/AgentExtras.kt").contains("\"IRIS-log-\""))
    }

    @Test fun assistantBarKeepsPrimaryAndOverflowsTheRest() {
        val item = src("ui/chat/MessageItem.kt")
        val row = item.substringAfter("private fun ActionRow(").substringBefore("@Composable")
        assertTrue(row.contains("\"复制\"") && row.contains("\"重新生成\"") && row.contains("\"朗读\""))
        assertTrue(row.contains("IrisIcons.More"))
        val menu = row.substringAfter("IrisMenu(")
        assertTrue(menu.contains("VersionText.EDIT_REPLY") && menu.contains("VersionText.REMEMBER") && menu.contains("ActionRowText.SHOW_PROMPT"))
        assertFalse("low-frequency actions are not top-level buttons", row.contains("PressableIcon(com.iris.localai.ui.icons.IrisIcons.Edit"))
        assertFalse(row.contains("PressableIcon(com.iris.localai.ui.icons.IrisIcons.Memory"))
    }

    @Test fun drawerNavIsOneRowOfIrisIcons() {
        val d = src("ui/chat/HistoryDrawer.kt")
        assertEquals(5, Regex("DrawerNavButton\\(com\\.iris\\.localai\\.ui\\.icons\\.IrisIcons\\.").findAll(d).count())
        for (dest in listOf("onModels", "onFiles", "onTerminal", "onApi", "onSettings")) assertTrue(dest, d.contains("onClick = $dest)"))
        assertFalse(d.contains("Icons.Outlined.Settings"))
        assertFalse(d.contains("NavRow(com.iris.localai.ui.icons.IrisIcons.Cube"))
    }
}
