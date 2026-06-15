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
    val boneStrokeNormalized: Float = 0.012f,
    /** Joint circle radius as a fraction of min(canvasW, canvasH). */
    val jointRadiusNormalized: Float = 0.010f,
    /** Whether to draw joint dots at all. */
    val showJoints: Boolean = false,
    /** Whether to draw joint dots in exported video. */
    val showJointsOnExport: Boolean = false,

    // ── Canvas ────────────────────────────────────────────────────────────────
    /** Background colour for preview canvas. */
    val previewBgColor: Long = 0xFF1A1A2EL,
    /** Background colour in exported video. 0x00000000 = transparent (WebM only). */
    val exportBgColor: Long = 0xFF1A1A2EL,
    /** Overlay grid for spatial reference. */
    val showGrid: Boolean = false,
    val gridColor: Long = 0x22FFFFFFL,

    // ── Transform ─────────────────────────────────────────────────────────────
    /** Overall character scale multiplier (1.0 = default). */
    val characterScale: Float = 1.0f,
    /** Root anchor as fraction of canvas width (0..1). */
    val rootAnchorX: Float = 0.50f,
    /** Root anchor as fraction of canvas height (0..1). */
    val rootAnchorY: Float = 0.52f
)
