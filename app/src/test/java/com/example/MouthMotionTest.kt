package com.example

import com.example.engine.FaceGeometry
import com.example.engine.MouthShape
import com.example.engine.ResolvedFace
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingParams
import com.example.engine.acting.MouthTrack
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * WP2.4 (speech mouth): the baked track is pinned to the Python prototype (`tools/acting_proto/mouth_proto.py`), plus the
 * brief's acceptance list: no flicker on a noisy synthetic envelope, ranges bounded, determinism, OFF equals the legacy
 * mapping; and the I2 rule (seek equals sequential) through the real engine.
 */
class MouthMotionTest {

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected, got $actual (tolerance $tolerance)", abs(expected - actual) <= tolerance)
    }

    // Reference envelope (4 s, 3 decimals so float32 holds the same value in Python and here) and classes, from
    // `python3 mouth_proto.py --emit-reference`.
    private val refEnvelope = floatArrayOf(
        0.357f,0.662f,0.662f,0.357f,0.180f,0.364f,0.453f,0.453f,0.364f,0.180f,0.046f,0.004f,0.006f,0.047f,0.031f,
        0.018f,0.303f,0.613f,0.763f,0.763f,0.613f,0.303f,0.228f,0.447f,0.519f,0.447f,0.228f,0.034f,0.026f,0.041f,
        0.027f,0.049f,0.010f,0.028f,0.024f,0.018f,0.030f,0.012f,0.040f,0.043f,0.349f,0.647f,0.647f,0.349f,0.216f,
        0.424f,0.492f,0.424f,0.216f,0.232f,0.478f,0.618f,0.665f,0.618f,0.478f,0.232f,0.402f,0.744f,0.744f,0.402f,
        0.045f,0.011f,0.002f,0.010f,0.017f,0.023f,0.045f,0.241f,0.486f,0.605f,0.605f,0.486f,0.241f,0.271f,0.501f,
        0.501f,0.271f,0.237f,0.488f,0.632f,0.680f,0.632f,0.488f,0.237f,0.003f,0.002f,0.042f,0.029f,0.015f,0.016f,
        0.004f,0.009f,0.001f,0.042f,0.023f,0.006f,0.213f,0.430f,0.535f,0.535f,0.430f,0.213f,0.382f,0.709f,0.709f,
        0.382f,0.162f,0.333f,0.431f,0.463f,0.431f,0.333f,0.162f,0.010f,0.005f,0.001f,0.015f,0.036f,0.025f,0.043f
    )
    private val refClasses = intArrayOf(
        1,1,1,1,1,1,1,1,1,1,0,0,0,0,0,0,3,3,3,3,3,3,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,3,3,3,3,3,2,2,2,2,2,2,
        2,2,2,2,2,0,0,0,0,0,0,0,3,3,3,3,3,3,2,2,2,2,3,3,3,3,3,3,3,0,0,0,0,0,0,0,0,0,0,0,0,3,3,3,3,3,3,3,3,3,3,1,1,1,1,
        1,1,1,0,0,0,0,0,0,0
    )

    // Rows: sample index i (time = i / 64 s), open, width, round.
    private val refRows = arrayOf(
        doubleArrayOf(0.0, 0.000000, 0.857143, 0.000000),
        doubleArrayOf(5.0, 0.305118, 0.753225, 0.000000),
        doubleArrayOf(10.0, 0.291682, 0.724872, 0.000000),
        doubleArrayOf(15.0, 0.293588, 0.717162, 0.000000),
        doubleArrayOf(20.0, 0.282128, 0.715069, 0.000000),
        doubleArrayOf(25.0, 0.227448, 0.714498, 0.000000),
        doubleArrayOf(30.0, 0.099058, 0.811698, 0.000000),
        doubleArrayOf(35.0, 0.113764, 0.816175, 0.000000),
        doubleArrayOf(40.0, 0.225523, 0.297820, 0.000000),
        doubleArrayOf(45.0, 0.230895, 0.081177, 0.000000),
        doubleArrayOf(50.0, 0.225094, 0.175155, 0.000000),
        doubleArrayOf(55.0, 0.297033, 0.567783, 0.000000),
        doubleArrayOf(60.0, 0.245404, 0.674387, 0.000000),
        doubleArrayOf(65.0, 0.127904, 0.783064, 0.000000),
        doubleArrayOf(70.0, 0.051035, 0.836983, 0.000000),
        doubleArrayOf(75.0, 0.020361, 0.851657, 0.000000),
        doubleArrayOf(80.0, 0.008112, 0.855654, 0.000000),
        doubleArrayOf(85.0, 0.000000, 0.856737, 0.000000),
        doubleArrayOf(90.0, 0.293442, 0.756629, 0.000000),
        doubleArrayOf(95.0, 0.295054, 0.725792, 0.000000),
        doubleArrayOf(100.0, 0.234902, 0.255789, 0.000000),
        doubleArrayOf(105.0, 0.187640, 0.069474, 0.000000),
        doubleArrayOf(110.0, 0.623087, 0.515614, 0.027645),
        doubleArrayOf(115.0, 0.779037, 0.764211, 0.110190),
        doubleArrayOf(120.0, 0.697375, 0.831926, 0.048458),
        doubleArrayOf(125.0, 0.822251, 0.850270, 0.156170),
        doubleArrayOf(130.0, 0.687671, 0.855274, 0.086476),
        doubleArrayOf(135.0, 0.314313, 0.826721, 0.036287),
        doubleArrayOf(140.0, 0.125457, 0.848858, 0.015241),
        doubleArrayOf(145.0, 0.175014, 0.795527, 0.006394),
        doubleArrayOf(150.0, 0.208156, 0.220121, 0.002685),
        doubleArrayOf(155.0, 0.192376, 0.059896, 0.001127),
        doubleArrayOf(160.0, 0.513424, 0.381607, 0.000860),
        doubleArrayOf(165.0, 0.623579, 0.727525, 0.000597),
        doubleArrayOf(170.0, 0.417363, 0.301045, 0.000250),
        doubleArrayOf(175.0, 0.301579, 0.081806, 0.000105),
        doubleArrayOf(180.0, 0.210069, 0.022279, 0.000044),
        doubleArrayOf(185.0, 0.138058, 0.492058, 0.000019),
        doubleArrayOf(190.0, 0.055087, 0.757786, 0.000008),
        doubleArrayOf(195.0, 0.021978, 0.830107, 0.000003),
        doubleArrayOf(200.0, 0.008756, 0.849807, 0.000001),
        doubleArrayOf(205.0, 0.018667, 0.848196, 0.000001),
        doubleArrayOf(210.0, 0.179560, 0.358395, 0.000000),
        doubleArrayOf(215.0, 0.193649, 0.097390, 0.000000),
        doubleArrayOf(220.0, 0.181310, 0.026523, 0.000000),
        doubleArrayOf(225.0, 0.218594, 0.007204, 0.000000),
        doubleArrayOf(230.0, 0.228982, 0.260626, 0.000000),
        doubleArrayOf(235.0, 0.291810, 0.590842, 0.000000),
        doubleArrayOf(240.0, 0.273729, 0.680790, 0.000000),
        doubleArrayOf(245.0, 0.220180, 0.705156, 0.000000),
        doubleArrayOf(250.0, 0.092372, 0.812207, 0.000000),
        doubleArrayOf(255.0, 0.036830, 0.844932, 0.000000)
    )

    private fun at(track: MouthTrack, t: Double): DoubleArray {
        val o = DoubleArray(3)
        track.evaluate(t, o)
        return o
    }

    @Test
    fun theKotlinTrackMatchesThePythonPrototype() {
        assertTrue("reference sizes", refEnvelope.size == 120 && refClasses.size == 120)
        val track = MouthTrack.bake(refEnvelope, refClasses, 0.08f)
        for (r in refRows) {
            val m = at(track, r[0] / 64.0)
            near("open at #${r[0].toInt()}", r[1], m[0], 1e-3)
            near("width at #${r[0].toInt()}", r[2], m[1], 1e-3)
            near("round at #${r[0].toInt()}", r[3], m[2], 1e-3)
        }
    }

    private fun legacyOval(shape: Int, amp: Float) =
        FaceGeometry.mouth(100f, 100f, 100f, 200f, 100f, shape, amp, com.example.engine.Expression.NORMAL, 1f)

    private fun actedOval(track: MouthTrack, t: Double): com.example.engine.FaceOval {
        val m = at(track, t)
        val f = ResolvedFace().setLegacy(com.example.engine.Expression.NORMAL, 1f)
        f.mouthActed = true; f.mouthOpen = m[0].toFloat(); f.mouthWidth = m[1].toFloat(); f.mouthRound = m[2].toFloat()
        return FaceGeometry.mouth(100f, 100f, 100f, 200f, 100f, MouthShape.CLOSED, 0f, f, 1f)
    }

    @Test
    fun steadyStateEqualsTheLegacyClassMouth() {
        // MID and NARROW exactly (up to the 120 Hz float track); WIDE only below the roundness onset (amplitude 0.5).
        val cases = listOf(
            MouthShape.MID to 0.5f, MouthShape.MID to 1.0f, MouthShape.MID to 0.2f,
            MouthShape.NARROW to 0.6f, MouthShape.NARROW to 0.3f, MouthShape.WIDE to 0.4f)
        for ((shape, amp) in cases) {
            val track = MouthTrack.bake(FloatArray(90) { amp }, IntArray(90) { shape }, 0.08f)
            val was = legacyOval(shape, amp)
            val now = actedOval(track, 2.9)
            near("half width $shape $amp", was.halfWidth.toDouble(), now.halfWidth.toDouble(), 0.1)
            near("half height $shape $amp", was.halfHeight.toDouble(), now.halfHeight.toDouble(), 0.1)
            assertTrue("same centre and rotation", was.cx == now.cx && was.cy == now.cy && was.rotationDeg == now.rotationDeg)
        }
        // A loud WIDE vowel is rounded: a little taller and narrower than the legacy class mouth, within the stated caps.
        val loud = MouthTrack.bake(FloatArray(90) { 1f }, IntArray(90) { MouthShape.WIDE }, 0.08f)
        val was = legacyOval(MouthShape.WIDE, 1f); val now = actedOval(loud, 2.9)
        assertTrue("rounded wide is narrower", now.halfWidth < was.halfWidth && now.halfWidth > was.halfWidth * 0.85f)
        assertTrue("rounded wide is taller", now.halfHeight > was.halfHeight && now.halfHeight < was.halfHeight * 1.3f)
        // Silence: exactly the legacy closed mouth.
        val quiet = MouthTrack.bake(FloatArray(90), IntArray(90), 0.08f)
        val closed = actedOval(quiet, 2.9); val legacyClosed = legacyOval(MouthShape.CLOSED, 0f)
        near("closed width", legacyClosed.halfWidth.toDouble(), closed.halfWidth.toDouble(), 0.05)
        near("closed height", legacyClosed.halfHeight.toDouble(), closed.halfHeight.toDouble(), 0.05)
    }

    @Test
    fun theMouthClosesToExactlyZeroAfterSustainedSilence() {
        val env = FloatArray(90) { if (it < 30) 0.7f else 0f }
        val track = MouthTrack.bake(env, IntArray(90) { MouthShape.MID }, 0.08f)
        assertTrue("open while speaking", at(track, 0.8)[0] > 0.2)
        assertTrue("exactly closed at 2.9 s", at(track, 2.9)[0] == 0.0)
        // A 66 ms dip is shorter than the hold: the mouth does not close.
        val dip = FloatArray(42) { if (it in 20..21) 0f else 0.7f }
        val t2 = MouthTrack.bake(dip, IntArray(42) { MouthShape.MID }, 0.08f)
        assertTrue("a short dip does not close the mouth", at(t2, 22.0 / 30.0)[0] > 0.02)
    }

    @Test
    fun attackIsFasterThanRelease() {
        val env = FloatArray(75) { if (it in 15..44) 0.7f else 0f }
        val track = MouthTrack.bake(env, IntArray(75) { MouthShape.MID }, 0.08f)
        val peak = (0..2400).maxOf { at(track, it / 480.0)[0] }
        var tRise = -1.0; var tFall = -1.0
        for (i in 0..1200) { val t = 0.5 + i / 480.0; if (tRise < 0 && at(track, t)[0] >= 0.632 * peak) tRise = t - 0.5 }
        for (i in 0..1200) { val t = 1.5 + i / 480.0; if (tFall < 0 && at(track, t)[0] <= 0.368 * peak) tFall = t - 1.5 }
        assertTrue("rise $tRise", tRise > 0.0 && tRise < 0.085)
        assertTrue("fall $tFall is slower than the rise", tFall > tRise)
    }

    /** A deterministic noisy envelope and class sequence (an LCG, no clock): the worst case for flicker. */
    private fun noisy(frames: Int, seed: Long): Pair<FloatArray, IntArray> {
        var s = seed
        fun next(): Double { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) and 0x7fffffffL) / 2147483648.0 }
        val env = FloatArray(frames); val cls = IntArray(frames)
        var k = 0
        while (k < frames) {
            val syl = 4 + (next() * 4).toInt(); val peak = 0.45 + next() * 0.5
            for (i in 0 until syl) {
                if (k >= frames) break
                val x = Math.sin(Math.PI * (i + 0.5) / syl)
                env[k] = (peak * Math.pow(x, 0.7) * (1 + (next() - 0.5) * 0.5) + (next() - 0.5) * 0.08).coerceIn(0.0, 1.0).toFloat(); k++
            }
            val pause = 5 + (next() * 10).toInt()
            for (i in 0 until pause) { if (k >= frames) break; env[k] = (next() * 0.05).toFloat(); k++ }
        }
        for (i in 0 until frames) cls[i] = if (env[i] <= 0.1f) MouthShape.CLOSED else if (next() < 0.45) 1 + (next() * 3).toInt() else MouthShape.MID
        return env to cls
    }

    @Test
    fun noFlickerOnANoisyEnvelope() {
        val frames = 360
        val (env, cls) = noisy(frames, 7L)
        val track = MouthTrack.bake(env, cls, 0.08f)
        // Legacy half-heights per envelope frame.
        val table = mapOf(MouthShape.CLOSED to 0.05, MouthShape.MID to 0.14, MouthShape.WIDE to 0.28, MouthShape.NARROW to 0.10)
        var sm = 0.0
        val legacy = DoubleArray(frames) { k -> sm += (env[k] - sm) * (1 - 0.22); table.getValue(cls[k]) * (0.5 + 0.5 * sm) }
        var jumpsLegacy = 0; var jumpsActed = 0
        var prevL = legacy[0]; var prevA = 0.0
        val steps = frames * 2 // 60 fps
        for (i in 0 until steps) {
            val t = i / 60.0
            val l = legacy[minOf((t * 30).toInt(), frames - 1)]
            val m = at(track, t)
            val a = (0.025 + (0.28 - 0.025) * m[0]) * (1 + 0.25 * m[2])
            if (i > 0) { if (abs(l - prevL) > 0.04) jumpsLegacy++; if (abs(a - prevA) > 0.04) jumpsActed++ }
            prevL = l; prevA = a
        }
        val seconds = frames / 30.0
        assertTrue("legacy is the flickery one: $jumpsLegacy jumps", jumpsLegacy / seconds > 2.0)
        assertTrue("acted jumps per second ${jumpsActed / seconds} (legacy ${jumpsLegacy / seconds})", jumpsActed / seconds < 1.0 && jumpsActed * 10 <= jumpsLegacy)
    }

    @Test
    fun theTrackIsBoundedAndFiniteEvenForBadInput() {
        val (env, cls) = noisy(300, 11L)
        env[5] = Float.NaN; env[6] = Float.POSITIVE_INFINITY; env[7] = -3f; env[8] = 9f
        cls[10] = 77; cls[11] = -4
        val track = MouthTrack.bake(env, cls, 0.08f)
        var t = -1.0
        while (t < 12.0) {
            val m = at(track, t)
            for (v in m) assertTrue("bounded at $t: $v", v.isFinite() && v >= 0.0 && v <= 1.0)
            t += 1.0 / 97.0
        }
        assertTrue("empty envelope gives an empty track", MouthTrack.bake(FloatArray(0), IntArray(0), 0.08f).isEmpty)
        val e = at(MouthTrack.EMPTY, 3.0)
        assertTrue("empty track is the closed mouth", e[0] == 0.0 && e[2] == 0.0)
        // Missing classes (a shorter or empty array) degrade to MID, never crash.
        MouthTrack.bake(FloatArray(60) { 0.6f }, IntArray(0), 0.08f)
        MouthTrack.bake(FloatArray(60) { 0.6f }, IntArray(3) { MouthShape.WIDE }, 0.08f)
    }

    @Test
    fun theTrackIsDeterministicAndIndependentOfTheSamplingOrder() {
        val (env, cls) = noisy(200, 3L)
        val a = MouthTrack.bake(env, cls, 0.08f); val b = MouthTrack.bake(env, cls, 0.08f)
        val times = (0 until 400).map { it / 60.0 }
        val forward = times.map { at(a, it).toList() }
        val shuffled = times.indices.sortedBy { (it * 7919) % 400 }
        for (i in shuffled) assertTrue("same value at $i", at(b, times[i]).toList() == forward[i])
        // A 30 fps and a 60 fps sampling agree at the same instants (frame-rate independent, unlike the legacy smoothing).
        for (k in 0 until 190) assertTrue("fps independent at $k", at(a, k / 30.0).toList() == at(a, (2 * k) / 60.0).toList())
    }

    @Test
    fun theEngineDrawsTheActedMouthAndSeekEqualsSequential() {
        val script = Fixtures.script("02_poses_expressions")
        val (env, cls) = noisy(600, 5L)
        val ctx = ActingContext.create("NORMAL", "p", env, 30, mouthShapes = cls)
        val walker = EngineHarness.create(script, env); walker.actingContext = ctx
        var i = 0
        for (target in listOf(40, 95, 96, 180, 333, 599)) {
            while (i <= target) { EngineHarness.seekFrame(walker, env, i / 30f); i++ }
            val fresh = EngineHarness.create(script, env); fresh.actingContext = ctx
            fresh.seekTo(target / 30f)
            val a = walker.renderFace!!; val b = fresh.renderFace!!
            assertTrue("acted mouth is on", a.mouthActed && b.mouthActed)
            assertTrue("open $target", a.mouthOpen == b.mouthOpen)
            assertTrue("width $target", a.mouthWidth == b.mouthWidth)
            assertTrue("round $target", a.mouthRound == b.mouthRound)
        }
    }

    @Test
    fun withActingOffOrWithoutAnEnvelopeTheLegacyMouthStays() {
        val script = Fixtures.script("02_poses_expressions")
        val (env, cls) = noisy(300, 5L)
        for (ctx in listOf<ActingContext?>(null, ActingContext.create("OFF", "p", env, 30, mouthShapes = cls))) {
            val e = EngineHarness.create(script, env); e.actingContext = ctx
            e.seekTo(2f)
            assertTrue("no acted face when OFF", e.renderFace == null)
        }
        // Acting on but no envelope: the acted face exists, its mouth is the legacy shape table.
        val none = EngineHarness.create(script, FloatArray(0)); none.actingContext = ActingContext.create("NORMAL", "p")
        none.seekTo(2f)
        assertTrue("legacy mouth without an envelope", none.renderFace != null && !none.renderFace!!.mouthActed)
        // Amplitude motion switched off: the legacy mouth stays.
        val off = EngineHarness.create(script, env, com.example.data.AmplitudeSettings(enabled = false))
        off.actingContext = ActingContext.create("NORMAL", "p", env, 30, mouthShapes = cls)
        off.seekTo(2f)
        assertTrue("legacy mouth with amplitude motion off", off.renderFace != null && !off.renderFace!!.mouthActed)
        // The legacy geometry path is unchanged for a ResolvedFace that is not acted (FaceGeometryTest keeps the oracle).
        val f = ResolvedFace().setLegacy(com.example.engine.Expression.NORMAL, 1f)
        assertTrue("setLegacy clears the mouth", !f.mouthActed)
    }

    @Test
    fun theLevelScalesTheOpeningAndNeverBeyondOne() {
        val (env, cls) = noisy(200, 9L)
        val script = Fixtures.script("02_poses_expressions")
        val opens = listOf("SUBTLE", "NORMAL", "BOLD").map { name ->
            val e = EngineHarness.create(script, env); e.actingContext = ActingContext.create(name, "p", env, 30, mouthShapes = cls)
            var peak = 0f
            for (k in 0 until 200) { e.seekTo(k / 30f); peak = maxOf(peak, e.renderFace!!.mouthOpen) }
            peak
        }
        assertTrue("SUBTLE < NORMAL < BOLD: $opens", opens[0] < opens[1] && opens[1] <= opens[2] && opens[2] <= 1f)
    }
}
