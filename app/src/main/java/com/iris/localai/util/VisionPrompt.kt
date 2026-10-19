package com.iris.localai.util

import com.iris.localai.engine.PromptMessage

/** 2.1.1: which prompt message carries the newest user message's pictures. Pure, JVM-tested. */
object VisionPrompt {
    /**
     * Index of the last user prompt message that is [text] (the newest user message with its
     * attachment notes), or that ends with it: 2.1.0 「引用知识库」 puts the snippets in front of the
     * user's words, and an exact match then failed, so the picture was silently left out. -1 = none.
     */
    fun targetIndex(messages: List<PromptMessage>, text: String): Int {
        val want = text.trim()
        if (want.isEmpty()) return -1
        val exact = messages.indexOfLast { it.role == PromptMessage.USER && it.content.trim() == want }
        if (exact >= 0) return exact
        return messages.indexOfLast { it.role == PromptMessage.USER && it.content.trimEnd().endsWith(want) }
    }

    /** Long side of the picture copy for an engine that asked for [engineMax] (0 = default). */
    fun maxSide(engineMax: Int): Int = if (engineMax > 0) engineMax else Attachments.VISION_MAX_SIDE
}
