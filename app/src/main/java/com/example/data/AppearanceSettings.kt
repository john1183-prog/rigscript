package com.example.data

import kotlinx.serialization.Serializable

/**
 * Controls how the stick figure looks. Stored per-project so each video can
 * have its own colour scheme, stroke weight, and character scale.
 *
 * Colors are stored as ARGB Long (0xAARRGGBB) for easy JSON round-tripping.
 */
@Serializable
data class AppearanceSettings(
    // ── Figure colours ────────────────────────────────────────────────────────
    /** Main bone / limb colour. Default: pure blue. */
    val boneColor: Long = 0xFF0000FFL,
    /** Head fill colour. */
    val headColor: Long = 0xFF0000FFL,
    /** Joint dot colour. */
    val jointColor: Long = 0xFF0000FFL,

    // ── Stroke & size ─────────────────────────────────────────────────────────
    /** Bone stroke width as a fraction of min(canvasW, canvasH). */
    val boneStrokeNormalized: Float = 0.2f,
    /** Joint circle radius as a fraction of min(canvasW, canvasH). */
    val jointRadiusNormalized: Float = 0.010f,
    /** Whether to draw joint dots at all. */
    val showJoints: Boolean = false,
    /** Whether to draw joint dots in exported video. */
    val showJointsOnExport: Boolean = false,

    // ── Canvas / scene ────────────────────────────────────────────────────────
    /** Background colour for preview canvas. Also the gradient's top stop when backgroundStyle == "gradient". */
    val previewBgColor: Long = 0xFF1A1A2EL,
    /** Background colour in exported video. 0x00000000 = transparent (WebM only). */
    val exportBgColor: Long = 0xFF1A1A2EL,
    /** Overlay grid for spatial reference. */
    val showGrid: Boolean = false,
    val gridColor: Long = 0x22FFFFFFL,
    /** "solid" | "gradient" — gradient blends from the bg color (top) to backgroundGradientColor (bottom). */
    val backgroundStyle: String = "solid",
    /** Gradient bottom stop. Only used when backgroundStyle == "gradient". */
    val backgroundGradientColor: Long = 0xFF0A0A14L,
    /** Optional horizontal ground line for scene composition — gives exports a sense of "place" instead of a floating figure. */
    val showGroundLine: Boolean = false,
    val groundLineColor: Long = 0x33FFFFFFL,
    /** Ground line Y position as a fraction of canvas height. */
    val groundLineYFraction: Float = 0.82f,

    // ── Mouth (audio-reactive) ────────────────────────────────────────────────
    /** Whether to draw the amplitude/shape-driven mouth on the head circle. */
    val showMouth: Boolean = true,
    /** Mouth fill colour. Dark so it reads as an open/closed shape. */
    val mouthColor: Long = 0xFF0D0D14L,

    // ── Eyes / eyebrows (V2) ──────────────────────────────────────────────────
    /** Whether to draw eyes on the head circle. Eyes blink automatically and react to ScriptEvent.expression. */
    val showEyes: Boolean = true,
    val eyeColor: Long = 0xFF0D0D14L,
    /** Eyebrows are synthetic lines, only drawn for WORRIED/ANGRY expressions — see Expression.kt. */
    val eyebrowColor: Long = 0xFF0D0D14L,

    // ── Transform ─────────────────────────────────────────────────────────────
    /** Overall character scale multiplier (1.0 = default). */
    val characterScale: Float = 1.0f,
    /** Head circle radius multiplier, independent of [characterScale] — lets a stylised bigger/smaller head ratio without rescaling the whole body. 1.0 = default (headNormalizedRadius as authored on the bone). */
    val headScaleMultiplier: Float = 1.5f,
    /** Root anchor as fraction of canvas width (0..1). */
    val rootAnchorX: Float = 0.50f,
    /** Root anchor as fraction of canvas height (0..1). */
    val rootAnchorY: Float = 0.52f,

    // ── Eyes — position & shape (V2) ─────────────────────────────────────────
    /** How far apart the two eyes sit, as a fraction of the head radius. Was a fixed 0.34, which read as too close together for a bigger/thicker head style. */
    val eyeSpacingNormalized: Float = 0.34f,
    /** How far the eyes sit from the head's center toward the neck, as a fraction of the head radius (same head-tip/neck-axis technique the mouth also uses). */
    val eyeVerticalOffsetNormalized: Float = 0.12f,
    /** Eye height-to-width ratio at full openness (blinking still flattens toward a thin line regardless of this setting). 1.0 = perfectly round. The reference look this was built toward has genuinely oval eyes, but exactly how oval is a matter of taste — hence adjustable rather than hardcoded. */
    val eyeAspectRatio: Float = 1.2f
)
