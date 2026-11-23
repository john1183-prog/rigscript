package com.example.engine.acting

/**
 * One column of the brief's initial-parameter table (Part C, Phase 2). These are starting points to be tuned by
 * plotting, not truth. Nothing reads them until WP2.2 (motion model) and WP2.3 (expression blending); WP2.1 only
 * creates the single place they live so the tuning guide (WP2.7) has one file to point at.
 */
class ActingLevel(
    /** Lead/follow delay per chain level, seconds. */
    val leadFollowDelaySec: Float,
    /** Duration stretch per chain level, as a fraction of the move's duration. */
    val durationStretchPerLevel: Float,
    /** Anticipation: fraction of the move size, and the cap in degrees. */
    val anticipationFraction: Float,
    val anticipationCapDeg: Float,
    /** Overshoot: fraction of the move size, and the cap in degrees. */
    val overshootFraction: Float,
    val overshootCapDeg: Float,
    /** Expression blend time, seconds. */
    val expressionBlendSec: Float
)

/** All acting constants, in one place (brief A8 "Tunables in one place"). */
object ActingParams {

    /** OFF: every Craft amount is zero (the layer does not run at all; see [ActingLayer]). */
    val OFF_LEVEL = ActingLevel(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val SUBTLE_LEVEL = ActingLevel(0.012f, 0.03f, 0.03f, 4f, 0.025f, 3f, 0.12f)
    val NORMAL_LEVEL = ActingLevel(0.025f, 0.06f, 0.06f, 6f, 0.05f, 5f, 0.18f)
    val BOLD_LEVEL = ActingLevel(0.040f, 0.09f, 0.09f, 9f, 0.08f, 8f, 0.24f)

    fun levelFor(intensity: ActingIntensity): ActingLevel = when (intensity) {
        ActingIntensity.OFF -> OFF_LEVEL
        ActingIntensity.SUBTLE -> SUBTLE_LEVEL
        ActingIntensity.NORMAL -> NORMAL_LEVEL
        ActingIntensity.BOLD -> BOLD_LEVEL
    }

    /** Anticipation only for moves larger than about this many degrees lasting at least [ANTICIPATION_MIN_DURATION_SEC]. */
    const val ANTICIPATION_MIN_MOVE_DEG: Float = 12f
    const val ANTICIPATION_MIN_DURATION_SEC: Float = 0.25f

    /** Overshoot is a damped sinusoid (about omega 14 rad/s, zeta 0.55) with an exact landing after the settle time. */
    const val OVERSHOOT_OMEGA: Float = 14f
    const val OVERSHOOT_ZETA: Float = 0.55f
}
