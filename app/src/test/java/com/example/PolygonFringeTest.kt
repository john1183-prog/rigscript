package com.example

import com.example.engine.PolygonFringe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** WP1.4 / R3: the anti-aliasing fringe geometry for filled polygons. */
class PolygonFringeTest {

    private val red = 0xFFFF0000.toInt()
    private val redTransparent = 0x00FF0000

    private fun alphaOf(argb: Int) = (argb ushr 24) and 0xFF

    /** Signed distance from point (px,py) to the infinite line through (ax,ay)-(bx,by), positive on the left. */
    private fun side(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return ((px - ax) * dy - (py - ay) * dx) / sqrt(dx * dx + dy * dy)
    }

    private fun triangle(vararg p: Float) = p

    @Test
    fun aSingleTriangleGetsAStripOnEachEdgeAndAWedgeAtEachCorner() {
        val tri = triangle(0f, 0f, 100f, 0f, 0f, 100f)
        val f = PolygonFringe.forTriangles(tri, intArrayOf(red, red, red))
        // 3 edges x 2 triangles x 3 vertices + 3 corners x 1 triangle x 3 vertices.
        assertEquals(3 * 6 + 3 * 3, f.colorsArgb.size)
        assertEquals(f.colorsArgb.size * 2, f.xy.size)
    }

    @Test
    fun theFringeFadesFromTheShapesAlphaToNothingAndKeepsItsColour() {
        val f = PolygonFringe.forTriangles(triangle(0f, 0f, 100f, 0f, 0f, 100f), intArrayOf(red, red, red))
        for (c in f.colorsArgb) {
            assertEquals("colour keeps its rgb", 0x00FF0000, c and 0x00FFFFFF)
            assertTrue("alpha is the shape's or zero", alphaOf(c) == 255 || alphaOf(c) == 0)
        }
        assertTrue(f.colorsArgb.any { alphaOf(it) == 255 })
        assertTrue(f.colorsArgb.any { alphaOf(it) == 0 })
    }

    @Test
    fun aSemiTransparentShapeFadesFromItsOwnAlpha() {
        val half = 0x80FF0000.toInt()
        val f = PolygonFringe.forTriangles(triangle(0f, 0f, 100f, 0f, 0f, 100f), intArrayOf(half, half, half))
        assertTrue(f.colorsArgb.any { alphaOf(it) == 0x80 })
        assertFalse(f.colorsArgb.any { alphaOf(it) > 0x80 })
    }

    @Test
    fun everyOuterVertexIsOnePixelOutsideItsEdgeAndNeverInsideTheShape() {
        // Triangle (0,0) (100,0) (0,100): the interior is the side towards (+x, +y).
        val f = PolygonFringe.forTriangles(triangle(0f, 0f, 100f, 0f, 0f, 100f), intArrayOf(red, red, red), widthPx = 1f)
        for (i in f.colorsArgb.indices) {
            if (alphaOf(f.colorsArgb[i]) != 0) continue
            val x = f.xy[i * 2]; val y = f.xy[i * 2 + 1]
            // Outside means on the far side of at least one edge line (wedge points can lie beyond two of them).
            val outsideAny = side(0f, 0f, 100f, 0f, x, y) < -0.999f ||        // below the bottom edge (y < 0)
                side(100f, 0f, 0f, 100f, x, y) < -0.999f ||                   // beyond the hypotenuse
                side(0f, 100f, 0f, 0f, x, y) < -0.999f                        // left of the left edge (x < 0)
            assertTrue("outer vertex ($x,$y)", outsideAny)
        }
    }

    @Test
    fun theOutwardDirectionDoesNotDependOnTheWindingOfTheTriangle() {
        val ccw = PolygonFringe.forTriangles(triangle(0f, 0f, 100f, 0f, 0f, 100f), intArrayOf(red, red, red))
        val cw = PolygonFringe.forTriangles(triangle(0f, 0f, 0f, 100f, 100f, 0f), intArrayOf(red, red, red))
        for (f in listOf(ccw, cw)) {
            for (i in f.colorsArgb.indices) {
                if (alphaOf(f.colorsArgb[i]) != 0) continue
                val x = f.xy[i * 2]; val y = f.xy[i * 2 + 1]
                val inside = x > 0.001f && y > 0.001f && x + y < 99.999f
                assertFalse("outer vertex ($x,$y) must not be inside the triangle", inside)
            }
        }
    }

    @Test
    fun anEdgeSharedByTwoTrianglesIsNotFringed() {
        // A 100x100 square as two triangles: 4 boundary edges, the diagonal is interior.
        val sq = triangle(0f, 0f, 100f, 0f, 100f, 100f,   0f, 0f, 100f, 100f, 0f, 100f)
        val f = PolygonFringe.forTriangles(sq, IntArray(6) { red })
        // 4 strips x 6 vertices + 4 corner wedges x 3 vertices; no strip along the diagonal.
        assertEquals(4 * 6 + 4 * 3, f.colorsArgb.size)
        // No outer vertex sits near the diagonal's middle.
        for (i in f.colorsArgb.indices) {
            if (alphaOf(f.colorsArgb[i]) != 0) continue
            val x = f.xy[i * 2]; val y = f.xy[i * 2 + 1]
            assertFalse("($x,$y) near the diagonal", abs(x - y) < 0.5f && x in 10f..90f)
        }
    }

    @Test
    fun aFanOfARectangleHasTheSameFringeAsItsTwoTriangles() {
        val rect = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val fan = PolygonFringe.forFan(rect, IntArray(4) { red })
        val tris = PolygonFringe.forTriangles(
            triangle(0f, 0f, 100f, 0f, 100f, 100f,   0f, 0f, 100f, 100f, 0f, 100f), IntArray(6) { red })
        assertEquals(tris.colorsArgb.size, fan.colorsArgb.size)
    }

    @Test
    fun aStraightRunOfEdgesNeedsNoWedgeBetweenThem() {
        // A 100x50 rectangle split into four triangles through (50,0) and (50,50): the bottom and the top are each two
        // collinear boundary edges, so the vertices (50,0) and (50,50) need no corner wedge, only the four real corners.
        val tris = triangle(
            0f, 0f, 50f, 0f, 50f, 50f,
            0f, 0f, 50f, 50f, 0f, 50f,
            50f, 0f, 100f, 0f, 100f, 50f,
            50f, 0f, 100f, 50f, 50f, 50f
        )
        val f = PolygonFringe.forTriangles(tris, IntArray(12) { red })
        // 6 boundary edges (2 bottom, 2 top, 1 left, 1 right) x 6 vertices + 4 corner wedges x 3 vertices.
        assertEquals(6 * 6 + 4 * 3, f.colorsArgb.size)
    }

    @Test
    fun degenerateAndBrokenInputsGiveNothingAndNeverThrow() {
        assertTrue(PolygonFringe.forTriangles(FloatArray(0), IntArray(0)).isEmpty)
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, 10f, 10f, 20f, 20f), IntArray(3) { red }).isEmpty)        // collinear
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, 10f, 0f), IntArray(2) { red }).isEmpty)                   // not a triangle
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, Float.NaN, 0f, 0f, 10f), IntArray(3) { red }).isEmpty)
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, 10f, 0f, 0f, 10f), IntArray(3) { red }, widthPx = 0f).isEmpty)
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, 10f, 0f, 0f, 10f), IntArray(3) { red }, widthPx = Float.NaN).isEmpty)
        assertTrue(PolygonFringe.forFan(floatArrayOf(0f, 0f, 1f, 1f), IntArray(2) { red }).isEmpty)
        assertTrue(PolygonFringe.forTriangles(triangle(0f, 0f, 10f, 0f, 0f, 10f), IntArray(2) { red }).isEmpty)          // too few colours
    }

    @Test
    fun theWidthScalesTheStrip() {
        val f2 = PolygonFringe.forTriangles(triangle(0f, 0f, 100f, 0f, 0f, 100f), IntArray(3) { red }, widthPx = 2f)
        var maxOutside = 0f
        for (i in f2.colorsArgb.indices) {
            if (alphaOf(f2.colorsArgb[i]) == 0) maxOutside = maxOf(maxOutside, -f2.xy[i * 2 + 1])   // how far below y = 0
        }
        assertEquals(2f, maxOutside, 1e-3f)
    }

    @Test
    fun verticesThatDifferByFloatNoiseStillCountAsTheSameVertex() {
        // Two triangles sharing an edge whose endpoints differ by 0.001 px: still one shared edge (not fringed).
        val tris = triangle(0f, 0f, 100f, 0f, 100f, 100f,   0.001f, 0.001f, 100.001f, 100f, 0f, 100f)
        val f = PolygonFringe.forTriangles(tris, IntArray(6) { red })
        assertEquals(4 * 6 + 4 * 3, f.colorsArgb.size)
    }
}
