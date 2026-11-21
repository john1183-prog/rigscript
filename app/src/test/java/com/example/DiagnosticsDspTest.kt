package com.example

import com.example.engine.DiagnosticsDsp
import com.example.engine.GlesAcceptance
import com.example.engine.GrayImage
import com.example.engine.PixelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** WP1.6a: the pure DSP and image-comparison math behind the Self-test and the quick GLES check. */
class DiagnosticsDspTest {

    private val rate = 44100

    /** Exact zero without `assertEquals(double, double)`, which real JUnit 4 rejects. */
    private fun zero(actual: Double) {
        assertTrue("expected 0.0 but was $actual", actual == 0.0)
    }

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected +- $tolerance but was $actual", abs(expected - actual) <= tolerance)
    }

    // ------------------------------------------------------------------------------------------------- audio

    private class Tone(val freq: Double, val amplitude: Double, val startSec: Double = 0.0, val endSec: Double = Double.MAX_VALUE)

    private fun render(seconds: Double, vararg tones: Tone): ShortArray {
        val n = (seconds * rate).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var v = 0.0
            for (tone in tones) if (t >= tone.startSec && t < tone.endSec) v += tone.amplitude * sin(2 * PI * tone.freq * t)
            out[i] = (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private val cues = listOf(1.0, 2.5, 4.0)

    private fun bursts(at: List<Double> = cues): Array<Tone> = at.map { Tone(1000.0, 0.5, it, it + 0.1) }.toTypedArray()

    @Test
    fun goertzelReadsTheAmplitudeOfASine() {
        val s = render(6.0, Tone(440.0, 0.5))
        near("440 Hz", 0.5, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 440.0), 0.01)
        near("220 Hz", 0.0, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 220.0), 0.003)
        near("1 kHz", 0.0, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 1000.0), 0.003)
        near("rectangular window", 0.5, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 440.0, hann = false), 0.005)
    }

    @Test
    fun goertzelSeparatesTonesAtDifferentFrequencies() {
        val s = render(6.0, Tone(440.0, 0.4), Tone(220.0, 0.1), Tone(1000.0, 0.2))
        near("narration", 0.4, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 440.0), 0.01)
        near("music", 0.1, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 220.0), 0.01)
        near("sfx", 0.2, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 1000.0), 0.01)
        near("nothing at 660 Hz", 0.0, DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 660.0), 0.003)
    }

    @Test
    fun goertzelIsFailSoftOnDegenerateInput() {
        val s = render(1.0, Tone(440.0, 0.5))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 100, 100, rate, 440.0))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 500, 100, rate, 440.0))
        zero(DiagnosticsDsp.goertzelAmplitude(ShortArray(0), 0, 10, rate, 440.0))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, 0.0))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, -5.0))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, rate / 2.0))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, rate, Double.NaN))
        zero(DiagnosticsDsp.goertzelAmplitude(s, 0, s.size, 0, 440.0))
        // a range that runs past both ends is clamped, not an error
        assertTrue(DiagnosticsDsp.goertzelAmplitude(s, -1000, s.size + 1000, rate, 440.0) > 0.4)
        zero(DiagnosticsDsp.goertzelAmplitude(ShortArray(8), 0, 8, rate, 440.0))
    }

    @Test
    fun rmsOfASineIsAmplitudeOverRootTwo() {
        val s = render(1.0, Tone(440.0, 0.5))
        near("rms", 0.5 / sqrt(2.0), DiagnosticsDsp.rms(s, 0, s.size), 0.005)
        zero(DiagnosticsDsp.rms(s, 10, 10))
        zero(DiagnosticsDsp.rms(ShortArray(0), 0, 5))
    }

    @Test
    fun toneInWindowsSeesEveryCueAndNothingElse() {
        val s = render(6.0, Tone(440.0, 0.4), *bursts())
        val t = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, cues)
        assertTrue("weakest cue window ${t.insideMin}", t.insideMin > 0.1)
        assertTrue("background ${t.outside}", t.outside < 0.005)
        assertTrue("ratio ${t.ratio()}", t.ratio() > 20.0)
        assertTrue(t.insideMean >= t.insideMin)
    }

    @Test
    fun toneInWindowsShowsAMissingCue() {
        val s = render(6.0, Tone(440.0, 0.4), *bursts(listOf(1.0, 4.0)))
        val t = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, cues)
        assertTrue("the missing 2.5 s cue must pull the minimum down: ${t.insideMin}", t.insideMin < 0.01)
        assertTrue("the two present cues keep the mean up: ${t.insideMean}", t.insideMean > 0.05)
        assertTrue(t.ratio() < GlesAcceptance.MIN_SFX_CUE_RATIO)
    }

    @Test
    fun toneInWindowsReadsNothingWhenThereIsNoSfx() {
        val s = render(6.0, Tone(440.0, 0.4))
        val t = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, cues)
        assertTrue(t.insideMin < 0.005)
        assertTrue(t.outside < 0.005)
    }

    @Test
    fun toneInWindowsClampsACueNearTheStartAndIgnoresNonFiniteTimes() {
        val s = render(2.0, Tone(1000.0, 0.5, 0.0, 0.1))
        val early = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, listOf(0.02))
        assertTrue("a window that starts before 0 is clamped: ${early.insideMin}", early.insideMin > 0.1)
        val withJunk = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, listOf(Double.NaN, 0.02, Double.POSITIVE_INFINITY))
        near("junk cues are ignored", early.insideMin, withJunk.insideMin, 1e-9)
        val none = DiagnosticsDsp.toneInWindows(s, rate, 1000.0, listOf(Double.NaN))
        zero(none.insideMin)
        zero(none.outside)
        zero(DiagnosticsDsp.toneInWindows(ShortArray(0), rate, 1000.0, cues).insideMin)
    }

    @Test
    fun downmixAveragesChannelsAndDropsAPartialFrame() {
        val stereo = shortArrayOf(100, 300, -200, -400, 7)
        val mono = DiagnosticsDsp.downmixToMono(stereo, 2)
        assertEquals(2, mono.size)
        assertEquals(200.toShort(), mono[0])
        assertEquals((-300).toShort(), mono[1])
        val same = shortArrayOf(1, 2, 3)
        assertTrue(DiagnosticsDsp.downmixToMono(same, 1) === same)
    }

    // ------------------------------------------------------------------------------------------------- images

    /** Shapes on white, shifted by (ox, oy) and supersampled ss x ss: a dark disc, a gray bar and a gray triangle. */
    private fun scene(ox: Double, oy: Double, ss: Int = 8, size: Int = 128): GrayImage {
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var acc = 0.0
                for (sy in 0 until ss) {
                    for (sx in 0 until ss) {
                        acc += colorAt(x + (sx + 0.5) / ss - ox, y + (sy + 0.5) / ss - oy)
                    }
                }
                out[y * size + x] = (acc / (ss * ss)).toFloat()
            }
        }
        return GrayImage(size, size, out)
    }

    private fun colorAt(px: Double, py: Double): Double {
        val dx = px - 44.0
        val dy = py - 46.0
        if (dx * dx + dy * dy <= 18.0 * 18.0) return 40.0
        if (px >= 70.0 && px <= 110.0 && py >= 30.0 && py <= 37.0) return 90.0
        if (inTriangle(px, py)) return 120.0
        return 255.0
    }

    private fun inTriangle(px: Double, py: Double): Boolean {
        fun e(ax: Double, ay: Double, bx: Double, by: Double) = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
        val a = e(30.0, 112.0, 78.0, 112.0)
        val b = e(78.0, 112.0, 54.0, 76.0)
        val c = e(54.0, 76.0, 30.0, 112.0)
        return (a >= 0 && b >= 0 && c >= 0) || (a <= 0 && b <= 0 && c <= 0)
    }

    private fun boxBlur(img: GrayImage, radius: Int): GrayImage {
        val w = img.width
        val h = img.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) {
                            sum += img.data[yy * w + xx]
                            n++
                        }
                    }
                }
                out[y * w + x] = sum / n
            }
        }
        return GrayImage(w, h, out)
    }

    private val whole = PixelBox(0, 0, 128, 128)

    @Test
    fun fromArgbUsesRec601Luma() {
        val img = GrayImage.fromArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 2, 2)
        near("white", 255.0, img[0, 0].toDouble(), 0.01)
        near("black", 0.0, img[1, 0].toDouble(), 0.01)
        near("red", 76.245, img[0, 1].toDouble(), 0.01)
        near("green", 149.685, img[1, 1].toDouble(), 0.01)
    }

    @Test
    fun meanAbsDiffIsMeasuredInsideTheBoxOnly() {
        val a = GrayImage(10, 10, FloatArray(100) { 100f })
        val bData = FloatArray(100) { 100f }
        for (y in 2 until 4) for (x in 2 until 4) bData[y * 10 + x] = 110f
        val b = GrayImage(10, 10, bData)
        near("inside", 10.0, DiagnosticsDsp.meanAbsDiff(a, b, PixelBox(2, 2, 4, 4)), 1e-9)
        near("whole", 0.4, DiagnosticsDsp.meanAbsDiff(a, b, PixelBox(0, 0, 10, 10)), 1e-9)
        near("outside", 0.0, DiagnosticsDsp.meanAbsDiff(a, b, PixelBox(5, 5, 9, 9)), 1e-9)
        assertTrue(DiagnosticsDsp.meanAbsDiff(a, b, PixelBox(5, 5, 5, 9)).isNaN())
        assertTrue(DiagnosticsDsp.meanAbsDiff(a, GrayImage(5, 5, FloatArray(25)), whole).isNaN())
    }

    @Test
    fun offsetEstimatorFindsWholePixelShiftsWithTheRightSign() {
        val reference = scene(0.0, 0.0)
        val same = DiagnosticsDsp.estimateOffset(reference, reference, whole)
        assertTrue(same.reliable)
        near("no shift", 0.0, same.magnitude, 0.02)
        val moved = DiagnosticsDsp.estimateOffset(reference, scene(2.0, -1.0), whole)
        assertTrue(moved.reliable)
        near("content 2 px to the right", 2.0, moved.dx, 0.1)
        near("content 1 px up", -1.0, moved.dy, 0.1)
    }

    @Test
    fun offsetEstimatorResolvesSubPixelShifts() {
        val reference = scene(0.0, 0.0)
        val half = DiagnosticsDsp.estimateOffset(reference, scene(0.5, 0.0), whole)
        near("half a pixel", 0.5, half.dx, 0.2)
        near("no vertical part", 0.0, half.dy, 0.15)
        // R1: a vertical offset of about 1.75 px must read as far above the 0.75 px limit
        val r1 = DiagnosticsDsp.estimateOffset(reference, scene(0.0, 1.75), whole)
        near("R1 offset", 1.75, r1.dy, 0.25)
        assertTrue("R1 magnitude ${r1.magnitude}", r1.magnitude > 1.4)
        // a 0.3 px wobble stays under the limit
        val small = DiagnosticsDsp.estimateOffset(reference, scene(0.0, 0.3), whole)
        assertTrue("0.3 px reads as ${small.magnitude}", small.magnitude < 0.6)
    }

    @Test
    fun offsetEstimatorRestrictedToABoxStillFindsTheShift() {
        val figure = PixelBox(20, 20, 70, 70)
        val e = DiagnosticsDsp.estimateOffset(scene(0.0, 0.0), scene(0.0, 1.0), figure)
        assertTrue(e.reliable)
        near("dy", 1.0, e.dy, 0.15)
    }

    @Test
    fun offsetEstimatorHitsTheRangeLimitOnBigShifts() {
        val e = DiagnosticsDsp.estimateOffset(scene(0.0, 0.0), scene(0.0, 5.0), whole)
        assertTrue("a 5 px shift must read as at least the 3 px range: ${e.magnitude}", !e.reliable || e.magnitude >= 2.5)
    }

    @Test
    fun offsetEstimatorSaysNothingAboutAFlatImageOrMismatchedSizes() {
        val flat = GrayImage(64, 64, FloatArray(64 * 64) { 200f })
        assertFalse(DiagnosticsDsp.estimateOffset(flat, flat, PixelBox(0, 0, 64, 64)).reliable)
        assertFalse(DiagnosticsDsp.estimateOffset(scene(0.0, 0.0), flat, whole).reliable)
        assertFalse(DiagnosticsDsp.estimateOffset(scene(0.0, 0.0), scene(0.0, 0.0), PixelBox(0, 0, 4, 4)).reliable)
    }

    @Test
    fun parabolaPeakFindsTheVertex() {
        near("symmetric", 0.0, DiagnosticsDsp.parabolaPeak(0.8, 1.0, 0.8), 1e-12)
        assertTrue(DiagnosticsDsp.parabolaPeak(0.8, 1.0, 0.9) > 0.1)
        assertTrue(DiagnosticsDsp.parabolaPeak(0.9, 1.0, 0.8) < -0.1)
        near("flat", 0.0, DiagnosticsDsp.parabolaPeak(1.0, 1.0, 1.0), 1e-12)
        near("valley", 0.0, DiagnosticsDsp.parabolaPeak(1.0, 0.5, 1.0), 1e-12)
        assertTrue(abs(DiagnosticsDsp.parabolaPeak(0.0, 0.9, 0.9)) <= 0.5)
    }

    @Test
    fun laplacianRatioFallsWithBlurAndIsOneForIdenticalImages() {
        val canvas = scene(0.0, 0.0)
        near("identical", 1.0, DiagnosticsDsp.laplacianRatio(canvas, canvas, whole), 1e-9)
        val soft = DiagnosticsDsp.laplacianRatio(canvas, boxBlur(canvas, 1), whole)
        val softer = DiagnosticsDsp.laplacianRatio(canvas, boxBlur(canvas, 2), whole)
        assertTrue("one pixel of blur must fall below 0.90: $soft", soft < GlesAcceptance.MIN_LAPLACIAN_RATIO)
        assertTrue("more blur is softer still: $softer vs $soft", softer < soft)
    }

    @Test
    fun laplacianRatioIsNaNWithoutDetailOrWithMismatchedSizes() {
        val flat = GrayImage(128, 128, FloatArray(128 * 128) { 255f })
        assertTrue(DiagnosticsDsp.laplacianRatio(flat, scene(0.0, 0.0), whole).isNaN())
        assertTrue(DiagnosticsDsp.laplacianRatio(scene(0.0, 0.0), flat.let { GrayImage(8, 8, FloatArray(64)) }, whole).isNaN())
        assertTrue(DiagnosticsDsp.laplacianVariance(scene(0.0, 0.0), PixelBox(0, 0, 1, 1)).isNaN())
    }

    @Test
    fun edgeAaCountSeesAntiAliasedEdgesButNotHardOnes() {
        val hard = scene(0.0, 0.0, ss = 1)
        val smooth = scene(0.0, 0.0, ss = 8)
        assertEquals(0, DiagnosticsDsp.edgeAaCount(hard, whole))
        assertTrue(
            "anti-aliased edges: ${DiagnosticsDsp.edgeAaCount(smooth, whole)}",
            DiagnosticsDsp.edgeAaCount(smooth, whole) > 4 * DiagnosticsDsp.AA_MIN_CANVAS_PIXELS
        )
        assertEquals(0, DiagnosticsDsp.edgeAaCount(GrayImage(32, 32, FloatArray(1024) { 128f }), PixelBox(0, 0, 32, 32)))
    }

    @Test
    fun edgeAaRatioComparesGlesAgainstCanvas() {
        val smooth = scene(0.0, 0.0, ss = 8)
        val hard = scene(0.0, 0.0, ss = 1)
        near("same", 1.0, DiagnosticsDsp.edgeAaRatio(smooth, smooth, whole), 1e-9)
        val aliased = DiagnosticsDsp.edgeAaRatio(smooth, hard, whole)
        assertTrue("aliased GLES against smooth Canvas: $aliased", aliased < GlesAcceptance.MIN_EDGE_AA_RATIO)
        assertTrue("with no Canvas edge pixels there is nothing to compare against", DiagnosticsDsp.edgeAaRatio(hard, smooth, whole).isNaN())
    }

    @Test
    fun inkRatioComparesMarksAgainstTheBackground() {
        fun mouth(contrast: Float): GrayImage {
            val d = FloatArray(40 * 20) { 230f }
            for (y in 8 until 12) for (x in 10 until 30) d[y * 40 + x] = 230f - contrast
            return GrayImage(40, 20, d)
        }
        val box = PixelBox(0, 0, 40, 20)
        near("same ink", 1.0, DiagnosticsDsp.inkRatio(mouth(120f), mouth(120f), box), 1e-9)
        near("half the contrast", 0.5, DiagnosticsDsp.inkRatio(mouth(120f), mouth(60f), box), 1e-6)
        near("missing mouth", 0.0, DiagnosticsDsp.inkRatio(mouth(120f), mouth(0f), box), 1e-9)
        assertTrue(DiagnosticsDsp.inkRatio(mouth(0f), mouth(120f), box).isNaN())
    }

    @Test
    fun compareFramesPassesIdenticalFramesAndFlagsAShift() {
        val canvas = scene(0.0, 0.0)
        val figure = PixelBox(10, 10, 118, 118)
        val text = PixelBox(10, 10, 118, 118)
        val same = DiagnosticsDsp.compareFrames(canvas, canvas, figure, text, PixelBox(26, 28, 62, 64))
        near("offset", 0.0, same.offsetPx, 0.02)
        near("difference", 0.0, same.meanAbsDiff, 1e-9)
        near("sharpness", 1.0, same.laplacianRatio, 1e-9)
        near("anti-aliasing", 1.0, same.edgeAaRatio, 1e-9)
        near("mouth ink", 1.0, same.mouthInkRatio, 1e-9)

        val shifted = DiagnosticsDsp.compareFrames(canvas, scene(0.0, 1.75), figure, text)
        assertTrue("shifted offset ${shifted.offsetPx}", shifted.offsetPx > GlesAcceptance.MAX_OFFSET_PX)
        assertTrue("shifted difference ${shifted.meanAbsDiff}", shifted.meanAbsDiff > GlesAcceptance.MAX_MEAN_ABS_DIFF)
        assertTrue(shifted.mouthInkRatio.isNaN())

        val mismatch = DiagnosticsDsp.compareFrames(canvas, scene(0.0, 0.0, size = 64), figure, text)
        assertTrue(mismatch.offsetPx.isNaN() && mismatch.meanAbsDiff.isNaN() && mismatch.laplacianRatio.isNaN() && mismatch.edgeAaRatio.isNaN())
    }

    // ------------------------------------------------------------------- the DSP against the acceptance rules

    private fun cleanAudio(expected: Set<GlesAcceptance.Source>) = GlesAcceptance.AudioMetrics(
        hasAudioTrack = true, durationSec = 6.0, expectedDurationSec = 6.0,
        narrationAmplitude = if (GlesAcceptance.Source.NARRATION in expected) 0.4 else 0.0,
        musicAmplitude = if (GlesAcceptance.Source.MUSIC in expected) 0.1 else 0.0,
        sfxAmplitude = if (GlesAcceptance.Source.SFX in expected) 0.05 else 0.0,
        sfxCueMin = if (GlesAcceptance.Source.SFX in expected) 0.2 else 0.0,
        sfxOutside = 0.0
    )

    private fun verdictFor(glesScene: () -> GrayImage): GlesAcceptance.Evaluation {
        val canvas = scene(0.0, 0.0)
        val frames = List(GlesAcceptance.REQUIRED_FRAMES) {
            DiagnosticsDsp.compareFrames(canvas, glesScene(), PixelBox(10, 10, 118, 118), PixelBox(10, 10, 118, 118))
        }
        return GlesAcceptance.evaluate(
            GlesAcceptance.CheckMetrics(
                glesFailure = null,
                mixOnGles = cleanAudio(GlesAcceptance.ALL_SOURCES),
                sfxOnlyOnGles = cleanAudio(setOf(GlesAcceptance.Source.SFX)),
                canvasDurationSec = 6.0,
                glesDurationSec = 6.0,
                frames = frames
            )
        )
    }

    @Test
    fun identicalFramesAreEligibleAndAnR1ShiftIsNot() {
        val ok = verdictFor { scene(0.0, 0.0) }
        assertTrue(ok.failures.joinToString(), ok.pass)
        val r1 = verdictFor { scene(0.0, 1.75) }
        assertFalse(r1.pass)
        assertTrue(r1.failures.joinToString(), r1.failures.any { it.contains("offset") })
    }

    @Test
    fun aBlurryOrAliasedGlesFrameIsNotEligible() {
        val blurry = verdictFor { boxBlur(scene(0.0, 0.0), 1) }
        assertFalse(blurry.pass)
        assertTrue(blurry.failures.joinToString(), blurry.failures.any { it.contains("sharpness") })
        val aliased = verdictFor { scene(0.0, 0.0, ss = 1) }
        assertFalse(aliased.pass)
        assertTrue(aliased.failures.joinToString(), aliased.failures.any { it.contains("anti-aliasing") })
    }
}
