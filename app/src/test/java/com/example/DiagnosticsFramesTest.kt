package com.example

import com.example.engine.DiagnosticsFrames
import com.example.engine.PixelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.6b: locating the figure and the text in a decoded frame by colour. */
class DiagnosticsFramesTest {

    private val sky = 0xFF7A4B2A.toInt()
    private val ground = 0xFF2E5E3A.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFD500.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val eyeDark = 0xFF0D0D14.toInt()

    private fun frame(w: Int, h: Int, background: Int = sky): IntArray = IntArray(w * h) { background }

    private fun fill(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = color
    }

    @Test
    fun theFixtureColoursAreNeverMistakenForEachOther() {
        for (c in listOf(sky, ground, white, eyeDark, 0x99000000.toInt(), yellow)) assertFalse(DiagnosticsFrames.isFigurePixel(c))
        for (c in listOf(sky, ground, white, eyeDark, blue)) assertFalse(DiagnosticsFrames.isTextPixel(c))
        assertTrue(DiagnosticsFrames.isFigurePixel(blue))
        assertTrue(DiagnosticsFrames.isTextPixel(yellow))
    }

    @Test
    fun coloursSurviveAnH264RoundTripWithinTheTolerance() {
        assertTrue(DiagnosticsFrames.isFigurePixel(0xFF0A1CF0.toInt()))
        assertTrue(DiagnosticsFrames.isTextPixel(0xFFFAC81E.toInt()))
    }

    @Test
    fun theFigureBoxIsTightAroundTheBlueAndPadded() {
        val w = 100
        val h = 200
        val px = frame(w, h)
        fill(px, w, 0, 150, w, h, ground)
        fill(px, w, 20, 40, 50, 100, blue)
        val box = DiagnosticsFrames.figureBox(px, w, h)
        assertNotNull(box)
        assertEquals(PixelBox(16, 36, 54, 104), box)
    }

    @Test
    fun aBoxIsClippedAtTheFrameEdge() {
        val w = 60
        val h = 60
        val px = frame(w, h)
        fill(px, w, 0, 0, 10, 10, blue)
        assertEquals(PixelBox(0, 0, 14, 14), DiagnosticsFrames.figureBox(px, w, h))
        val px2 = frame(w, h)
        fill(px2, w, 50, 50, 60, 60, yellow)
        assertEquals(PixelBox(46, 46, 60, 60), DiagnosticsFrames.textBox(px2, w, h))
    }

    @Test
    fun theTextBoxIgnoresTheFigureAndViceVersa() {
        val w = 120
        val h = 120
        val px = frame(w, h)
        fill(px, w, 10, 10, 40, 30, yellow)
        fill(px, w, 60, 60, 80, 110, blue)
        assertEquals(PixelBox(6, 6, 44, 34), DiagnosticsFrames.textBox(px, w, h))
        assertEquals(PixelBox(56, 56, 84, 114), DiagnosticsFrames.figureBox(px, w, h))
    }

    @Test
    fun tooFewMatchingPixelsMeansNotFound() {
        val w = 50
        val h = 50
        val px = frame(w, h)
        // 5 stray pixels (a compression artefact) are not a figure.
        for (i in 0 until 5) px[i * w + i] = blue
        assertNull(DiagnosticsFrames.figureBox(px, w, h))
        assertNull(DiagnosticsFrames.textBox(px, w, h))
        // Exactly the minimum is found.
        val px2 = frame(w, h)
        fill(px2, w, 0, 0, 8, 5, blue) // 40 pixels
        assertNotNull(DiagnosticsFrames.figureBox(px2, w, h))
    }

    @Test
    fun badInputGivesNullNotAnException() {
        assertNull(DiagnosticsFrames.figureBox(IntArray(0), 0, 0))
        assertNull(DiagnosticsFrames.figureBox(IntArray(10), 100, 100))
        assertNull(DiagnosticsFrames.textBox(IntArray(10), -1, 5))
    }

    @Test
    fun theMouthRegionIsTheTopOfTheFigureBox() {
        val figure = PixelBox(16, 36, 54, 104) // height 68
        val mouth = DiagnosticsFrames.mouthRegion(figure)
        assertEquals(16, mouth.left)
        assertEquals(36, mouth.top)
        assertEquals(54, mouth.right)
        assertEquals(36 + 14, mouth.bottom)
        // A very flat box still yields a non-empty region inside it.
        val flat = DiagnosticsFrames.mouthRegion(PixelBox(0, 10, 30, 13))
        assertFalse(flat.isEmpty)
        assertTrue(flat.bottom <= 13)
    }
}
