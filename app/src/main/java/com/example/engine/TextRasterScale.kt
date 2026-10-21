package com.example.engine

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * At what scale a GLES text bitmap is rasterized (WP1.4 / R2 and R5). Pure: no Android types.
 *
 * Until now every text overlay was rasterized once at its unscaled size and the GPU resampled that bitmap whenever
 * the camera zoom, the layer's own scale or a rotation made the on-screen size differ, which made zoomed, scaled and
 * rotated text visibly softer than Canvas (which re-renders the glyphs at the final size). Now the already-wrapped
 * layout is drawn into the bitmap under a canvas scale equal to the on-screen scale rounded to a step, so the bitmap
 * is within a few percent of 1:1 with the screen. Rounding to steps (4 per octave) keeps the number of bitmaps per
 * text small while a zoom animation runs; unscaled text (scale 1) rasterizes exactly as before.
 */
object TextRasterScale {

    const val STEPS_PER_OCTAVE = 4
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f

    /** No bitmap side longer than this: a GL texture can be refused above the device's maximum (often 4096). */
    const val MAX_DIMENSION = 4096

    /** No bitmap with more pixels than this (32 MB as ARGB_8888). */
    const val MAX_PIXELS = 8_388_608L

    /**
     * The scale to rasterize at for text shown at [effectiveScale] (the layer's scale times the camera zoom):
     * `2^(k / 4)` for the nearest whole k, kept within [[MIN_SCALE], [MAX_SCALE]]. Exactly 1 for an effective scale
     * of 1; 1 for anything that is not a positive number.
     */
    fun rasterScale(effectiveScale: Float): Float {
        if (!effectiveScale.isFinite() || effectiveScale <= 0f) return 1f
        val clamped = effectiveScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val steps = (log2(clamped) * STEPS_PER_OCTAVE).roundToInt()
        return 2.0.pow(steps.toDouble() / STEPS_PER_OCTAVE).toFloat()
    }

    /** Whether a bitmap of this size may be made at all. */
    fun fits(widthPx: Int, heightPx: Int): Boolean =
        widthPx in 1..MAX_DIMENSION && heightPx in 1..MAX_DIMENSION && widthPx.toLong() * heightPx <= MAX_PIXELS

    /**
     * True when a bitmap rasterized at [rasterScale] lands on the screen pixel for pixel when the text is shown at
     * [effectiveScale] (to within a tenth of a percent), so an upright quad can be snapped to whole pixels and
     * sampled without blur.
     */
    fun isOneToOne(effectiveScale: Float, rasterScale: Float): Boolean =
        rasterScale > 0f && abs(effectiveScale - rasterScale) <= 1e-3f * rasterScale
}
