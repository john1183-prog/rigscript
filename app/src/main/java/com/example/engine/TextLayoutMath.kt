package com.example.engine

/**
 * Pure layout rules for overlay text (WP1.4 / R6), shared by both renderers through
 * [RigRenderer.buildOverlayTextLayout] so Canvas and GLES wrap text identically. No Android types.
 *
 * Until now every text layer wrapped at a fixed 0.92 of the canvas width whatever its anchor or alignment, so
 * left-aligned text anchored at x = 0.1 could run past the right edge and text centred at x = 0.2 past the left.
 * The width a block may use is now what is left between its anchor and the canvas edge, keeping the same margin
 * (4% of the canvas on each side) the old 0.92 implied.
 */
object TextLayoutMath {

    /** The widest a wrapped text block ever is, as a fraction of the canvas width (the old fixed width). */
    const val MAX_WIDTH_FRACTION = 0.92f

    /**
     * The narrowest it is made, as a fraction of the canvas width. An anchor at the very edge would otherwise leave
     * no room at all and shrink the text to nothing; such text still overflows, as it always did, but stays readable.
     */
    const val MIN_WIDTH_FRACTION = 0.25f

    /**
     * The width is rounded DOWN to a multiple of one fiftieth of the canvas, so a layer whose anchor moves does not
     * build a new layout (and, on GLES, a new texture) every frame; it reflows in at most 50 steps.
     */
    const val QUANT_STEPS = 50

    /**
     * Pixels of width available to a text block anchored at [anchorXFraction] (a fraction of the canvas width, 0 at
     * the left edge) with alignment [align] (`"left"`, `"right"`, anything else means centred):
     *  - left: from the anchor to the right edge, minus the margin;
     *  - right: from the left edge, plus the margin, to the anchor;
     *  - centred: twice the smaller of the two distances to the edges;
     * each capped at [MAX_WIDTH_FRACTION] of [canvasW] and floored at [MIN_WIDTH_FRACTION]. When nothing is
     * constrained (centred at the middle, or any anchor with room to spare) the result is exactly the old fixed
     * width, `(canvasW * 0.92f).toInt()`, so text that already fitted renders as before.
     */
    fun availableWidthPx(align: String, anchorXFraction: Float, canvasW: Int): Int {
        val w = canvasW.coerceAtLeast(1)
        val cap = (w * MAX_WIDTH_FRACTION).toInt().coerceAtLeast(1)
        if (!anchorXFraction.isFinite()) return cap

        val anchor = anchorXFraction * w
        val margin = w * (1f - MAX_WIDTH_FRACTION) / 2f
        val room = when (align) {
            "left" -> (w - margin) - anchor
            "right" -> anchor - margin
            else -> 2f * minOf(anchor - margin, (w - margin) - anchor)
        }
        // One pixel of tolerance keeps rounding noise (centred at 0.5 is mathematically exactly the cap) from tipping
        // the common case into the quantized branch.
        if (room >= cap - 1f) return cap

        val floor = minOf((w * MIN_WIDTH_FRACTION).toInt().coerceAtLeast(1), cap)
        val step = (w / QUANT_STEPS).coerceAtLeast(1)
        val quantized = (room / step).toInt() * step
        return quantized.coerceIn(floor, cap)
    }
}
