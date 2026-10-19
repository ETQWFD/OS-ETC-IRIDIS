package com.iris.localai.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.14.0: process-wide live state the 运行状态 rack reads (the chat screen writes it). Nothing here
 * is persisted; it only mirrors what is happening right now.
 */
object SystemStatus {
    data class Live(
        /** A reply is being written. */
        val generating: Boolean = false,
        /** An 智能体 run is active. */
        val agentRunning: Boolean = false,
        /** Label of the tool running now (null = none). */
        val tool: String? = null,
        /** Tool group of that tool (ToolGroup.name), to light the right slot. */
        val toolGroup: String? = null,
        /** Voice input or read-aloud is active. */
        val voiceActive: Boolean = false,
        /** Steps finished in the current/last agent run. */
        val stepsDone: Int = 0,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    fun update(f: (Live) -> Live) { _live.value = f(_live.value) }
}
