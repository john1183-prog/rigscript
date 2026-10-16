package com.example

import com.example.engine.TrailGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.3: the shared numbers of a physics layer's trail. The expected values were derived by reading RigRenderer.drawGmsTrail. */
class TrailGeometryTest {

    @Test
    fun aTrailNeedsTwoPoints() {
        assertFalse(TrailGeometry.hasTrail(0))
        assertFalse(TrailGeometry.hasTrail(1))
        assertTrue(TrailGeometry.hasTrail(2))
        assertEquals(0, TrailGeometry.segmentCount(0))
        assertEquals(0, TrailGeometry.segmentCount(1))
        assertEquals(1, TrailGeometry.segmentCount(2))
        assertEquals(7, TrailGeometry.segmentCount(8))
    }

    @Test
    fun theStrokeIsSixtyPercentOfTheLayersSizeOnTheShorterSide() {
        // radius wins, then height, then the default 0.015.
        assertEquals(0.02f * 1080 * 0.6f, TrailGeometry.strokeWidthPx(0.02f, 0.5f, 1080, 1920), 1e-4f)
        assertEquals(0.1f * 1080 * 0.6f, TrailGeometry.strokeWidthPx(null, 0.1f, 1080, 1920), 1e-4f)
        assertEquals(0.015f * 1080 * 0.6f, TrailGeometry.strokeWidthPx(null, null, 1080, 1920), 1e-4f)
        // Landscape uses the same shorter side.
        assertEquals(TrailGeometry.strokeWidthPx(0.02f, null, 1080, 1920), TrailGeometry.strokeWidthPx(0.02f, null, 1920, 1080), 1e-6f)
        assertEquals(9.72f, TrailGeometry.strokeWidthPx(null, null, 1080, 1920), 1e-3f)
    }

    @Test
    fun theOldestSegmentIsFaintestAndTheNewestIsSixtyPercentOfTheLayer() {
        val n = 5
        assertEquals(0.25f * 0.6f, TrailGeometry.segmentOpacity(1f, 0, n), 1e-6f)
        assertEquals(0.6f, TrailGeometry.segmentOpacity(1f, n - 2, n), 1e-6f)
        var previous = -1f
        for (i in 0 until TrailGeometry.segmentCount(n)) {
            val o = TrailGeometry.segmentOpacity(1f, i, n)
            assertTrue("segment $i", o > previous)
            previous = o
        }
        // It scales with the layer's own opacity, and a two-point trail is one segment at the cap.
        assertEquals(0.3f, TrailGeometry.segmentOpacity(0.5f, 0, 2), 1e-6f)
        assertEquals(0f, TrailGeometry.segmentOpacity(0f, 3, 6), 1e-9f)
    }

    @Test
    fun theFormulasMatchWhatTheCanvasPathUsedBeforeTheyWereExtracted() {
        for (opacity in listOf(0.1f, 0.5f, 1f)) for (n in 2..9) for (i in 0 until n - 1) {
            val segProgress = (i + 1).toFloat() / (n - 1).toFloat()          // the original line in drawGmsTrail
            assertEquals(opacity * segProgress * 0.6f, TrailGeometry.segmentOpacity(opacity, i, n), 0f)
        }
        for (r in listOf(null, 0.01f, 0.2f)) for (hgt in listOf(null, 0.3f)) {
            val minDim = minOf(1080, 1920)
            val original = (r ?: (hgt ?: 0.015f)) * minDim * 0.6f                // the original strokeWidth expression
            assertEquals(original, TrailGeometry.strokeWidthPx(r, hgt, 1080, 1920), 0f)
        }
    }

    @Test
    fun aTinySegmentIsADotAndAVisibleOneIsNot() {
        assertTrue(TrailGeometry.isDot(10f, 10f, 10f, 10f))
        assertTrue(TrailGeometry.isDot(10f, 10f, 10.3f, 10.3f))     // length 0.42
        assertFalse(TrailGeometry.isDot(10f, 10f, 10.5f, 10f))      // exactly the line primitive's threshold
        assertFalse(TrailGeometry.isDot(0f, 0f, 30f, 40f))
    }
}
