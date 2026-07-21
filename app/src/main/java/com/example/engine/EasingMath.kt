package com.example.engine

import kotlin.math.*

/**
 * Stateless easing functions. All accept a normalised progress [t] in [0, 1]
 * and return a mapped value also in approximately [0, 1].
 *
 * The spring analytical path is used during scrubbing/seeking (no velocity
 * history needed). Real-time playback uses Euler sub-steps in [PlaybackEngine].
 */
object EasingMath {

    fun ease(type: String, t: Float): Float = when (type) {
        "linear"       -> t
        "ease_in"      -> t * t * t
        "ease_out"     -> { val inv = 1f - t; 1f - inv * inv * inv }
        "ease_in_out"  -> easeInOut(t)
        "bounce"       -> bounce(t)
        "elastic_out"  -> elasticOut(t)
        "spring"       -> springAnalytical(t)
        "back"         -> backOut(t)
        else           -> t
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) 4f * t * t * t
        else {
            val inv = -2f * t + 2f
            1f - inv * inv * inv / 2f
        }
    }

    private fun bounce(t: Float): Float {
        val n = 7.5625f
        var x = t
        return when {
            x < 1f / 2.75f -> n * x * x
            x < 2f / 2.75f -> { x -= 1.5f / 2.75f; n * x * x + 0.75f }
            x < 2.5f / 2.75f -> { x -= 2.25f / 2.75f; n * x * x + 0.9375f }
            else -> { x -= 2.625f / 2.75f; n * x * x + 0.984375f }
        }
    }

    /**
     * Standard "back" overshoot ease — rises past 1.0 then settles back,
     * used by [OverlayResolver]'s "pop" enter style so a scale-in overshoots
     * slightly before landing, instead of just growing monotonically.
     * c1 = 1.70158 is the conventional constant for this curve (produces a
     * ~10% overshoot), same value used across easing-function libraries.
     */
    private fun backOut(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun elasticOut(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = (2.0 * PI) / 3.0
        return (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0).toFloat()
    }

    /**
     * Analytical damped spring — stable at any [t], suitable for seeking.
     * Models a critically-damped-ish spring snapping to the target.
     *
     * Uses the closed-form:  x(t) = 1 − e^(−ζωt) · [cos(ωd·t) + (ζ/√(1−ζ²))·sin(ωd·t)]
     * with ζ = 0.65 (underdamped, one visible overshoot).
     */
    fun springAnalytical(t: Float, stiffness: Float = 280f, damping: Float = 28f): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        // Normalise t so that the spring mostly settles within [0,1]
        val tScaled = t * 2.2f
        val omegaN = sqrt(stiffness.toDouble())
        val zeta   = damping / (2.0 * omegaN)
        return if (zeta < 1.0) {
            val omegaD = omegaN * sqrt(1.0 - zeta * zeta)
            val envelope = exp(-zeta * omegaN * tScaled)
            val osc = cos(omegaD * tScaled) + (zeta / sqrt(1.0 - zeta * zeta)) * sin(omegaD * tScaled)
            (1.0 - envelope * osc).toFloat().coerceIn(0f, 1.5f)   // allow slight overshoot
        } else {
            // Overdamped fallback
            (1.0 - exp(-omegaN * tScaled)).toFloat()
        }
    }

    /**
     * Single Euler step for a spring-mass-damper system.
     *
     * Writes [newPos] to [out][0] and [newVel] to [out][1] instead of
     * returning a Pair — eliminates ~2,400 boxed Pair allocations/second
     * that previously occurred in the 60fps animation loop (4 sub-steps ×
     * 10 bones × 60fps).
     *
     * Callers must pre-allocate the [out] array (FloatArray(2)) and reuse it.
     */
    fun springEulerStep(
        pos: Float, vel: Float,
        target: Float,
        stiffness: Float, damping: Float,
        dt: Float,
        out: FloatArray   // out[0] = newPos, out[1] = newVel
    ) {
        val force  = stiffness * (target - pos) - damping * vel
        val newVel = vel + force * dt
        out[0] = pos + newVel * dt
        out[1] = newVel
    }
}
