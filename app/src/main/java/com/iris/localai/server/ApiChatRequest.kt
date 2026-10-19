package com.iris.localai.server

import com.iris.localai.engine.PromptMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** One parsed /v1/chat/completions or /api/chat request (pure: no engine, unit-testable). */
internal data class ChatRequest(
    val model: String?,
    val messages: List<PromptMessage>,
    val stream: Boolean,
    val includeUsage: Boolean,
    val temperature: Float?,
    val topP: Float?,
    val topK: Int?,
    val minP: Float?,
    val repeatPenalty: Float?,
    val maxTokens: Int?,
    val thinking: Boolean?,
    /** 2.0.0: OpenAI `presence_penalty` / `frequency_penalty` / `seed` (and Ollama `options.*`). */
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val seed: Int? = null,
    /** 2.0.0: OpenAI `stop` / Ollama `options.stop` (string or array). Empty = none. */
    val stop: List<String> = emptyList(),
)

internal object ChatRequestParser {

    /** Structural JSON mismatches (an array where an object was expected, …) are client errors (400). */
    fun parse(body: String, ollama: Boolean, json: Json): ChatRequest =
        try {
            parseUnchecked(body, ollama, json)
        } catch (e: HttpError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "malformed request: ${e.message ?: "invalid JSON structure"}")
        }

    private fun parseUnchecked(body: String, ollama: Boolean, json: Json): ChatRequest {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { throw HttpError(400, "body must be a JSON object") }
        val msgs = root["messages"] as? JsonArray ?: throw HttpError(400, "'messages' is required")
        val messages = msgs.mapNotNull { el ->
            val o = el as? JsonObject ?: throw HttpError(400, "each message must be an object")
            val role = when (o.str("role")) {
                "system", "developer" -> PromptMessage.SYSTEM
                "user" -> PromptMessage.USER
                "assistant" -> PromptMessage.ASSISTANT
                else -> return@mapNotNull null // tool / function messages are not supported
            }
            PromptMessage(role, contentText(o["content"]))
        }
        if (messages.none { it.role == PromptMessage.USER }) throw HttpError(400, "at least one user message is required")
        // `null` objects (stream_options: null, options: null) are common in SDK output: treat as absent.
        val opts = if (ollama) root["options"] as? JsonObject else root
        fun f(k: String) = (opts?.get(k) as? JsonPrimitive)?.floatOrNull?.takeIf { !it.isNaN() }
        fun i(k: String) = (opts?.get(k) as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.floatOrNull?.toInt() }
        return ChatRequest(
            model = root.str("model"),
            messages = messages,
            stream = (root["stream"] as? JsonPrimitive)?.booleanOrNull ?: ollama, // Ollama streams by default
            includeUsage = ((root["stream_options"] as? JsonObject)?.get("include_usage") as? JsonPrimitive)?.booleanOrNull == true,
            temperature = f("temperature"),
            topP = f("top_p"),
            topK = i("top_k"),
            minP = f("min_p"),
            repeatPenalty = f("repeat_penalty") ?: f("repetition_penalty"),
            maxTokens = i("max_completion_tokens") ?: i("max_tokens") ?: i("num_predict"),
            thinking = ((root["think"] ?: root["enable_thinking"]) as? JsonPrimitive)?.booleanOrNull,
            presencePenalty = f("presence_penalty"),
            frequencyPenalty = f("frequency_penalty"),
            seed = i("seed"),
            stop = stopList(opts?.get("stop")),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** OpenAI content may be a string or an array of typed parts; only text is supported. */
    fun contentText(el: JsonElement?): String = when (el) {
        null, JsonNull -> ""
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.joinToString("") { part ->
            val o = part as? JsonObject ?: throw HttpError(400, "content parts must be objects")
            when (o.str("type")) {
                "text", "input_text" -> o.str("text").orEmpty()
                else -> throw HttpError(400, "only text content parts are supported")
            }
        }
        else -> throw HttpError(400, "unsupported content")
    }

    /** `"stop": "x"` or `["x","y"]` (null / missing = none). Empty strings are dropped. */
    fun stopList(el: JsonElement?): List<String> = when (el) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> if (el.isString) listOfNotNull(el.content.takeIf { it.isNotEmpty() })
            else throw HttpError(400, "'stop' must be a string or an array of strings")
        is JsonArray -> el.map { s ->
            (s as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw HttpError(400, "'stop' must be a string or an array of strings")
        }.filter { it.isNotEmpty() }.also {
            if (it.size > MAX_STOP) throw HttpError(400, "at most $MAX_STOP stop sequences are supported")
        }
        else -> throw HttpError(400, "'stop' must be a string or an array of strings")
    }

    private const val MAX_STOP = 16
}

/**
 * 2.0.0: server-side stop sequences. Text is pushed as it streams; what [push] returns is safe to
 * send (a possible start of a stop sequence is held back until it is resolved). After a match
 * [hit] is true and nothing more is returned; the stop sequence itself is never emitted.
 */
class StopSequenceFilter(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }
    private val held = StringBuilder()

    var hit = false
        private set

    val active: Boolean get() = stops.isNotEmpty()

    fun push(text: String): String {
        if (hit) return ""
        if (stops.isEmpty()) return text
        held.append(text)
        var at = -1
        for (s in stops) {
            val i = held.indexOf(s)
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at >= 0) {
            hit = true
            val out = held.substring(0, at)
            held.setLength(0)
            return out
        }
        // Hold back the longest tail that could still grow into a stop sequence.
        var keep = 0
        for (s in stops) {
            var k = minOf(s.length - 1, held.length)
            while (k > keep) {
                if (held.endsWith(s.substring(0, k))) { keep = k; break }
                k--
            }
        }
        val out = held.substring(0, held.length - keep)
        held.delete(0, held.length - keep)
        return out
    }

    /** End of generation: whatever was held back was not a stop sequence after all. */
    fun flush(): String {
        if (hit) return ""
        val out = held.toString()
        held.setLength(0)
        return out
    }
}

/** Pure decisions of the API routes, kept apart from the engine so they can be unit-tested. */
object ApiPolicy {
    /**
     * 2.0.0: an API request for a *different* installed model while the engine is generating
     * (in-app chat, voice, another API client) must not swap the model: [com.iris.localai.engine.EngineManager.load]
     * cancels whatever runs, which used to cut the user's chat reply off mid-sentence.
     */
    fun modelSwitchBlocked(engineBusy: Boolean, requestedFile: String?, readyFile: String?): Boolean =
        engineBusy && requestedFile != null && requestedFile != readyFile
}
