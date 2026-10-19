package com.iris.localai.v1146

import com.iris.localai.ui.chat.ComposerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.14.6: empty chat with a model loaded shows only the mark + greeting; placeholders say IRIDIS. */
class V1146EmptyChatTest {
    private val src = listOf(File("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt"), File("app/src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")).first { it.exists() }.readText()

    @Test fun noExamplePromptsOrModelSubtitle() {
        assertFalse(src.contains("EXAMPLE_PROMPTS"))
        assertFalse(src.contains("完全在本机运行"))
        assertTrue(src.contains("点击顶部选择一个本地模型")) // no-model hint stays
    }

    @Test fun placeholdersRenamed() {
        assertEquals("给 IRIDIS 发送消息", ComposerText.PLACEHOLDER)
        assertTrue(ComposerText.AGENT_PLACEHOLDER.startsWith("交给 IRIDIS"))
    }
}
