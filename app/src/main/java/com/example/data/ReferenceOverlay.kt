package com.example.data

import kotlinx.serialization.Serializable

/**
 * A manual, one-time-configured reference overlay (image or text) shown on top
 * of (or behind) the stick figure — e.g. a logo, a diagram to point at, or a
 * caption-style label the user positions by hand.
 *
 * Deliberately NOT part of the AI script-generation pipeline: [type], [imagePath],
 * and [text] are set once by the user in the editor UI and are never referenced
 * by [com.example.engine.TimelineCompiler] or exposed to the AI prompt — see
 * PROMPT_CONSIDERATIONS.md. Only [durationSec]/[startSec] give it any time
 * component, and even those are optional (null = shown for the whole project).
 *
 * [type]            NONE | IMAGE | TEXT — see [OverlayType].
 * [imagePath]       Absolute on-device path to the imported image, set by
 *                    [com.example.viewmodel.MainViewModel.importReferenceImage].
 *                    Null when [type] != IMAGE.
 * [cropLeft]/[cropTop]/[cropRight]/[cropBottom]
 *                    Crop fractions (0..1) applied to the source image before
 *                    it's placed on canvas — lets the user frame a sub-region
 *                    of a larger reference image without a separate crop tool.
 * [text]             The overlay's text content. Null/blank when [type] != TEXT.
 * [textColor]        ARGB Long, same convention as [AppearanceSettings] colors.
 * [showBackdrop]     Whether to draw a semi-opaque backing rect behind the text
 *                    for legibility over a busy scene.
 * [posX]/[posY]      Overlay anchor position, as a fraction of canvas width/height.
 * [sizeFraction]     Overlay size as a fraction of the canvas's shorter dimension.
 * [inFrontOfFigure]  If true, drawn after (on top of) the stick figure; if
 *                    false, drawn before it (the figure can occlude it).
 * [startSec]/[durationSec]
 *                    Optional visibility window in seconds. Both null = visible
 *                    for the entire project. If only [startSec] is set, visible
 *                    from that point to the end.
 */
@Serializable
data class ReferenceOverlay(
    val type: String = OverlayType.NONE,

    // Image variant
    val imagePath: String? = null,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,

    // Text variant
    val text: String? = null,
    val textColor: Long = 0xFFFFFFFFL,
    val showBackdrop: Boolean = true,

    // Shared placement
    val posX: Float = 0.5f,
    val posY: Float = 0.15f,
    val sizeFraction: Float = 0.25f,
    val inFrontOfFigure: Boolean = true,

    // Optional visibility window
    val startSec: Float? = null,
    val durationSec: Float? = null
) {
    /** True if this overlay should render at all right now (has content AND is within its optional time window). */
    fun isVisibleAt(timeSec: Float): Boolean {
        if (type == OverlayType.NONE) return false
        if (type == OverlayType.IMAGE && imagePath.isNullOrBlank()) return false
        if (type == OverlayType.TEXT && text.isNullOrBlank()) return false
        val start = startSec ?: return true
        if (timeSec < start) return false
        val dur = durationSec ?: return true
        return timeSec <= start + dur
    }

    object OverlayType {
        const val NONE  = "none"
        const val IMAGE = "image"
        const val TEXT  = "text"
    }
}
