package com.iris.localai.v1143

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V1143MultiSelectTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @Test fun drawerClosingResetsSelection() {
        val d = src("ui/chat/HistoryDrawer.kt")
        assertTrue(d.contains("open: Boolean = true"))
        assertTrue(d.contains("LaunchedEffect(open) { if (!open) { selected = emptySet()"))
        assertTrue(src("ui/chat/ChatScreen.kt").contains("open = drawer.targetValue == DrawerValue.Open"))
    }
}
