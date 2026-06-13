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
    val idleBreathAmplitude: Float = 2.5f,
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
    val armSwayAmplitude: Float = 2.0f
)
