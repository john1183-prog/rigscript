package com.example.engine.acting

/**
 * One column of the brief's initial-parameter table (Part C, Phase 2). These are starting points to be tuned by
 * plotting, not truth. WP2.2 (motion model) reads everything except the expression blend time, which WP2.3 will read; WP2.1 only
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
    val expressionBlendSec: Float,
    /** Anticipation wind-back: it lasts this long and ends exactly when the joint starts to move (WP2.2). */
    val anticipationLeadSec: Float = 0f,
    /** Moving hold: the amplitude of the tiny seeded drift on a settled joint, degrees (the brief's ceiling is 0.3). */
    val holdDriftDeg: Float = 0f
)

/** All acting constants, in one place (brief A8 "Tunables in one place"). */
object ActingParams {

    /** OFF: every Craft amount is zero (the layer does not run at all; see [ActingLayer]). */
    val OFF_LEVEL = ActingLevel(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val SUBTLE_LEVEL = ActingLevel(0.012f, 0.03f, 0.03f, 4f, 0.025f, 3f, 0.12f, 0.07f, 0.15f)
    val NORMAL_LEVEL = ActingLevel(0.025f, 0.06f, 0.06f, 6f, 0.05f, 5f, 0.18f, 0.10f, 0.25f)
    val BOLD_LEVEL = ActingLevel(0.040f, 0.09f, 0.09f, 9f, 0.08f, 8f, 0.24f, 0.13f, 0.30f)

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

    // WP2.2 motion model (prototype: tools/acting_proto/acting_proto.py; the constants are the same).
    /** The settle kick starts this long BEFORE the eased shape arrives, so it rides the arrival instead of following a stop. */
    const val OVERSHOOT_LEAD_SEC: Double = 0.04
    /** The kick is ramped in over this long, so the angular velocity has no step. */
    const val OVERSHOOT_RAMP_SEC: Double = 0.05
    /** Every acting term is exactly zero this long after arrival (exact landing). */
    const val SETTLE_SEC: Double = 0.9
    /** On a retarget the velocity of the interrupted move carries on as v0*t*exp(-t/tau): time constant and cap (deg/s). */
    const val CARRY_TAU_SEC: Double = 0.07
    const val CARRY_MAX_DEG_PER_SEC: Double = 720.0
    /** The moving hold fades in over this long after a joint has settled. */
    const val HOLD_RAMP_SEC: Double = 0.6
    /** How many earlier events a retarget may depend on (older ones are treated as settled). */
    const val CHAIN_DEPTH: Int = 6
    /** Step of the finite difference that samples the velocity at a retarget. */
    const val VELOCITY_STEP_SEC: Double = 1.0 / 480.0
    /** The eased shape counts as arrived at this fraction; the grid it is searched on. */
    const val ARRIVAL_FRACTION: Float = 0.98f
    const val SHAPE_GRID: Int = 128
}
