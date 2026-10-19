package com.iris.localai.ui.chat

import kotlin.math.PI
import kotlin.math.sin

/** Pure motion curve for the voice orb's waiting state (unit-tested). */
object OrbMotion {
    const val FLOAT_PERIOD_MS = 1800
    /** Vertical travel, as a fraction of the orb box (±4.5 % of 220 dp ≈ ±10 dp). */
    const val FLOAT_AMPLITUDE = 0.045f
    /** Extra breathing scale on top of the idle breath (±8 %, the old 3.5 % was invisible). */
    const val BREATH_SCALE = 0.08f

    data class Frame(val dy: Float, val scale: Float)

    /**
     * [phase] in 0..1 over one period, [amount] 0..1 (eased in/out). The disc rises while it
     * inhales: dy is negative (up) when the scale is largest, like something buoyant breathing.
     */
    fun at(phase: Float, amount: Float): Frame {
        if (amount <= 0f || !phase.isFinite()) return Frame(0f, 0f)
        val a = amount.coerceIn(0f, 1f)
        val s = sin(phase * 2f * PI.toFloat())
        return Frame(dy = -FLOAT_AMPLITUDE * s * a, scale = BREATH_SCALE * (0.5f + 0.5f * s) * a)
    }
}
