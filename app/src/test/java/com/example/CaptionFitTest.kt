package com.example

import com.example.engine.CaptionFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.4 / R11: the shared caption fit and the new height cap. */
class CaptionFitTest {

    /** A stand-in for StaticLayout: fixed character width, 1.2 line height times the 1.1 spacing the renderers use. */
    private class FakeLayout(val size: Float, val lines: Int, val height: Int)

    private fun fake(chars: Int, maxWidthPx: Int): (Float, Int) -> FakeLayout = { size, maxLines ->
        val charsPerLine = (maxWidthPx / (0.5f * size)).toInt().coerceAtLeast(1)
        val natural = (chars + charsPerLine - 1) / charsPerLine
        val lines = if (maxLines > 0) minOf(natural, maxLines) else natural
        FakeLayout(size, lines, (lines * size * 1.2f * 1.1f).toInt())
    }

    private fun fitNew(initial: Float, minSize: Float, limit: Float, maxLines: Int, chars: Int, maxWidthPx: Int) =
        CaptionFit.fit(initial, minSize, limit, maxLines, fake(chars, maxWidthPx), { it.height }, { it.lines })

    /** The loops exactly as RigRenderer.drawCaption had them before the extraction (the reference). */
    private fun fitReference(initial: Float, minSize: Float, limit: Float, maxLines: Int, chars: Int, maxWidthPx: Int): Pair<FakeLayout, Float> {
        val build = fake(chars, maxWidthPx)
        var textSize = initial
        var layout = build(textSize, maxLines)
        var iterations = 0
        while (layout.height > limit && textSize > minSize && iterations++ < 25) {
            textSize = (textSize * 0.9f).coerceAtLeast(minSize)
            layout = build(textSize, maxLines)
            if (textSize == minSize) break
        }
        if (layout.height > limit && layout.lines > 1) {
            val avg = (layout.height.toFloat() / layout.lines).coerceAtLeast(1f)
            var fitting = (limit / avg).toInt().coerceIn(1, layout.lines)
            if (maxLines > 0) fitting = minOf(fitting, maxLines)
            layout = build(textSize, fitting)
            while (layout.height > limit && fitting > 1) {
                fitting--
                layout = build(textSize, fitting)
            }
        }
        return layout to textSize
    }

    // ── the height limit ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDefaultCapsTheBoxAtHalfTheCanvasHeight() {
        val h = 1920
        val padding = h * 0.02f
        val boxBottom = h - h * 0.06f
        val limit = CaptionFit.maxLayoutHeight(h, boxBottom, padding, CaptionFit.DEFAULT_MAX_HEIGHT_FRACTION)
        assertEquals(h * 0.5f - padding * 2f, limit, 1e-3f)          // the box (text + padding) is half the canvas
        assertTrue(limit < boxBottom - padding * 2f)                   // tighter than the old limit
    }

    @Test
    fun aFractionOfOneOrMoreLeavesTheOldLimit() {
        val h = 1080
        val padding = h * 0.02f
        val boxBottom = h - h * 0.06f
        val old = boxBottom - padding * 2f
        assertEquals(old, CaptionFit.maxLayoutHeight(h, boxBottom, padding, 1f), 1e-3f)
        assertEquals(old, CaptionFit.maxLayoutHeight(h, boxBottom, padding, 3f), 1e-3f)
    }

    @Test
    fun aDamagedFractionMeansNoCapSoCaptionsNeverVanish() {
        val h = 1080
        val padding = h * 0.02f
        val boxBottom = h - h * 0.06f
        val old = boxBottom - padding * 2f
        for (bad in listOf(0f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("fraction $bad", old, CaptionFit.maxLayoutHeight(h, boxBottom, padding, bad), 1e-3f)
        }
    }

    @Test
    fun theLimitIsNeverNegative() {
        assertEquals(0f, CaptionFit.maxLayoutHeight(100, 10f, 20f, 0.5f), 0f)
    }

    // ── the fit ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNormalCaptionIsNotTouched() {
        val h = 1920
        val padding = h * 0.02f
        val limit = CaptionFit.maxLayoutHeight(h, h - h * 0.06f, padding, 0.5f)
        val initial = h * 0.045f
        val f = fitNew(initial, 19.2f, limit, 0, chars = 60, maxWidthPx = 950)
        assertEquals(initial, f.textSize, 0f)           // no shrinking
        assertEquals(60 / (950 / (0.5f * initial)).toInt() + 1, f.layout.lines)
    }

    @Test
    fun aVeryLongCaptionNowStopsAtTheCap() {
        val h = 1920
        val padding = h * 0.02f
        val boxBottom = h - h * 0.06f
        val initial = h * 0.045f
        val capped = CaptionFit.maxLayoutHeight(h, boxBottom, padding, 0.5f)
        val old = CaptionFit.maxLayoutHeight(h, boxBottom, padding, 1f)
        val chars = 2500
        val withCap = fitNew(initial, 19.2f, capped, 0, chars, 950)
        val withoutCap = fitNew(initial, 19.2f, old, 0, chars, 950)
        assertTrue("capped height ${withCap.layout.height} <= $capped", withCap.layout.height <= capped)
        assertTrue(withCap.layout.height < withoutCap.layout.height || withCap.layout.height <= capped)
        assertTrue(withoutCap.layout.height > capped)       // this caption really was taller than the cap before
    }

    @Test
    fun theUsersLineLimitIsRespected() {
        val limit = 800f
        for (maxLines in listOf(1, 2, 3)) {
            val f = fitNew(86.4f, 19.2f, limit, maxLines, chars = 900, maxWidthPx = 950)
            assertTrue("lines ${f.layout.lines} <= $maxLines", f.layout.lines <= maxLines)
        }
    }

    @Test
    fun itNeverShrinksBelowTheMinimumSize() {
        val f = fitNew(86.4f, 40f, 30f, 0, chars = 5000, maxWidthPx = 950)
        assertTrue(f.textSize >= 40f)
        assertEquals(1, f.layout.lines)           // nothing else left to give up
    }

    @Test
    fun theExtractedLoopIsTheOldLoopForEverySizeAndLimit() {
        for (h in listOf(640, 1080, 1280, 1920)) {
            val padding = h * 0.02f
            val boxBottom = h - h * 0.06f
            for (fraction in listOf(1f, 0.5f, 0.35f)) {
                val limit = CaptionFit.maxLayoutHeight(h, boxBottom, padding, fraction)
                for (maxLines in listOf(0, 1, 2, 4)) {
                    for (chars in listOf(1, 20, 60, 140, 400, 1200, 4000)) {
                        val initial = h * 0.045f
                        val minSize = (h * 0.01f).coerceAtLeast(1f)
                        val width = (h * 0.5f).toInt()
                        val got = fitNew(initial, minSize, limit, maxLines, chars, width)
                        val (refLayout, refSize) = fitReference(initial, minSize, limit, maxLines, chars, width)
                        val tag = "h=$h fraction=$fraction maxLines=$maxLines chars=$chars"
                        assertEquals(tag, refSize, got.textSize, 0f)
                        assertEquals(tag, refLayout.lines, got.layout.lines)
                        assertEquals(tag, refLayout.height, got.layout.height)
                    }
                }
            }
        }
    }
}
