package com.example

import com.example.engine.TextLayoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.4 / R6: how wide a text block may be, given its anchor and alignment. */
class TextLayoutMathTest {

    private val w = 1080
    private val cap = (w * 0.92f).toInt()          // 993: the old fixed width
    private val margin = w * 0.04f                 // 43.2 px kept clear on each side

    @Test
    fun theOldFixedWidthIsUnchangedWhereNothingIsConstrained() {
        // Centred at the middle, for many canvas sizes (including ones where 0.92 * w is a whole number).
        for (cw in listOf(100, 360, 720, 1080, 1088, 1280, 1920, 2160, 4000)) {
            assertEquals("w=$cw", (cw * 0.92f).toInt(), TextLayoutMath.availableWidthPx("center", 0.5f, cw))
        }
        assertEquals(cap, TextLayoutMath.availableWidthPx("left", 0.04f, w))      // anchored at the margin: whole 0.92 fits
        assertEquals(cap, TextLayoutMath.availableWidthPx("left", 0.0f, w))
        assertEquals(cap, TextLayoutMath.availableWidthPx("right", 1.0f, w))
        assertEquals(cap, TextLayoutMath.availableWidthPx("right", 0.96f, w))
    }

    @Test
    fun leftAlignedTextGetsTheRoomToTheRightEdgeMinusTheMargin() {
        // Anchored at x = 0.1: room = 1080 - 43.2 - 108 = 928.8, rounded down to a multiple of 21 (1080 / 50 = 21).
        val got = TextLayoutMath.availableWidthPx("left", 0.1f, w)
        assertEquals(924, got)
        assertTrue(0.1f * w + got <= w - margin + 1e-3f)     // the box ends inside the right margin
        // Further right there is less room.
        assertTrue(TextLayoutMath.availableWidthPx("left", 0.5f, w) < TextLayoutMath.availableWidthPx("left", 0.1f, w))
        assertEquals(483, TextLayoutMath.availableWidthPx("left", 0.5f, w))      // 1080 - 43.2 - 540 = 496.8, rounded down to 23 steps of 21
    }

    @Test
    fun rightAlignedTextGetsTheRoomToTheLeftEdgeMinusTheMargin() {
        val got = TextLayoutMath.availableWidthPx("right", 0.3f, w)
        // anchor 324, room = 324 - 43.2 = 280.8 -> 273 (multiple of 21), above the floor of 270.
        assertEquals(273, got)
        assertTrue(0.3f * w - got >= margin - 1e-3f)         // the box starts inside the left margin
    }

    @Test
    fun centredTextGetsTwiceTheSmallerDistance() {
        // Anchor at 0.2: distances 216 and 864; twice the smaller after the margin: 2 * (216 - 43.2) = 345.6 -> 336.
        assertEquals(336, TextLayoutMath.availableWidthPx("center", 0.2f, w))
        // Symmetric on the other side.
        assertEquals(336, TextLayoutMath.availableWidthPx("center", 0.8f, w))
        // Anything that is not left or right is centred.
        assertEquals(336, TextLayoutMath.availableWidthPx("whatever", 0.2f, w))
    }

    @Test
    fun theBoxNeverLeavesTheCanvasWhenThereIsRoom() {
        for (align in listOf("left", "right", "center")) {
            var x = 0.0f
            while (x <= 1.0f) {
                val width = TextLayoutMath.availableWidthPx(align, x, w)
                val anchor = x * w
                val left = when (align) { "left" -> anchor; "right" -> anchor - width; else -> anchor - width / 2f }
                val right = left + width
                val floor = (w * 0.25f).toInt()
                if (width > floor) {      // above the floor the rule is in force: the box stays inside the canvas
                    assertTrue("$align x=$x left=$left", left >= -1e-3f)
                    assertTrue("$align x=$x right=$right", right <= w + 1e-3f)
                }
                x += 0.01f
            }
        }
    }

    @Test
    fun anAnchorAtTheEdgeKeepsAReadableMinimumWidth() {
        val floor = (w * 0.25f).toInt()                       // 270
        assertEquals(floor, TextLayoutMath.availableWidthPx("left", 1.0f, w))
        assertEquals(floor, TextLayoutMath.availableWidthPx("left", 1.5f, w))
        assertEquals(floor, TextLayoutMath.availableWidthPx("right", 0.0f, w))
        assertEquals(floor, TextLayoutMath.availableWidthPx("right", -0.4f, w))
        assertEquals(floor, TextLayoutMath.availableWidthPx("center", 0.0f, w))
        assertEquals(floor, TextLayoutMath.availableWidthPx("center", 1.0f, w))
    }

    @Test
    fun theWidthStepsInWholeFiftiethsSoAMovingAnchorDoesNotRebuildEveryFrame() {
        val seen = HashSet<Int>()
        var x = 0.04f
        while (x <= 0.96f) {
            seen.add(TextLayoutMath.availableWidthPx("left", x, w))
            x += 0.0005f
        }
        assertTrue("distinct widths: ${seen.size}", seen.size <= 52)       // at most one per step, plus the cap and the floor
        val floor = (w * 0.25f).toInt()
        for (v in seen) assertTrue("width $v", v == cap || v == floor || v % 21 == 0)
    }

    @Test
    fun brokenInputsFallBackToTheOldWidth() {
        assertEquals(cap, TextLayoutMath.availableWidthPx("left", Float.NaN, w))
        assertEquals(cap, TextLayoutMath.availableWidthPx("left", Float.POSITIVE_INFINITY, w))
        assertEquals(1, TextLayoutMath.availableWidthPx("center", 0.5f, 0))   // never zero
    }

    @Test
    fun landscapeFollowsTheSameRule() {
        val lw = 1920
        assertEquals((lw * 0.92f).toInt(), TextLayoutMath.availableWidthPx("center", 0.5f, lw))
        val got = TextLayoutMath.availableWidthPx("left", 0.1f, lw)
        assertTrue(0.1f * lw + got <= lw - lw * 0.04f + 1e-2f)
    }
}
