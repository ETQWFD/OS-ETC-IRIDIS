package com.iris.localai.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Hold to talk": records while the button is held, then transcribes the whole clip.
 * Silence is cut by the VAD first, so long pauses cost nothing and never confuse the model.
 */
class Dictation(private val scope: CoroutineScope, private val recognizer: SpeechRecognizer) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var job: Job? = null
    private val frames = ArrayList<FloatArray>()

    /** Language the recogniser is being warmed for while the user is still talking. */
    var warmLanguage: String = "auto"

    fun start() {
        if (job != null) return
        scope.launch { runCatching { recognizer.prepare(warmLanguage) } }
        synchronized(frames) { frames.clear() }
        job = scope.launch {
            try {
                Microphone.frames().collect { f ->
                    _level.value = Microphone.rms(f)
                    synchronized(frames) {
                        if (frames.size < MAX_FRAMES) frames.add(f)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("Dictation", "microphone failed", t)
                _level.value = 0f
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _level.value = 0f
        synchronized(frames) { frames.clear() }
    }

    /** Stops recording and returns the recognised text ("" if nothing was said). */
    suspend fun finish(language: String): String {
        job?.cancel()
        job?.join()
        job = null
        _level.value = 0f
        val clip = synchronized(frames) {
            val all = FloatArray(frames.sumOf { it.size })
            var o = 0
            for (f in frames) { f.copyInto(all, o); o += f.size }
            frames.clear()
            all
        }
        if (clip.size < ASR_SAMPLE_RATE / 4) return ""
        recognizer.prepare(language)
        // Up to 90 s of audio through the VAD is a few thousand ONNX calls: never on the caller's
        // (usually main) thread.
        val segments = withContext(Dispatchers.Default) {
            val vad = recognizer.newVad(minSilenceSec = 0.4f)
            val found = ArrayList<FloatArray>()
            try {
                val window = FloatArray(VAD_WINDOW)
                var i = 0
                while (i + VAD_WINDOW <= clip.size) {
                    clip.copyInto(window, 0, i, i + VAD_WINDOW)
                    vad.acceptWaveform(window)
                    i += VAD_WINDOW
                    while (!vad.empty()) { found.add(vad.front().samples); vad.pop() }
                }
                vad.flush()
                while (!vad.empty()) { found.add(vad.front().samples); vad.pop() }
            } finally {
                vad.release()
            }
            found
        }
        if (segments.isEmpty()) return ""
        return segments.map { recognizer.transcribe(it) }.filter { it.isNotBlank() }.joinToString("")
    }

    private companion object {
        /** 90 s at 16 kHz / 512-sample frames. */
        const val MAX_FRAMES = 90 * ASR_SAMPLE_RATE / VAD_WINDOW
    }
}
