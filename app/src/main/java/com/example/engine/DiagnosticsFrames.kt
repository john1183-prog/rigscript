package com.example.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Finds where the figure and the overlay text are in a decoded frame of the visual fixture (WP1.6b), so the parity
 * metrics are measured in the right places without anyone guessing where the figure was drawn.
 *
 * The fixture ([DiagnosticsFixtures.visualScriptJson]) draws the figure in the product's default bone colour (blue)
 * and its text in yellow, and uses neither colour anywhere else, so a colour test over the Canvas frame gives tight
 * bounding boxes. The tests are deliberately loose (the frame went through an H.264 round trip, which shifts colours a
 * little). Pure Kotlin on `IntArray` ARGB pixels; the same boxes are applied to both backends' frames, so a
 * difference between the backends cannot move the box.
 */
object DiagnosticsFrames {

    /** A box grows by this many pixels on each side so that anti-aliased edges and the codec's blur are inside it. */
    const val BOX_PAD_PX = 4

    /** Fewer matching pixels than this means "not found" (a stray compression artefact is not a figure). */
    const val MIN_MATCHING_PIXELS = 40

    /** The share of the figure box, from its top, that is taken as the head for the (reported only) mouth ink. */
    const val HEAD_FRACTION = 0.22f

    /** Blue enough to be a bone: blue high, red and green low. */
    fun isFigurePixel(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return b >= 150 && r <= 100 && g <= 100
    }

    /** Yellow enough to be the fixture's text: red and green high, blue low. */
    fun isTextPixel(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 200 && g >= 150 && b <= 110
    }

    /** The tight box of the figure's blue pixels in [pixels] (`width * height`), padded, or null if there are too few. */
    fun figureBox(pixels: IntArray, width: Int, height: Int): PixelBox? =
        boxOf(pixels, width, height) { isFigurePixel(it) }

    /** The tight box of the yellow text pixels, padded, or null if there are too few. */
    fun textBox(pixels: IntArray, width: Int, height: Int): PixelBox? =
        boxOf(pixels, width, height) { isTextPixel(it) }

    /**
     * The part of [figure] that holds the head (and with it the mouth): its top [HEAD_FRACTION]. An approximation, used
     * only for the reported mouth-ink ratio (R4), never for a pass or fail.
     */
    fun mouthRegion(figure: PixelBox): PixelBox {
        val headHeight = max(1, (figure.height * HEAD_FRACTION).toInt())
        return PixelBox(figure.left, figure.top, figure.right, min(figure.bottom, figure.top + headHeight))
    }

    private inline fun boxOf(pixels: IntArray, width: Int, height: Int, test: (Int) -> Boolean): PixelBox? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var count = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (test(pixels[row + x])) {
                    count++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (count < MIN_MATCHING_PIXELS) return null
        return PixelBox(
            max(0, left - BOX_PAD_PX),
            max(0, top - BOX_PAD_PX),
            min(width, right + 1 + BOX_PAD_PX),
            min(height, bottom + 1 + BOX_PAD_PX)
        )
    }
}
