package com.example.engine

import kotlin.math.sqrt

/**
 * The numbers of a physics layer's motion trail (WP1.3), shared by both renderers so a trail looks the same on
 * Canvas and on GLES (I3: renderers only draw). The resolver supplies the trail points ([ResolvedOverlay.trailPoints],
 * oldest first, in world fractions of the canvas); a trail is a chain of round-capped line segments, each more
 * opaque than the one before, ending at the layer's current position. Pure: no Android types.
 *
 * The constants are the ones `RigRenderer.drawGmsTrail` has always used; the Canvas result is unchanged.
 */
object TrailGeometry {

    /** Stroke width when the layer has neither a radius nor a height: this fraction of the shorter canvas side. */
    const val DEFAULT_SIZE_FRACTION = 0.015f

    /** The stroke is this fraction of the layer's own size. */
    const val STROKE_SCALE = 0.6f

    /** The newest segment is this fraction of the layer's own opacity; older ones fade towards zero. */
    const val OPACITY_SCALE = 0.6f

    /** A segment shorter than this many pixels is drawn as a round dot (the GLES line primitive skips lines this short). */
    const val MIN_LINE_PX = 0.5f

    fun segmentCount(pointCount: Int): Int = if (pointCount >= 2) pointCount - 1 else 0

    /** Whether there is anything to draw: at least two points. */
    fun hasTrail(pointCount: Int): Boolean = pointCount >= 2

    /** Stroke width in canvas pixels. */
    fun strokeWidthPx(radius: Float?, height: Float?, canvasW: Int, canvasH: Int): Float =
        (radius ?: (height ?: DEFAULT_SIZE_FRACTION)) * minOf(canvasW, canvasH) * STROKE_SCALE

    /**
     * Opacity of segment [index] (0 = the oldest) of a trail with [pointCount] points, before it is combined with the
     * colour's own alpha: the oldest is nearly transparent, the newest is [OPACITY_SCALE] of [layerOpacity].
     * Call only when [hasTrail].
     */
    fun segmentOpacity(layerOpacity: Float, index: Int, pointCount: Int): Float {
        val progress = (index + 1).toFloat() / (pointCount - 1).toFloat()
        return layerOpacity * progress * OPACITY_SCALE
    }

    /** True when the segment from (x0,y0) to (x1,y1), in pixels, is so short that it is drawn as a dot. */
    fun isDot(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy) < MIN_LINE_PX
    }
}
