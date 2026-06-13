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
     * One Euler sub-step for real-time spring integration.
     * Call 4× per tick with [dt] = frameDt / 4 for numerical stability.
     *
     * Mutates [pos] and [vel] in-place (passed as index into caller's arrays).
     * Returns updated (position, velocity) as a Pair to keep callers allocation-free.
     */
    fun springEulerStep(
        pos: Float, vel: Float,
        target: Float,
        stiffness: Float, damping: Float,
        dt: Float
    ): Pair<Float, Float> {
        val force = stiffness * (target - pos) - damping * vel
        val newVel = vel + force * dt
        val newPos = pos + newVel * dt
        return Pair(newPos, newVel)
    }
}
