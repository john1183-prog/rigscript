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
    val holdDriftDeg: Float = 0f,
    /** Face (WP2.3): how long before an expression change the squint begins, seconds. */
    val faceAnticipationSec: Float = 0f,
    /** Face (WP2.3): scales the anticipation squint depth and the take (eye-height overshoot). */
    val faceTakeScale: Float = 0f,
    /** Face (WP2.3): scales the idle gaze (the cap of [ActingParams.GAZE_CAP] is never exceeded). 0 = no gaze. */
    val gazeScale: Float = 0f,
    /** Speech (WP2.4): scales the acted mouth opening (clamped to 0..1 after scaling). 1 = the legacy class heights. */
    val mouthOpenScale: Float = 1f
)

/** All acting constants, in one place (brief A8 "Tunables in one place"). */
object ActingParams {

    /** OFF: every Craft amount is zero (the layer does not run at all; see [ActingLayer]). */
    val OFF_LEVEL = ActingLevel(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val SUBTLE_LEVEL = ActingLevel(0.012f, 0.03f, 0.03f, 4f, 0.025f, 3f, 0.12f, 0.07f, 0.15f, 0.040f, 0.5f, 0.6f, 0.85f)
    val NORMAL_LEVEL = ActingLevel(0.025f, 0.06f, 0.06f, 6f, 0.05f, 5f, 0.18f, 0.10f, 0.25f, 0.060f, 1.0f, 1.0f, 1.0f)
    val BOLD_LEVEL = ActingLevel(0.040f, 0.09f, 0.09f, 9f, 0.08f, 8f, 0.24f, 0.13f, 0.30f, 0.080f, 1.4f, 1.0f, 1.15f)

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

    // WP2.3 face model (prototype: tools/acting_proto/face_proto.py; the constants are the same).
    /** Anticipation squint depth (eye height multiplier dips by this much, times the level's take scale) by TARGET expression: NORMAL WIDE SQUINT WORRIED ANGRY HAPPY. */
    val FACE_ANT_DEPTH: DoubleArray = doubleArrayOf(0.0, 0.35, 0.0, 0.0, 0.25, 0.0)
    /** Take: first-peak eye-height overshoot after the change (times the level's take scale) by TARGET expression. */
    val FACE_TAKE_AMP: DoubleArray = doubleArrayOf(0.03, 0.10, 0.0, 0.05, 0.04, 0.06)
    /** Brow height above the eye in eye radii by expression under acting (worried and angry are the legacy 1.8). */
    val FACE_BROW_HEIGHT: DoubleArray = doubleArrayOf(1.8, 2.3, 1.4, 1.8, 1.8, 1.95)
    /** Brow inner-end lift in eye radii by expression under acting (worried +0.55 and angry -0.55 are the legacy values). */
    val FACE_BROW_LIFT: DoubleArray = doubleArrayOf(0.0, 0.20, 0.0, 0.55, -0.55, 0.12)
    /** Damping of the eye-height response. */
    const val FACE_ZETA: Double = 0.45
    /** Every face term is exactly neutral this long after an expression change. */
    const val FACE_SETTLE_SEC: Double = 0.6
    /** How many earlier expression changes a retarget may depend on (older ones count as settled). */
    const val FACE_CHAIN_DEPTH: Int = 4

    /** Gaze: offset cap as a fraction of the head radius; one saccade per slot at a seeded offset (intervals 1.0 to 3.2 s). */
    const val GAZE_CAP: Double = 0.18
    const val GAZE_SLOT_SEC: Double = 2.1
    const val GAZE_SLOT_SPREAD_SEC: Double = 1.1
    const val GAZE_SACCADE_SEC: Double = 0.06
    /** The head follows the eyes this much later, over this ramp, by at most [HEAD_TILT_MAX_DEG]. */
    const val GAZE_EYE_LEAD_SEC: Double = 0.09
    const val GAZE_HEAD_RAMP_SEC: Double = 0.25
    const val HEAD_TILT_MAX_DEG: Double = 3.0
    /** Return-to-centre bias: the chance that a saccade goes to (near) the centre. */
    const val GAZE_P_CENTRE: Double = 0.45
    /** A shift larger than this fraction of the head radius may carry a blink, with this seeded probability. */
    const val GAZE_BLINK_SHIFT: Double = 0.12
    const val GAZE_P_BLINK: Double = 0.35

    // WP2.4 speech mouth (prototype: tools/acting_proto/mouth_proto.py; the constants are the same).
    /** The envelope rate (AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS) and the track samples per envelope frame (120 Hz). */
    const val MOUTH_ENV_FPS: Int = 30
    const val MOUTH_SUBSTEPS: Int = 4
    /** Time constants in seconds: the opening rises with the attack and falls with the release; width and roundness co-articulate. */
    const val MOUTH_TAU_ATTACK: Double = 0.035
    const val MOUTH_TAU_RELEASE: Double = 0.085
    const val MOUTH_TAU_WIDTH: Double = 0.060
    const val MOUTH_TAU_ROUND: Double = 0.090
    /** Gate hysteresis as a fraction of the silence threshold, and the sustained silence that closes the mouth fully. */
    const val MOUTH_HYSTERESIS: Double = 0.08
    const val MOUTH_SILENCE_HOLD_SEC: Double = 0.10
    /** A class change must last this many envelope frames to count. */
    const val MOUTH_CLASS_DWELL: Int = 2
    /** A closed mouth snaps to exactly 0 below this opening. */
    const val MOUTH_SNAP_CLOSED: Double = 0.004
    /** Half-width and half-height as fractions of the head radius at parameter 0 and 1 (H0 is the legacy closed 0.05 x 0.5). */
    const val MOUTH_W_MIN: Double = 0.32
    const val MOUTH_W_MAX: Double = 0.46
    const val MOUTH_H0: Double = 0.025
    const val MOUTH_HMAX: Double = 0.28
    /** Legacy closed half-width. */
    const val MOUTH_CLOSED_HALF_WIDTH: Double = 0.44
    /** A loud WIDE vowel rounds up to this much; roundness narrows the mouth by up to MOUTH_ROUND_W_CUT and opens it by up to MOUTH_ROUND_H_GAIN. */
    const val MOUTH_ROUND_MAX: Double = 0.5
    const val MOUTH_ROUND_W_CUT: Double = 0.12
    const val MOUTH_ROUND_H_GAIN: Double = 0.25
}
