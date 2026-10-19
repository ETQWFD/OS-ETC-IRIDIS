package com.iris.localai.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Microphone as a cold Flow of VAD-sized float frames (16 kHz mono).
 * Uses the VOICE_COMMUNICATION source so the platform echo canceller removes our own TTS
 * playback — that is what makes barge-in (talking over the assistant) work on a speakerphone.
 * The recorder only exists while the flow is collected.
 */
object Microphone {
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun frames(
        sampleRate: Int = ASR_SAMPLE_RATE,
        source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        effects: Boolean = true,
    ): Flow<FloatArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val record = try {
            AudioRecord(
                source,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, VAD_WINDOW * 4 * 8),
            )
        } catch (t: Throwable) { // SecurityException when the permission was revoked meanwhile
            close(IllegalStateException("无法打开麦克风：${t.message}"))
            return@callbackFlow
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            close(IllegalStateException("无法打开麦克风"))
            return@callbackFlow
        }
        val aec = if (effects && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (effects && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        try { record.startRecording() } catch (t: Throwable) {
            aec?.release(); ns?.release(); record.release()
            close(IllegalStateException("麦克风被占用：${t.message}"))
            return@callbackFlow
        }
        // The reader thread owns the recorder: it is released only after the last read returns,
        // never underneath a blocking read (which crashes inside libaudioclient).
        val job = launch(Dispatchers.IO) {
          try {
            while (isActive) {
                val buf = FloatArray(VAD_WINDOW)
                var off = 0
                while (off < VAD_WINDOW && isActive) {
                    val n = record.read(buf, off, VAD_WINDOW - off, AudioRecord.READ_BLOCKING)
                    if (n < 0) { close(IllegalStateException("录音中断 ($n)")); return@launch }
                    off += n
                }
                trySend(buf)
            }
          } finally {
            runCatching { record.stop() }
            aec?.release()
            ns?.release()
            record.release()
          }
        }
        awaitClose {
            runCatching { record.stop() } // unblocks a pending read
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    fun rms(frame: FloatArray): Float = rms(frame, 0, frame.size)

    /** RMS of frame[from, to) without copying the slice. */
    fun rms(frame: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (i in from until to) { val v = frame[i]; s += v * v }
        val r = sqrt(s / (to - from)).toFloat()
        // TTS models can emit NaN/Inf samples; never let that reach the UI (gradients reject it).
        return if (r.isFinite()) r.coerceIn(0f, 1f) else 0f
    }
}

/** Streaming PCM player for TTS output. One instance per voice session. */
class PcmPlayer(private val sampleRate: Int, voiceCommunication: Boolean = false) {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Media volume by default. Voice-communication usage (setting「更易打断」) lets the
                // platform echo canceller remove the reply from the mic, at the cost of call volume.
                .setUsage(if (voiceCommunication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                sampleRate * 4 / 2,
            ),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
        .build()

    @Volatile private var stopped = false
    @Volatile var level = 0f
        private set

    private val lock = Any()
    @Volatile private var released = false
    /** Frames handed to the track since the last [stop]/[reset]; used by [drain]. */
    @Volatile private var written = 0L
    /**
     * Playback head before the first frame of the current turn. Sampled lazily, right after
     * play() and before the first write: after pause()+flush() some devices keep counting the
     * head, others reset it to 0 only once play() runs. Sampling in [reset] (1.5.0) read the stale
     * pre-flush value on the latter, so after every barge-in [drain] waited for a position that
     * never came and the voice loop sat in SPEAKING (mic gated) for the whole reply length again.
     */
    @Volatile private var headBase = 0L
    @Volatile private var needBase = true

    /**
     * Blocking write; returns false once [stop] was called. Called from the TTS thread while the
     * session may be torn down on another, so every touch of the track happens under [lock].
     */
    fun write(samples: FloatArray): Boolean {
        if (stopped) return false
        var off = 0
        while (off < samples.size && !stopped) {
            val n = synchronized(lock) {
                if (released) return false
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                if (needBase) {
                    headBase = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    needBase = false
                }
                track.write(samples, off, minOf(2048, samples.size - off), AudioTrack.WRITE_BLOCKING)
            }
            if (n <= 0) return false
            written += n
            level = Microphone.rms(samples, off, off + n)
            off += n
        }
        return !stopped
    }

    /**
     * Suspends until everything written so far has actually left the speaker. The last write
     * returns while up to ~0.5 s is still queued in the track: releasing it then cut off the end
     * of every sentence, and the voice loop started listening while its own voice still played.
     */
    suspend fun drain() {
        if (written <= 0 || needBase) return
        val target = headBase + written
        val start = System.currentTimeMillis()
        val deadline = start + written * 1000 / sampleRate + 1500
        var lastHead = -1L
        var lastMove = start
        while (!stopped && !released && System.currentTimeMillis() < deadline) {
            val head = synchronized(lock) {
                if (released) return
                // A stream shorter than the buffer never reaches the start threshold on its own.
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track.play() }
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }
            if (head >= target) break
            val now = System.currentTimeMillis()
            if (head != lastHead) { lastHead = head; lastMove = now }
            // The head stopped moving (route change, device quirk): don't hold the caller hostage.
            else if (now - lastMove > STALL_MS) break
            kotlinx.coroutines.delay(30)
        }
        level = 0f
    }

    /** Audio written but not yet played, in ms (0 when idle or before the first write of a turn). */
    fun queuedMs(): Long {
        if (written <= 0 || needBase || stopped) return 0L
        val head = synchronized(lock) {
            if (released) return 0L
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        return ((headBase + written - head).coerceAtLeast(0L)) * 1000L / sampleRate
    }

    /** Immediately silence output (barge-in). */
    fun stop() {
        stopped = true
        level = 0f
        if (!released) runCatching { track.pause(); track.flush() }
    }

    fun reset() {
        written = 0L
        needBase = true
        stopped = false
    }

    private companion object {
        const val STALL_MS = 800L
    }

    fun release() {
        stop() // pause+flush first so a blocked write returns promptly
        synchronized(lock) {
            if (released) return
            released = true
            runCatching { track.release() }
        }
    }
}
