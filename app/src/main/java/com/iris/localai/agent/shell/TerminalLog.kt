package com.iris.localai.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 1.12.2: one terminal history shared by the 终端 page and the agent's run_command, so the user
 * sees every command the agent ran (and its output) in the same place as their own. In memory only.
 */
object TerminalLog {
    data class Entry(val id: Long, val command: String, val output: String = "", val exit: Int? = null, val byAgent: Boolean = false, val at: Long = System.currentTimeMillis())

    const val MAX = 100
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var next = 1L

    @Synchronized
    fun start(command: String, byAgent: Boolean): Long {
        val id = next++
        _entries.update { (it + Entry(id, command, byAgent = byAgent)).takeLast(MAX) }
        return id
    }

    fun finish(id: Long, output: String, exit: Int) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(output = output, exit = exit) else it } }
    }

    fun clear() { _entries.value = emptyList() }
}
