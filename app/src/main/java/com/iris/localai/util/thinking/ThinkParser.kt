package com.iris.localai.util

/**
 * Splits a reasoning model's output into its <think> part and the visible answer.
 *
 * Handles all shapes seen in the wild:
 *  - "<think>reasoning</think>answer"           (Qwen3, QwQ)
 *  - "reasoning</think>answer"                   (DeepSeek-R1 distills: template pre-fills "<think>")
 *  - "<think>reasoning…" (still streaming)       → thinking in progress
 *  - plain text                                  → answer only
 */
object ThinkParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    data class Parts(
        val thinking: String,
        val answer: String,
        /** True while the closing tag has not arrived yet. */
        val thinkingOpen: Boolean,
    ) {
        val hasThinking: Boolean get() = thinking.isNotBlank() || thinkingOpen
    }

    fun parse(raw: String, expectThinking: Boolean = false): Parts =
        split(raw, raw.indexOf(OPEN), raw.indexOf(CLOSE), expectThinking)

    /** [parse] with the first-tag positions already known (used by the incremental [ThinkParseCache]). */
    fun split(raw: String, openAt: Int, closeAt: Int, expectThinking: Boolean = false): Parts {
        return when {
            // "reasoning</think>answer" — checked first so an answer that merely *mentions*
            // "<think>" after the real close tag is not mistaken for a new reasoning block.
            closeAt >= 0 && (openAt < 0 || openAt > closeAt) -> Parts(
                thinking = raw.substring(0, closeAt).trim(),
                answer = raw.substring(closeAt + CLOSE.length).trim(),
                thinkingOpen = false,
            )
            openAt >= 0 && closeAt > openAt -> Parts(
                thinking = raw.substring(openAt + OPEN.length, closeAt).trim(),
                answer = (raw.substring(0, openAt) + raw.substring(closeAt + CLOSE.length)).trim(),
                thinkingOpen = false,
            )
            openAt >= 0 -> Parts(
                thinking = raw.substring(openAt + OPEN.length).trim(),
                answer = raw.substring(0, openAt).trim(),
                thinkingOpen = true,
            )
            expectThinking -> Parts(thinking = raw.trim(), answer = "", thinkingOpen = true)
            else -> Parts(thinking = "", answer = raw.trim(), thinkingOpen = false)
        }
    }

    /** What gets sent back to the model as history: never the reasoning (saves context). */
    fun stripForHistory(raw: String): String {
        val p = parse(raw)
        if (p.answer.isNotBlank() || p.thinking.isBlank()) return p.answer
        // 1.13.0: the reply never left its reasoning (hit the length cap). Keep its tail so the
        // round stays in history instead of vanishing (which left two user turns in a row).
        val tail = p.thinking.trim().let { if (it.length > THINK_TAIL) "…" + it.takeLast(THINK_TAIL) else it }
        return "（未完成，仅有思考）$tail"
    }

    const val THINK_TAIL = 400
}
