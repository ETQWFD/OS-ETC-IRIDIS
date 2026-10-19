package com.iris.localai.voice

/**
 * Decides when the user is talking over the assistant (pure logic, unit-tested).
 *
 * The reply plays on the media stream through the loudspeaker; on Xiaomi phones the echo
 * canceller barely touches media audio, so the microphone hears the reply itself. The 1.6.0 rule
 * (VAD for 16 frames ≈ 0.5 s and mic ≥ max(0.02, 0.6 × output peak)) compared two levels on
 * different scales, and normal speech practically never passed it.
 *
 * Instead, while the assistant speaks, we learn the echo coupling of this room / phone / volume:
 * the ratio mic ÷ output, as a slowly moving baseline (a quantile tracker, see [onFrame]). The echo we expect
 * on the next frame is baseline × output; the user is talking when the mic is clearly above that
 * prediction (× [Params.margin]) and above an absolute floor, and the VAD says the frame is
 * speech, for about [Params.frames] × 32 ms. A single quiet frame does not reset the count.
 */
class BargeInDetector(sensitivity: Int = MEDIUM) {
    data class Params(val margin: Float, val floor: Float, val frames: Int, val vadProb: Float)

    val params: Params = paramsFor(sensitivity)

    /** Learned mic/out ratio. Starts low so barge-in works before anything was learned. */
    var coupling: Float = INITIAL_COUPLING
        private set
    var learnedFrames: Int = 0
        private set
    private var count = 0

    /** Echo the mic should pick up for this output level. */
    fun predictedEcho(out: Float): Float = coupling * out

    /**
     * One 32 ms frame while the assistant is replying. [mic] and [out] are RMS levels (0..1),
     * [out] being a short decaying peak of what is playing; [speechProb] the VAD probability.
     * Returns true on the frame the user should take over.
     */
    fun onFrame(mic: Float, out: Float, speechProb: Float): Boolean {
        if (!mic.isFinite() || !out.isFinite()) return false
        val speech = speechProb >= params.vadProb
        val threshold = maxOf(params.floor, predictedEcho(out) * params.margin)
        val loud = mic >= threshold
        // Learn from every frame where our output plays, except the ones that look like the user
        // taking over. Echo of our own reply is speech too (the VAD fires on it), so the VAD cannot
        // tell echo frames apart; a quantile tracker can: it settles at the ~70th percentile of
        // the mic/out ratio, which steady echo dominates, while short bursts of the user's voice
        // barely move it.
        if (out > LEARN_MIN_OUT) {
            val ratio = (mic / out).coerceIn(MIN_COUPLING, MAX_COUPLING)
            val warm = learnedFrames < WARMUP_FRAMES
            // Frames that look like the user taking over move the baseline 4× slower.
            val damp = if (speech && loud && !warm) 0.25f else 1f
            val up = (if (warm) WARM_UP_STEP else UP_STEP) * damp
            val down = (if (warm) WARM_DOWN_STEP else DOWN_STEP) * damp
            coupling = (coupling * if (ratio > coupling) 1f + up else 1f - down).coerceIn(MIN_COUPLING, MAX_COUPLING)
            learnedFrames++
            // Until the echo of this session has been measured, our own voice must not count as the user's.
            if (warm) { count = 0; return false }
        }
        count = if (speech && loud) count + 1 else (count - 1).coerceAtLeast(0)
        return if (count >= params.frames) { count = 0; true } else false
    }

    fun reset() { count = 0 }

    companion object {
        const val LOW = 0
        const val MEDIUM = 1
        const val HIGH = 2
        const val INITIAL_COUPLING = 0.6f
        /** Quantile tracking steps: equilibrium at UP/(UP+DOWN) ≈ 70th percentile. */
        const val UP_STEP = 0.035f
        const val DOWN_STEP = 0.015f
        /** First ≈0.8 s of our own audio per voice session: learn fast, never fire. */
        const val WARMUP_FRAMES = 25
        const val WARM_UP_STEP = 0.15f
        const val WARM_DOWN_STEP = 0.065f
        const val MIN_COUPLING = 0.02f
        const val MAX_COUPLING = 4f
        const val LEARN_MIN_OUT = 0.004f

        fun paramsFor(sensitivity: Int): Params = when (sensitivity) {
            LOW -> Params(margin = 3.0f, floor = 0.030f, frames = 10, vadProb = 0.70f)  // ≈ 320 ms
            HIGH -> Params(margin = 1.6f, floor = 0.012f, frames = 7, vadProb = 0.50f)  // ≈ 220 ms
            else -> Params(margin = 2.0f, floor = 0.018f, frames = 8, vadProb = 0.60f)  // ≈ 260 ms
        }
    }
}
