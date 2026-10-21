package com.example

import com.example.engine.TextRasterScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WP1.4 / R2 and R5: the scale a GLES text bitmap is rasterized at. */
class TextRasterScaleTest {

    @Test
    fun unscaledTextIsRasterizedExactlyAsBefore() {
        assertEquals(1f, TextRasterScale.rasterScale(1f), 0f)
        assertTrue(TextRasterScale.isOneToOne(1f, 1f))
    }

    @Test
    fun theScaleIsTheNearestQuarterOctave() {
        assertEquals(2f, TextRasterScale.rasterScale(2f), 1e-6f)
        assertEquals(4f, TextRasterScale.rasterScale(4f), 1e-6f)
        assertEquals(0.5f, TextRasterScale.rasterScale(0.5f), 1e-6f)
        assertEquals(2.0.pow(0.25).toFloat(), TextRasterScale.rasterScale(1.15f), 1e-6f)     // 1.15 rounds to one step up (1.189)
        assertEquals(2.0.pow(0.5).toFloat(), TextRasterScale.rasterScale(1.5f), 1e-6f)       // log2 1.5 * 4 = 2.34 -> 2 steps
        assertEquals(1f, TextRasterScale.rasterScale(1.05f), 0f)                              // within half a step of 1
    }

    @Test
    fun theScaleIsKeptInsideTheSupportedRange() {
        assertEquals(4f, TextRasterScale.rasterScale(10f), 1e-6f)
        assertEquals(4f, TextRasterScale.rasterScale(Float.MAX_VALUE), 1e-6f)
        assertEquals(0.25f, TextRasterScale.rasterScale(0.01f), 1e-6f)
    }

    @Test
    fun brokenInputsFallBackToOne() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("scale $bad", 1f, TextRasterScale.rasterScale(bad), 0f)
        }
    }

    @Test
    fun theBitmapIsAlwaysWithinAboutNinePercentOfTheScreenSize() {
        var s = 0.25f
        while (s <= 4f) {
            val ratio = s / TextRasterScale.rasterScale(s)
            assertTrue("scale $s ratio $ratio", ratio in 0.916f..1.091f)
            s *= 1.003f
        }
    }

    @Test
    fun aZoomAnimationNeedsOnlyAFewBitmaps() {
        val seen = HashSet<Float>()
        var s = 1f
        while (s <= 2.5f) { seen.add(TextRasterScale.rasterScale(s)); s += 0.001f }
        assertTrue("distinct scales ${seen.size}", seen.size <= 6)
        // The whole supported range: at most 4 octaves x 4 steps + 1.
        val all = HashSet<Float>()
        s = 0.25f
        while (s <= 4f) { all.add(TextRasterScale.rasterScale(s)); s += 0.001f }
        assertTrue("distinct scales over the whole range ${all.size}", all.size <= 17)
    }

    @Test
    fun theScaleNeverGoesBackwardsAsTheTextGrows() {
        var previous = 0f
        var s = 0.2f
        while (s <= 5f) {
            val r = TextRasterScale.rasterScale(s)
            assertTrue("scale $s", r >= previous)
            previous = r
            s += 0.002f
        }
    }

    @Test
    fun oneToOneNeedsTheScreenSizeToMatchTheBitmap() {
        assertTrue(TextRasterScale.isOneToOne(2f, 2f))
        assertTrue(TextRasterScale.isOneToOne(2.001f, 2f))
        assertFalse(TextRasterScale.isOneToOne(2.1f, 2f))
        assertFalse(TextRasterScale.isOneToOne(1f, 1.1892f))
        assertFalse(TextRasterScale.isOneToOne(1f, 0f))
    }

    @Test
    fun aBitmapThatIsTooBigIsRefused() {
        assertTrue(TextRasterScale.fits(1080, 600))
        assertTrue(TextRasterScale.fits(4096, 2048))
        assertFalse(TextRasterScale.fits(4097, 100))
        assertFalse(TextRasterScale.fits(100, 4097))
        assertFalse(TextRasterScale.fits(4096, 4096))        // 16.7 MP
        assertFalse(TextRasterScale.fits(0, 10))
    }
}
