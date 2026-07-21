package com.example.data

import kotlinx.serialization.Serializable

/**
 * Controls how the stick figure's body reacts to audio amplitude.
 *
 * All sensitivity values are in degrees of rotation per normalised amplitude unit (0–1).
 * The figure's response layers on top of regular pose animation; when no audio is
 * loaded the idle breathing loop uses [idleBreathAmplitude] and [breathFreqHz].
 */
@Serializable
data class AmplitudeSettings(
    /** Master toggle for all amplitude-driven motion. */
    val enabled: Boolean = true,

    // ── Idle breathing (always on when enabled, audio-independent) ────────────
    /** Torso rotation amplitude for the idle breathing cycle, in degrees. */
    val idleBreathAmplitude: Float = 1.4f,
    /** Frequency of the breathing oscillation in Hz. */
    val breathFreqHz: Float = 0.35f,

    // ── Audio-reactive motion ─────────────────────────────────────────────────
    /** Amplitude below this value is treated as silence; no reaction. */
    val silenceThreshold: Float = 0.08f,
    /**
     * Low-pass smoothing factor applied to raw amplitude each frame.
     * 0 = no smoothing (jittery), 1 = no reaction (frozen).
     * Values around 0.15–0.30 feel natural.
     */
    val smoothingFactor: Float = 0.22f,
    /** How much the torso oscillates while speaking, in degrees. */
    val talkTorsoAmplitude: Float = 5.0f,
    /** How much the head nods while speaking, in degrees. */
    val talkHeadNodAmplitude: Float = 3.5f,
    /** Frequency of the talking oscillation in Hz. */
    val talkFreqHz: Float = 4.5f,
    /** Whether arm bones receive a subtle micro-swing when speaking. */
    val armSwayEnabled: Boolean = true,
    /** Arm micro-sway amplitude in degrees. */
    val armSwayAmplitude: Float = 2.0f,

    // ── Transition physics (V2) ───────────────────────────────────────────────
    /**
     * When true (default), every pose transition gets a physics spring-chase
     * layered on top of its [com.example.data.ScriptEvent.ease] curve — not just
     * events explicitly tagged `"spring"`. This is the single biggest naturalism
     * change available without new animation features: everything gets a subtle,
     * consistent life-like overshoot instead of only the events an AI/human
     * remembered to mark. `"rigid"`-tagged events always bypass this regardless
     * of this flag — rigid is the explicit escape hatch for mechanical or abrupt
     * cuts where overshoot would look wrong.
     */
    val easeAllWithSpring: Boolean = true,

    // ── Eyes / blinking (V2) ──────────────────────────────────────────────────
    /** Automatic idle blinking, independent of [com.example.data.AnimScript.blinkEvents]. */
    val naturalBlinkEnabled: Boolean = true,
    /** Min/max gap between automatic blinks, in seconds — next interval is randomised in this range. */
    val blinkMinIntervalSec: Float = 2.5f,
    val blinkMaxIntervalSec: Float = 6.5f,
    /** How long a single blink (open → closed → open) takes, in seconds. Applies to both natural and scripted blinks. */
    val blinkDurationSec: Float = 0.15f,

    // ── Idle fidget (V2) ──────────────────────────────────────────────────────
    /**
     * Small randomized micro-gestures during silence gaps in the script —
     * makes the figure look "inhabited" during pauses rather than frozen.
     * Off by default per the original handoff's own guidance: some projects
     * (a deliberately still, formal delivery) may want silence to read as
     * stillness, not fidgeting — this is a stylistic choice, not a bug fix,
     * so it stays opt-in rather than becoming a new default behaviour.
     */
    val idleFidgetEnabled: Boolean = false,
    /** Minimum silent stretch (seconds) a candidate fidget must sit inside — avoids fidgeting during brief natural pauses between words/phrases. */
    val fidgetMinSilenceSec: Float = 1.5f,
    /** Min/max gap between fidget candidates, in seconds — next interval is randomised in this range, same technique as [blinkMinIntervalSec]/[blinkMaxIntervalSec]. */
    val fidgetMinIntervalSec: Float = 3.0f,
    val fidgetMaxIntervalSec: Float = 7.0f,
    /** Peak torso rotation during a fidget, in degrees — head/arm movement scale off this. */
    val fidgetAmplitude: Float = 3.5f,
    /** Duration of one fidget's ease-in/hold/ease-out cycle, in seconds. */
    val fidgetDurationSec: Float = 0.6f
)
