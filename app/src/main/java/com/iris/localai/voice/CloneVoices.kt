package com.iris.localai.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** A voice cloned with ZipVoice: the user's reference clip plus what is said in it. */
@Serializable
data class ClonedVoice(
    val id: String,
    val name: String,
    /** Transcript of the reference clip (auto from ASR, editable). ZipVoice needs it to align the voice. */
    val text: String,
    val sampleRate: Int,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Reference clips, app-private: filesDir/voice-clones/<id>/{voice.json, ref.f32}. */
class CloneVoiceStore(context: Context) {
    private val root = File(context.filesDir, "voice-clones").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _voices = MutableStateFlow(readAll())
    val voices: StateFlow<List<ClonedVoice>> = _voices.asStateFlow()

    fun get(id: String): ClonedVoice? = _voices.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(name: String, text: String, samples: FloatArray, sampleRate: Int): ClonedVoice {
        val v = ClonedVoice(
            id = UUID.randomUUID().toString().take(12), name = name.trim().ifEmpty { "我的音色" }, text = text.trim(),
            sampleRate = sampleRate, durationMs = samples.size * 1000L / sampleRate,
        )
        val dir = File(root, v.id).apply { mkdirs() }
        writeFloats(File(dir, AUDIO), samples)
        File(dir, META).writeText(json.encodeToString(ClonedVoice.serializer(), v))
        _voices.value = readAll()
        return v
    }

    @Synchronized
    fun update(id: String, name: String, text: String) {
        val v = get(id) ?: return
        val n = v.copy(name = name.trim().ifEmpty { v.name }, text = text.trim())
        File(File(root, id), META).writeText(json.encodeToString(ClonedVoice.serializer(), n))
        _voices.value = readAll()
    }

    @Synchronized
    fun delete(id: String) {
        File(root, id).deleteRecursively()
        _voices.value = readAll()
    }

    fun audio(v: ClonedVoice): FloatArray = readFloats(File(File(root, v.id), AUDIO))

    private fun readAll(): List<ClonedVoice> = (root.listFiles() ?: emptyArray())
        .mapNotNull { d ->
            val meta = File(d, META)
            if (!meta.exists() || !File(d, AUDIO).exists()) null
            else runCatching { json.decodeFromString(ClonedVoice.serializer(), meta.readText()) }.getOrNull()
        }
        .sortedBy { it.createdAt }

    private companion object {
        const val META = "voice.json"
        const val AUDIO = "ref.f32"

        fun writeFloats(f: File, a: FloatArray) {
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(a)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bb.array())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }

        fun readFloats(f: File): FloatArray {
            val bytes = DataInputStream(f.inputStream()).use { it.readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}

/** Pure helpers for reference clips (unit-tested). */
object CloneAudio {
    const val MIN_SEC = 3f
    const val MAX_SEC = 15f
    const val RECORD_RATE = 24_000

    /** Linear-interpolation resampler; good enough for ASR input and a TTS reference. */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = ((input.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - f) + input[i1] * f
        }
        return out
    }

    /**
     * Trims leading/trailing silence (frames under [threshold] RMS), caps at [MAX_SEC] and
     * normalises the peak to −1 dBFS, so quiet recordings still give ZipVoice a usable prompt.
     */
    fun prepare(samples: FloatArray, rate: Int, threshold: Float = 0.01f): FloatArray {
        if (samples.isEmpty()) return samples
        val win = (rate / 50).coerceAtLeast(1) // 20 ms
        fun rms(from: Int): Float {
            val to = minOf(samples.size, from + win)
            var s = 0.0
            for (i in from until to) s += samples[i] * samples[i]
            return kotlin.math.sqrt(s / (to - from).coerceAtLeast(1)).toFloat()
        }
        var start = 0
        while (start < samples.size && rms(start) < threshold) start += win
        var end = samples.size
        while (end - win > start && rms(end - win) < threshold) end -= win
        if (start >= end) return FloatArray(0)
        // keep 100 ms of context either side
        start = (start - rate / 10).coerceAtLeast(0)
        end = minOf(samples.size, end + rate / 10, start + (MAX_SEC * rate).toInt())
        val out = samples.copyOfRange(start, end)
        var peak = 0f
        for (v in out) if (v.isFinite()) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak > 1e-4f) {
            val g = 0.89f / peak
            for (i in out.indices) out[i] = if (out[i].isFinite()) out[i] * g else 0f
        }
        return out
    }

    fun seconds(samples: FloatArray, rate: Int): Float = samples.size.toFloat() / rate

    /** Why a clip cannot be used, or null when it can. */
    fun problem(samples: FloatArray, rate: Int): String? {
        val s = seconds(samples, rate)
        return when {
            samples.isEmpty() -> "没有检测到声音"
            s < MIN_SEC -> "有效人声只有 ${"%.1f".format(s)} 秒，至少需要 ${MIN_SEC.toInt()} 秒（建议 5–15 秒）"
            else -> null
        }
    }

    /** ZipVoice: steps for the 快 / 像 switch. */
    fun steps(fidelity: Int, fast: Int, fine: Int): Int = if (fidelity == 1) fine.coerceIn(6, 32) else fast.coerceIn(2, 8)
}
