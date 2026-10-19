package com.iris.localai.agent

import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class StepStatus { RUNNING, ASKING, OK, ERROR, DENIED }

/** One tool step of an agent reply; stored on the assistant message and drawn as a card. */
@Serializable
data class AgentStep(
    val id: String = UUID.randomUUID().toString(),
    /** Tool name (or "格式错误" for a call that could not be read). */
    val tool: String,
    val args: String = "",
    val result: String = "",
    val status: StepStatus = StepStatus.RUNNING,
    /** Tool output as the model saw it (capped), for the expanded card. */
    val detail: String = "",
    /** 1.13.3: what the model thought/wrote in the turn that made this call (shown above the card). */
    val lead: String = "",
    /** 2.1.0: the card header for people (搜索「q」, 读取 a.txt); [args] stays the model-facing form. */
    val title: String = "",
    /** 2.1.0: the plan after a `plan` step, drawn as a checklist (never from [detail]). */
    val plan: Plan? = null,
)

/**
 * The system prompt for 智能体 mode: compact (well under 800 estimated tokens with every tool on),
 * and only the enabled tools are listed. English for token economy; the model answers in Chinese.
 */
object AgentPrompt {
    /**
     * 1.10.12: how the model is told to call tools. Qwen-family models (Qwen2.5/3, Agents-A1,
     * DeepSeek-R1-Distill-Qwen, Hermes) are trained on `<tool_call>{"name":…,"arguments":…}</tool_call>`;
     * everything else gets the neutral fenced block. The parser accepts both either way.
     */
    enum class CallStyle { QWEN, FENCE }

    fun styleFor(modelName: String?): CallStyle {
        val n = modelName.orEmpty().lowercase()
        return if (QWEN_HINTS.any { n.contains(it) }) CallStyle.QWEN else CallStyle.FENCE
    }
    private val QWEN_HINTS = listOf("qwen", "agents-a1", "agents_a1", "hermes", "qwq", "minicpm4", "internlm3")

    /**
     * 1.10.12: system prompt restructured after Claude Code's layout: who you are → how to talk →
     * how to do a task → how to call tools (exact format + one example) → tools → environment.
     * Short imperative lines, one rule each; small on-device models follow these far better than
     * one long paragraph. Kept small: every token here is prefilled on the phone.
     */
    fun build(
        tools: List<AgentTool>, userSystem: String = "", style: CallStyle = CallStyle.FENCE,
        /** 1.12.0: skills found in the workspace (listed only while the skill tool is on). */
        skills: List<SkillInfo> = emptyList(),
        /** 1.14.0 按需载入: [ActivateTool.LAZY] groups are listed by name only. */
        lazy: Boolean = false,
        /** 1.14.0: the conversation's plan (kept here so compaction never drops it). */
        plan: Plan? = null,
        /** 1.14.0 计划模式: plan first, wait for the user's OK, then do it. */
        planFirst: Boolean = false,
    ): String = buildString {
        append("You are IRIS, an AI agent on the user's Android phone (on-device model), helping with everyday tasks and workspace files.\n\n")

        append("# Tone\n")
        append("- Reply in Chinese, concise, answer first.\n")
        append("- Never claim you did something you did not do.\n\n")

        append("# Doing tasks\n")
        append("- Chat or simple questions: plain text with NO tool block (\"system\" is not a tool).\n")
        append("- Use a tool only when the task needs it; one step at a time.\n")
        append("- When done, answer briefly with what you did. If a tool fails, fix it once or report the error.\n\n")

        append("# Tool calls\n")
        if (style == CallStyle.QWEN) {
            append("To call a tool, reply with ONLY this, then stop and wait:\n")
            append("<tool_call>\n{\"name\": \"<tool>\", \"arguments\": {<args as JSON>}}\n</tool_call>\n")
            append("Example:\n<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"notes/todo.md\"}}\n</tool_call>\n")
        } else {
            append("To call a tool, reply with ONLY this block, then stop and wait:\n")
            append("```tool\n{\"tool\": \"<name>\", \"args\": {<arguments>}}\n```\n")
            append("Example:\n```tool\n{\"tool\": \"read_file\", \"args\": {\"path\": \"notes/todo.md\"}}\n```\n")
        }
        append("- One call per reply, valid JSON. Results come back in <tool_response>; never write one yourself.\n")
        append("- Only the exact tool names below. Asked what tools you have? List them in words.\n\n")

        if (tools.isNotEmpty()) {
            // 1.12.0: grouped, one shared hint per group.
            append("# Tools (? = optional)\n")
            val activatable = lazy && tools.any { it.name == ActivateTool.name }
            val folded = if (activatable) tools.filter { it.group in ActivateTool.LAZY } else emptyList()
            tools.filter { it !in folded && (it.name != ActivateTool.name || folded.isNotEmpty()) }.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (g, ts) ->
                append("## ").append(g.title)
                if (g.hint.isNotEmpty()) append(" — ").append(g.hint)
                append('\n')
                ts.forEach { append("- ").append(it.promptLine).append('\n') }
            }
            if (folded.isNotEmpty()) {
                append("## More (call activate(group) first)\n")
                folded.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (g, ts) ->
                    append("- ").append(ActivateTool.key(g)).append(": ").append(ts.joinToString(", ") { it.name }).append('\n')
                }
            }
            if (skills.isNotEmpty() && tools.any { it.name == SkillTool.name }) {
                append("\n# Skills (load with skill(name) when the task matches)\n")
                skills.take(MAX_SKILLS_LISTED).forEach { append("- ").append(it.name).append(": ").append(it.description.take(120)).append('\n') }
            }
        } else {
            append("# Tools\nNo tools are enabled; answer in plain text.\n")
        }
        if (planFirst && tools.any { it.name == PlanTool.name }) {
            append("\n# Plan mode\n- For a task with several steps: first call plan(action: set, steps: [...]), then show the plan in one short reply and ask the user to confirm. Start only after the user agrees.\n")
        }
        if (plan != null && plan.steps.isNotEmpty() && !plan.finished) append('\n').append(plan.promptBlock()).append('\n')
        if (userSystem.isNotBlank()) append("\n# User instructions\n").append(userSystem.trim()).append('\n')
    }.trimEnd()

    const val MAX_SKILLS_LISTED = 12

    fun toolResponse(name: String, r: ToolResult): String =
        "<tool_response>\n{\"tool\": \"$name\", \"ok\": ${r.ok}}\n${r.output}\n</tool_response>"

fun retryHint(error: String): String =
        "<tool_response>\nError: your tool call could not be read ($error). Reply again with exactly one tool call " +
            "in the format from the system prompt (valid JSON, double quotes), or answer without any tool.\n</tool_response>"
    /** 1.13.0: the model wrote a 〔本轮已执行的工具〕 log itself — nothing ran. */
    const val FAKE_LOG_HINT =
        "<tool_response>\nError: no tool ran. You wrote a tool log yourself and its results are invented. " +
            "That block in history is written by the app, never by you. If you need a tool, reply with exactly one real tool call " +
            "in the format from the system prompt; otherwise answer the user without that block.\n</tool_response>"

    /** 1.10.4: silent retry hint after a call to a tool that does not exist. */
    fun unknownToolHint(name: String, available: Set<String>): String =
        "<tool_response>\nError: there is no tool \"$name\". Do not guess tool names. Use one of: ${available.joinToString()} — or answer the user in plain text without any tool.\n</tool_response>"

    const val STEP_LIMIT = "Step limit reached. Do not call any more tools. Tell the user in Chinese what was done and what is left."
    const val ELIDED = "<tool_response>\n(earlier tool output removed to save context)\n</tool_response>"
}

data class ModelTurn(val text: String, val error: String? = null)

/** One model completion; the app's adapter streams through [onText] (full text so far). */
interface AgentModel {
    suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn
}

interface AgentCallbacks {
    /** Asks the user (in-chat dialog). Suspends until answered; cancellation ends the wait. */
    suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision
    /** Steps changed (a new step, or a step's status). */
    fun onSteps(steps: List<AgentStep>)
    /** The model's text of the current turn so far (call block hidden). */
    fun onText(text: String) {}
    /** 总是允许 was chosen. */
    fun onAlwaysAllow(tool: AgentTool) {}
    /** 1.12.0: 总是允许 for one call; run_command remembers the command's prefix, not the whole tool. */
    fun onAlwaysAllowCall(tool: AgentTool, call: ToolCall) = onAlwaysAllow(tool)
    /** A tool changed the workspace (write / edit / delete). */
    fun onWorkspaceChanged() {}
    /** 1.10.0: the memory tool changed USER.md / MEMORY.md. */
    fun onMemoryChanged() {}
}

enum class AgentStop { DONE, MAX_STEPS, ERROR }

data class AgentOutcome(
    /** Raw text of the final turn (think tags kept; the UI parses them). */
    val finalText: String,
    val steps: List<AgentStep>,
    val stop: AgentStop,
    val error: String? = null,
    /**
     * 1.10.6: the whole prompt of this run as the engine saw it (system, history, every tool call
     * and result, and the final answer). The next 智能体 turn continues from it, so the engine's
     * KV cache is reused and the model keeps the tool results of earlier turns.
     */
    val transcript: List<PromptMessage> = emptyList(),
)

/**
 * 1.9.0 Pi-style minimal agent loop: model → parse → permission → execute → result appended →
 * again, until the model answers without a tool, [maxSteps] tool calls ran, or the coroutine is
 * cancelled (stop button; [CancellationException] propagates). A call that cannot be parsed is
 * retried once with an error hint. Older tool outputs are elided when the history outgrows
 * [contextBudget] (estimated tokens). Engine-agnostic and unit-tested with a fake model.
 */
class AgentLoop(
    private val model: AgentModel,
    private val tools: List<AgentTool>,
    private val permissionOf: (AgentTool) -> Permission,
    private val env: ToolEnv,
    private val callbacks: AgentCallbacks,
    private val maxSteps: Int = AgentSettings.DEFAULT_MAX_STEPS,
    private val contextBudget: Int = Int.MAX_VALUE,
    /** Text of a turn → the answer part (reasoning removed). */
    private val answerOf: (String) -> String = { it },
    /** Runs a tool off the caller's thread (Dispatchers.IO in the app). */
    private val runTool: suspend (() -> ToolResult) -> ToolResult = { it() },
) {
    private val steps = ArrayList<AgentStep>()

    suspend fun run(base: List<PromptMessage>): AgentOutcome {
        val msgs = base.toMutableList()
        var fixed = base.size
        var calls = 0
        var retried = false
        var unknownRetried = false
        var echoRetried = false
        while (true) {
            currentCoroutineContext().ensureActive()
            fixed = fit(msgs, fixed)
            val tLlm0 = System.currentTimeMillis()
            val turn = model.complete(msgs.toList()) { callbacks.onText(ToolCallParser.visibleText(it)) }
            android.util.Log.i(AGENT_TAG, "step $calls: llm=${System.currentTimeMillis() - tLlm0}ms msgs=${msgs.size} prompt~${msgs.sumOf { it.content.length / 4 }}tok raw=\"${turn.text.take(500).replace('\n', ' ')}\"")
            if (turn.error != null) return AgentOutcome(turn.text, steps.toList(), AgentStop.ERROR, turn.error)
            val answer = answerOf(turn.text)
            when (val p = ToolCallParser.parse(answer)) {
                is ParsedReply.Final -> {
                    // 1.13.0: the model wrote a fake tool log (copied from history) instead of calling.
                    if (com.iris.localai.context.ToolRollup.hasEcho(answer) && !echoRetried) {
                        echoRetried = true
                        android.util.Log.w(AGENT_TAG, "fake tool log in answer (silent retry)")
                        msgs += PromptMessage(PromptMessage.ASSISTANT, answer.trim())
                        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.FAKE_LOG_HINT)
                        continue
                    }
                    val clean = com.iris.localai.context.ToolRollup.stripEcho(answer.trim())
                    return AgentOutcome(ToolCallParser.stripCalls(com.iris.localai.context.ToolRollup.stripEcho(turn.text)), steps.toList(), AgentStop.DONE,
                        transcript = msgs + PromptMessage(PromptMessage.ASSISTANT, clean))
                }
                is ParsedReply.Malformed -> {
                    if (retried) {
                        // Second unreadable call in a row: stop and show what the model wrote.
                        return AgentOutcome(answer, steps.toList(), AgentStop.ERROR, "模型的工具调用格式两次都无法识别：${p.error}")
                    }
                    retried = true
                    // 1.10.8: the first unreadable call is retried silently (no red card), like an unknown tool.
                    android.util.Log.w(AGENT_TAG, "malformed call (silent retry): ${p.error}")
                    msgs += PromptMessage(PromptMessage.ASSISTANT, answer.trim())
                    msgs += PromptMessage(PromptMessage.USER, AgentPrompt.retryHint(p.error))
                }
                is ParsedReply.Call -> {
                    retried = false
                    if (calls >= maxSteps) return finishAtLimit(msgs, fixed, p)
                    calls++
                    var call = p.call
                    // 1.10.4: a misnamed call (ls/cat/…) is fixed to a real tool silently.
                    val avail = tools.map { it.name }.toSet()
                    if (call.name !in avail) ToolCallParser.canonicalizeName(call.name, avail)?.let { fixed0 ->
                        android.util.Log.i(AGENT_TAG, "alias: ${call.name} -> $fixed0")
                        call = call.copy(name = fixed0)
                    }
                    // 1.13.3: a skill called like a tool (daily-news{…}) → skill(name=daily-news).
                    if (call.name !in avail && SkillTool.name in avail && env.skills?.find(call.name) != null) {
                        android.util.Log.i(AGENT_TAG, "skill as tool: ${call.name} -> skill")
                        call = call.copy(name = SkillTool.name, args = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(call.name)) })
                    }
                    val tool = tools.firstOrNull { it.name == call.name }
                    var step = AgentStep(tool = call.name, args = Args.summary(call.args), lead = ToolCallParser.visibleText(turn.text).trim(),
                        title = StepTitle.of(call.name, call.args))
                    val stepStart = System.currentTimeMillis()
                    if (tool == null && !unknownRetried) {
                        // 1.10.4: first unknown tool — silent retry with a hint, no red card.
                        unknownRetried = true
                        android.util.Log.w(AGENT_TAG, "unknown tool \"${call.name}\" (silent retry); raw=${call.args.toString().take(200)}")
                        msgs += PromptMessage(PromptMessage.ASSISTANT, rawCall(answer, call))
                        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.unknownToolHint(call.name, avail))
                        continue
                    }
                    publish(step)
                    val result: ToolResult
                    val status: StepStatus
                    if (tool == null) {
                        // Still unknown after the retry: a gray note, not a red error card.
                        result = ToolResult(false, "Error: unknown tool \"${call.name}\". Available: ${avail.joinToString()}", "未知工具（已忽略）")
                        status = StepStatus.DENIED
                    } else {
                        val perm = tool.permissionFor(call.args, permissionOf(tool), env)
                        var decision: UserDecision? = null
                        if (perm == Permission.ASK) {
                            step = step.copy(status = StepStatus.ASKING); update(step)
                            decision = callbacks.confirm(tool, call)
                            if (decision == UserDecision.ALWAYS_ALLOW) callbacks.onAlwaysAllowCall(tool, call)
                            step = step.copy(status = StepStatus.RUNNING); update(step)
                        }
                        if (!PermissionPolicy.mayRun(perm, decision)) {
                            result = ToolResult(false, if (perm == Permission.DENY) "Error: this tool is denied in the user's settings." else "Error: the user declined this action.",
                                if (perm == Permission.DENY) "设置中已拒绝" else "用户已拒绝")
                            status = StepStatus.DENIED
                        } else {
                            result = try {
                                if (tool is SuspendTool) {
                                    // 1.12.0: long-running tool (process_file) — progress goes into its card.
                                    val running = step
                                    tool.runSuspend(call.args, env) { p -> update(running.copy(result = p)) }
                                } else runTool { tool.run(call.args, env) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // 1.12.0: a wrong-argument error carries the tool's full signature.
                                val usage = if (t is IllegalArgumentException) "\nUsage: ${tool.promptLine}" else ""
                                ToolResult(false, "Error: ${t.message ?: t.javaClass.simpleName}$usage", (t.message ?: "执行失败").take(40))
                            }
                            status = if (result.ok) StepStatus.OK else StepStatus.ERROR
                            android.util.Log.i(AGENT_TAG, "step $calls: tool=${call.name} ok=${result.ok} ${System.currentTimeMillis() - stepStart}ms")
                            if (result.ok && tool.risk in CHANGES) callbacks.onWorkspaceChanged()
                            if (result.ok && tool.risk == ToolRisk.MEMORY) callbacks.onMemoryChanged()
                        }
                    }
                    update(step.copy(result = result.summary, status = status, detail = result.output, plan = result.plan))
                    // 1.10.6: the model's own text (cut at the end of its call), not a rewritten
                    // canonical block: it matches what the engine decoded, so the KV cache is kept.
                    msgs += PromptMessage(PromptMessage.ASSISTANT, rawCall(answer, call))
                    msgs += PromptMessage(PromptMessage.USER, AgentPrompt.toolResponse(call.name, result))
                }
            }
        }
    }

    /** Step limit hit: one last turn without tools for a summary (a stray call in it is dropped). */
    private suspend fun finishAtLimit(msgs: MutableList<PromptMessage>, fixed: Int, p: ParsedReply.Call): AgentOutcome {
        msgs += PromptMessage(PromptMessage.ASSISTANT, p.call.canonical())
        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.STEP_LIMIT)
        fit(msgs, fixed)
        val turn = model.complete(msgs.toList()) { callbacks.onText(ToolCallParser.visibleText(it)) }
        val text = when (val r = ToolCallParser.parse(answerOf(turn.text))) {
            is ParsedReply.Final -> turn.text
            is ParsedReply.Call -> r.before
            is ParsedReply.Malformed -> r.before
        }
        return AgentOutcome(text, steps.toList(), AgentStop.MAX_STEPS, turn.error,
            transcript = msgs + PromptMessage(PromptMessage.ASSISTANT, answerOf(text).trim()))
    }

    /** The model's reply as written (already cut after its call); the canonical block if it is empty. */
    private fun rawCall(answer: String, call: ToolCall): String = answer.trim().ifEmpty { call.canonical() }

    private fun publish(s: AgentStep) {
        steps += s; callbacks.onSteps(steps.toList())
        // 1.13.3: the turn's text now lives on the step (above its card); the live text starts over.
        callbacks.onText("")
    }

    private fun update(s: AgentStep) {
        val i = steps.indexOfFirst { it.id == s.id }
        if (i >= 0) steps[i] = s else steps += s
        callbacks.onSteps(steps.toList())
    }

    /**
     * Keeps the prompt under [contextBudget]. 1.13.0 order (the old one dropped whole earlier turns
     * while their big tool outputs survived, so the model "forgot" what was just asked):
     *  1. tool outputs of earlier turns are shortened to their head ([EARLIER_KEEP] chars);
     *  2. the oldest tool outputs of this run are replaced by a stub;
     *  3. tool outputs of earlier turns are replaced by a stub;
     *  4. the oldest earlier rounds are dropped whole (a round = a user request and everything up to
     *     the next one), so the history never starts with an assistant turn or a tool response.
     * System prompt and the current request always stay.
     */
    private fun fit(msgs: MutableList<PromptMessage>, fixedIn: Int): Int {
        var fixed = fixedIn
        fun total() = msgs.sumOf { TokenEstimator.message(it.content) }
        if (total() <= contextBudget) return fixed
        val start = if (msgs.firstOrNull()?.role == PromptMessage.SYSTEM) 1 else 0
        val before = msgs.size
        fun isToolOut(m: PromptMessage) = m.role == PromptMessage.USER && m.content.startsWith("<tool_response>") && m.content != AgentPrompt.ELIDED
        // 1. shorten earlier turns' tool outputs
        for (i in start until (fixed - 1).coerceAtLeast(start)) {
            if (total() <= contextBudget) return fixed
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = shortenToolOutput(m.content, EARLIER_KEEP))
        }
        // 2. this run's oldest outputs
        for (i in fixed until msgs.size - 1) {
            if (total() <= contextBudget) return fixed
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = AgentPrompt.ELIDED)
        }
        // 3. earlier turns' outputs → stub
        for (i in start until (fixed - 1).coerceAtLeast(start)) {
            if (total() <= contextBudget) return fixed
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = AgentPrompt.ELIDED)
        }
        // 4. drop whole rounds, oldest first
        while (total() > contextBudget && fixed - start > 1) {
            msgs.removeAt(start); fixed--
            while (fixed - start > 1 && !isRequest(msgs[start])) { msgs.removeAt(start); fixed-- }
        }
        if (msgs.size != before) android.util.Log.i(AGENT_TAG, "fit dropped ${before - msgs.size} msgs")
        return fixed
    }

    private fun isRequest(m: PromptMessage) = m.role == PromptMessage.USER && !m.content.startsWith("<tool_response>")

    companion object {
        const val FORMAT_ERROR = "格式错误"
        /** 1.10.4: step timing + raw-output log tag (adb logcat -s IrisAgent). */
        const val AGENT_TAG = "IrisAgent"
        /** Characters of an earlier turn's tool output kept before it is stubbed out. */
        const val EARLIER_KEEP = 600

        /** Head of a `<tool_response>` block, closed again, with a marker that text was cut. */
        fun shortenToolOutput(block: String, keep: Int): String {
            val body = block.removePrefix("<tool_response>").removeSuffix("</tool_response>").trim()
            if (body.length <= keep) return block
            return "<tool_response>\n" + body.take(keep).trimEnd() + "\n…(shortened to save context)\n</tool_response>"
        }
        private val CHANGES = setOf(ToolRisk.WRITE, ToolRisk.DELETE)
    }
}
