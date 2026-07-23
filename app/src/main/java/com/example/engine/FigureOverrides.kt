package com.example.engine

/**
 * Per-frame script-driven overrides of otherwise-static [com.example.data.AppearanceSettings]
 * fields — figure position/scale and figure/scene colors, now directly
 * animatable by the AI instead of being fixed for the whole project.
 *
 * Every field is nullable with the SAME meaning null already has for
 * [BakedKeyframe]'s camera/scene fields: "no scripted override is active as
 * of this instant" — [RigRenderer] falls back to [com.example.data.AppearanceSettings]'s
 * own value in that case, so a project that never sets any of these renders
 * exactly as it did before this feature existed.
 *
 * Bundled into one object (rather than ~15 individual [RigRenderer.draw]
 * parameters) the same way [com.example.data.ReferenceOverlay] and
 * [com.example.data.AmplitudeSettings] already are — one param to add/thread
 * through call sites instead of fifteen.
 */
data class FigureOverrides(
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null,
    val headScale: Float? = null,
    val boneColor: Long? = null,
    val headColor: Long? = null,
    val jointColor: Long? = null,
    val bgColor: Long? = null,
    val backgroundGradientColor: Long? = null,
    val backgroundStyle: String? = null,
    val groundLineColor: Long? = null,
    val showGroundLine: Boolean? = null,
    val groundLineYFraction: Float? = null,
    val mouthColor: Long? = null,
    val eyeColor: Long? = null,
    val eyebrowColor: Long? = null
)
