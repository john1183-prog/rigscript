package com.example.engine

/**
 * Caption fitting (WP1.4 / R11), shared by both renderers so Canvas and GLES shrink and truncate a long caption the
 * same way. Before this the loop existed twice (RigRenderer.drawCaption and GlesFrameRenderer.ensureCaptionTexture)
 * and the only height limit was "everything above the bottom margin", so a very long caption could fill most of the
 * screen. The algorithm itself is the old one, unchanged; only the height it is given to fit is new. No Android
 * types: the layout is whatever the caller builds (a `StaticLayout`), reached through three small lambdas.
 */
object CaptionFit {

    /** Default for `AppearanceSettings.captionMaxHeightFraction`: the caption box may use this share of the canvas height. */
    const val DEFAULT_MAX_HEIGHT_FRACTION = 0.5f

    /**
     * The tallest the TEXT of a caption may be, in pixels. Two limits, the smaller wins: the room that was always
     * allowed (everything above the bottom margin, less the padding) and, new in R11, [maxHeightFraction] of the
     * canvas height for the whole box (text plus padding). A fraction that is not a positive number means "no cap"
     * (the old behaviour), so a damaged setting cannot make captions vanish.
     */
    fun maxLayoutHeight(canvasH: Int, boxBottom: Float, padding: Float, maxHeightFraction: Float): Float {
        val room = boxBottom - padding * 2f
        val cap = if (maxHeightFraction.isFinite() && maxHeightFraction > 0f) {
            canvasH * maxHeightFraction.coerceAtMost(1f) - padding * 2f
        } else {
            room
        }
        return minOf(room, cap).coerceAtLeast(0f)
    }

    /** The layout that fits and the text size it was built at. */
    class Fitted<L>(val layout: L, val textSize: Float)

    /**
     * Builds the caption layout so its height is at most [maxLayoutHeight]: first shrink the text by 10% steps (at
     * most 25 times, never below [minTextSize]), then, if it still does not fit and has several lines, cut the number
     * of lines until it does (the last visible line is ellipsized by the layout). [configuredMaxLines] is the user's
     * own line limit, 0 for none. [build] makes a layout at a text size and a line limit (0 = unlimited), [heightOf]
     * and [linesOf] read it.
     */
    inline fun <L> fit(
        initialTextSize: Float,
        minTextSize: Float,
        maxLayoutHeight: Float,
        configuredMaxLines: Int,
        build: (size: Float, maxLines: Int) -> L,
        heightOf: (L) -> Int,
        linesOf: (L) -> Int
    ): Fitted<L> {
        var textSize = initialTextSize
        var layout = build(textSize, configuredMaxLines)
        var iterations = 0
        while (heightOf(layout) > maxLayoutHeight && textSize > minTextSize && iterations++ < 25) {
            textSize = (textSize * 0.9f).coerceAtLeast(minTextSize)
            layout = build(textSize, configuredMaxLines)
            if (textSize == minTextSize) break
        }

        if (heightOf(layout) > maxLayoutHeight && linesOf(layout) > 1) {
            val avgLineHeight = (heightOf(layout).toFloat() / linesOf(layout)).coerceAtLeast(1f)
            var fittingLines = (maxLayoutHeight / avgLineHeight).toInt().coerceIn(1, linesOf(layout))
            if (configuredMaxLines > 0) {
                fittingLines = minOf(fittingLines, configuredMaxLines)
            }
            layout = build(textSize, fittingLines)
            while (heightOf(layout) > maxLayoutHeight && fittingLines > 1) {
                fittingLines--
                layout = build(textSize, fittingLines)
            }
        }
        return Fitted(layout, textSize)
    }
}
