package com.example

import com.example.data.AmplitudeSettings
import com.example.data.AnimScript
import com.example.data.ScriptEvent
import com.example.engine.PlaybackEngine
import com.example.engine.StickFigureRig
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingFrame
import com.example.engine.acting.ActingIntensity
import com.example.engine.acting.ActingMotion
import com.example.engine.acting.ActingParams
import com.example.engine.acting.FaceTimeline
import com.example.engine.acting.MoodModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.6 (mood idles). The pure model is pinned to the Python prototype (`tools/acting_proto/mood_proto.py`, rows from its
 * `--emit-reference`), and the brief's acceptance list is tested: the breathing phase is continuous when the rate changes,
 * an all-NORMAL timeline adds exactly nothing, the mood is off during a walk, the engine record leaves the legacy frame alone,
 * and a frame without amplitude motion is untouched.
 */
class MoodMotionTest {

    // Timeline A of the prototype: expr0 NORMAL; ANGRY 6, HAPPY 16, SQUINT 16.6 (overlaps the blend), WORRIED 26, WIDE 32, NORMAL 34.
    private val face = FaceTimeline(0, floatArrayOf(6f, 16f, 16.6f, 26f, 32f, 34f), intArrayOf(4, 5, 2, 3, 1, 0))
    // Locomotion: a walk keyframe from 20 s to 24 s (keyframes at 0, 20 and 24).
    private val free = MoodModel.bakeFree(floatArrayOf(0f, 20f, 24f), booleanArrayOf(false, true, false))
    private val bones = StickFigureRig.BONES
    private val torso = bones.indexOfFirst { it.id == "torso" }
    private val head = bones.indexOfFirst { it.id == "head" }
    private val armR = bones.indexOfFirst { it.id == "upper_arm_r" }
    private val armL = bones.indexOfFirst { it.id == "upper_arm_l" }

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected, got $actual (tolerance $tolerance)", abs(expected - actual) <= tolerance)
    }

    private fun frame(): ActingFrame = ActingFrame(FloatArray(bones.size) { bones[it].defaultAngleDegrees })

    /** Applies the mood to a fresh frame and returns (base, out). */
    private fun applied(mood: com.example.engine.acting.BlendTrack, gate: com.example.engine.acting.BlendTrack, t: Double,
                        breath: Float, sway: FloatArray = FloatArray(4)): Pair<ActingFrame, ActingFrame> {
        val base = frame()
        base.ampBreathDeg = breath
        base.ampBreathHz = 0.35f
        sway.copyInto(base.ampSway)
        val out = ActingFrame(base.angles.copyOf())
        MoodModel.apply(base, mood, gate, t, torso, head, armR, armL, out)
        return base to out
    }

    // Rows from `python3 mood_proto.py --emit-reference`: t, rate, depth, sway, warp, w_free, torso breath delta, sway gain.
    private val normalRef = arrayOf(
        doubleArrayOf(0.0, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(2.5, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(6.0, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(6.4, 1.052622222, 1.061392593, 1.052622222, 0.007395556, 1.000000000, 0.087101289, 0.052622222),
        doubleArrayOf(7.5, 1.300000000, 1.350000000, 1.300000000, 0.225000000, 1.000000000, -0.820808286, 0.300000000),
        doubleArrayOf(9.0, 1.300000000, 1.350000000, 1.300000000, 0.675000000, 1.000000000, 0.106082531, 0.300000000),
        doubleArrayOf(12.0, 1.300000000, 1.350000000, 1.300000000, 1.575000000, 1.000000000, -3.221420831, 0.300000000),
        doubleArrayOf(15.9, 1.300000000, 1.350000000, 1.300000000, 2.745000000, 1.000000000, 0.251552461, 0.300000000),
        doubleArrayOf(16.3, 1.279200000, 1.324000000, 1.294800000, 2.862840000, 1.000000000, -0.441928398, 0.294800000),
        doubleArrayOf(16.6, 1.229600000, 1.262000000, 1.282400000, 2.939640000, 1.000000000, -0.196731876, 0.282400000),
        doubleArrayOf(17.0, 1.163015401, 1.189732196, 1.197783596, 3.022122155, 1.000000000, 0.513623093, 0.197783596),
        doubleArrayOf(18.5, 0.850000000, 0.850000000, 0.800000000, 2.939340090, 1.000000000, -0.247186630, -0.200000000),
        doubleArrayOf(20.1, 0.850000000, 0.850000000, 0.800000000, 2.699340090, 0.801749271, -0.365806331, -0.160349854),
        doubleArrayOf(20.3, 0.850000000, 0.850000000, 0.800000000, 2.669340090, 0.055393586, -0.031431747, -0.011078717),
        doubleArrayOf(22.0, 0.850000000, 0.850000000, 0.800000000, 2.414340090, 0.000000000, 0.000000000, -0.000000000),
        doubleArrayOf(24.2, 0.850000000, 0.850000000, 0.800000000, 2.084340090, 0.606413994, 0.526553493, -0.121282799),
        doubleArrayOf(24.6, 0.850000000, 0.850000000, 0.800000000, 2.024340090, 1.000000000, 1.973805829, -0.200000000),
        doubleArrayOf(26.8, 1.124962963, 0.822503704, 0.827496296, 1.777777127, 1.000000000, -0.942290073, -0.172503704),
        doubleArrayOf(29.0, 1.350000000, 0.800000000, 0.850000000, 2.489340090, 1.000000000, -0.983395158, -0.150000000),
        doubleArrayOf(32.4, 1.358770370, 0.878933333, 0.902622222, 3.680572682, 1.000000000, -2.069478400, -0.097377778),
        doubleArrayOf(33.7, 1.400000000, 1.250000000, 1.150000000, 4.181840090, 1.000000000, 3.091830720, 0.150000000),
        doubleArrayOf(35.0, 1.103703704, 1.064814815, 1.038888889, 4.583321571, 1.000000000, -2.582707619, 0.038888889),
        doubleArrayOf(39.0, 1.000000000, 1.000000000, 1.000000000, 4.601840090, 1.000000000, 2.529494053, 0.000000000),
    )
    private val boldRef = arrayOf(
        doubleArrayOf(0.0, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(2.5, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(6.0, 1.000000000, 1.000000000, 1.000000000, 0.000000000, 1.000000000, 0.000000000, 0.000000000),
        doubleArrayOf(6.4, 1.073671110, 1.085949628, 1.073671110, 0.010353778, 1.000000000, 0.121872143, 0.073671110),
        doubleArrayOf(7.5, 1.419999993, 1.489999992, 1.419999993, 0.314999995, 1.000000000, -1.087098551, 0.419999993),
        doubleArrayOf(9.0, 1.419999993, 1.489999992, 1.419999993, 0.944999984, 1.000000000, -0.880933992, 0.419999993),
        doubleArrayOf(12.0, 1.419999993, 1.489999992, 1.419999993, 2.204999962, 1.000000000, -1.699803043, 0.419999993),
        doubleArrayOf(15.9, 1.419999993, 1.489999992, 1.419999993, 3.842999935, 1.000000000, -0.561174521, 0.419999993),
        doubleArrayOf(16.3, 1.390879993, 1.453599992, 1.412719993, 4.007975932, 1.000000000, 2.619712469, 0.412719993),
        doubleArrayOf(16.6, 1.321439995, 1.366799994, 1.395359993, 4.115495930, 1.000000000, 3.215200295, 0.395359993),
        doubleArrayOf(17.0, 1.228221558, 1.265625069, 1.276897029, 4.230970945, 1.000000000, 1.178581053, 0.276897029),
        doubleArrayOf(18.5, 0.790000004, 0.790000004, 0.720000005, 4.115076056, 1.000000000, -0.780352618, -0.279999995),
        doubleArrayOf(20.1, 0.790000004, 0.790000004, 0.720000005, 3.779076061, 0.801749271, 0.446564367, -0.224489792),
        doubleArrayOf(20.3, 0.790000004, 0.790000004, 0.720000005, 3.737076062, 0.055393586, -0.015677111, -0.015510204),
        doubleArrayOf(22.0, 0.790000004, 0.790000004, 0.720000005, 3.380076068, 0.000000000, 0.000000000, -0.000000000),
        doubleArrayOf(24.2, 0.790000004, 0.790000004, 0.720000005, 2.918076076, 0.606413994, -0.122550591, -0.169795915),
        doubleArrayOf(24.6, 0.790000004, 0.790000004, 0.720000005, 2.834076078, 1.000000000, 0.231519447, -0.279999995),
        doubleArrayOf(26.8, 1.174948145, 0.751505189, 0.758494819, 2.488887935, 1.000000000, 0.093715693, -0.241505181),
        doubleArrayOf(29.0, 1.489999992, 0.720000005, 0.790000004, 3.485076066, 1.000000000, -0.396855675, -0.209999996),
        doubleArrayOf(32.4, 1.502278510, 0.830506670, 0.863671113, 5.152801668, 1.000000000, -0.270183592, -0.136328887),
        doubleArrayOf(33.7, 1.559999990, 1.349999994, 1.209999996, 5.854576026, 1.000000000, -0.224742819, 0.209999996),
        doubleArrayOf(35.0, 1.145185183, 1.090740739, 1.054444444, 6.416650091, 1.000000000, -1.359971185, 0.054444444),
        doubleArrayOf(39.0, 1.000000000, 1.000000000, 1.000000000, 6.442576016, 1.000000000, 0.344991357, 0.000000000),
    )

    private fun checkAgainstPython(scale: Float, ref: Array<DoubleArray>) {
        val mood = MoodModel.bakeMood(face, scale.toDouble())
        val v = DoubleArray(3)
        val g = DoubleArray(1)
        for (row in ref) {
            val t = row[0]
            val warp = mood.evaluate(t, v)
            free.evaluate(t, g)
            near("rate @$t", row[1], v[0], 5e-9)
            near("depth @$t", row[2], v[1], 5e-9)
            near("sway @$t", row[3], v[2], 5e-9)
            near("warp @$t", row[4], warp, 1e-8)
            near("w_free @$t", row[5], g[0], 5e-9)
            // The layer's torso delta (the frame holds floats, so ~1e-5) and its sway gain, through MoodModel.apply itself.
            val (base, out) = applied(mood, free, t, 1.4f)
            near("breath delta @$t", row[6], (out.angles[torso] - base.angles[torso]).toDouble(), 5e-5)
            near("head gets -0.5 of it @$t", -0.5 * row[6], (out.angles[head] - base.angles[head]).toDouble(), 5e-5)
            val (b2, o2) = applied(mood, free, t, 0f, floatArrayOf(1f, 0f, 0f, 0f))
            near("sway gain @$t", row[7], (o2.angles[torso] - b2.angles[torso]).toDouble(), 5e-5)
        }
    }

    @Test
    fun theModelMatchesThePythonPrototypeAtNormalAndBold() {
        checkAgainstPython(ActingParams.levelFor(ActingIntensity.NORMAL).moodScale, normalRef)
        checkAgainstPython(ActingParams.levelFor(ActingIntensity.BOLD).moodScale, boldRef)
    }

    @Test
    fun theTableAndTheLevelScalesAreTheDocumentedOnes() {
        assertEquals(0.5f, ActingParams.levelFor(ActingIntensity.SUBTLE).moodScale, 0f)
        assertEquals(1.0f, ActingParams.levelFor(ActingIntensity.NORMAL).moodScale, 0f)
        assertEquals(1.4f, ActingParams.levelFor(ActingIntensity.BOLD).moodScale, 0f)
        assertEquals(0f, ActingParams.levelFor(ActingIntensity.OFF).moodScale, 0f)
        assertEquals(6, ActingParams.MOOD_RATE.size)
        assertEquals(6, ActingParams.MOOD_DEPTH.size)
        assertEquals(6, ActingParams.MOOD_SWAY.size)
        // NORMAL is the neutral mood: every multiplier is exactly 1 at every level.
        for (scale in listOf(0.5, 1.0, 1.4)) assertTrue(MoodModel.target(0, scale).all { it == 1.0 })
        // Calm below 1, energetic above (squint calm, angry energetic), and scaling grows with the level.
        assertTrue(MoodModel.target(2, 1.0)[0] < 1.0 && MoodModel.target(4, 1.0)[0] > 1.0)
        assertTrue(MoodModel.target(4, 0.5)[2] < MoodModel.target(4, 1.0)[2] && MoodModel.target(4, 1.0)[2] < MoodModel.target(4, 1.4)[2])
        // An out-of-range expression index is read as NORMAL, and the clamp holds for any table.
        assertTrue(MoodModel.target(99, 1.0).all { it == 1.0 })
        for (e in 0..5) for (x in MoodModel.target(e, 10.0)) assertTrue(x >= ActingParams.MOOD_MIN && x <= ActingParams.MOOD_MAX)
    }

    @Test
    fun theWarpIsTheIntegralOfTheRateMinusOne() {
        for (scale in listOf(0.5, 1.0, 1.4)) {
            val mood = MoodModel.bakeMood(face, scale)
            val v = DoubleArray(3)
            val dt = 1e-4
            var acc = 0.0
            var next = 0
            val probes = doubleArrayOf(3.0, 6.7, 10.0, 16.3, 16.9, 21.0, 26.5, 33.1, 39.9)
            var t = 0.0
            var worst = 0.0
            while (next < probes.size) {
                mood.evaluate(t + dt / 2, v)          // midpoint rule
                acc += (v[0] - 1.0) * dt
                t += dt
                if (t >= probes[next] - 1e-9) {
                    worst = maxOf(worst, abs(acc - mood.evaluate(t, v)))
                    next++
                }
            }
            assertTrue("scale $scale: warp error $worst", worst < 1e-6)
        }
    }

    @Test
    fun theBreathNeverJumpsWhenTheMoodChanges() {
        // Rate, depth, sway, warp and the delta added to the breath are continuous: no step larger than a smooth ramp allows in 1 ms.
        for (scale in listOf(0.5, 1.0, 1.4)) {
            val mood = MoodModel.bakeMood(face, scale)
            val v = DoubleArray(3)
            var prevRate = 0.0; var prevDepth = 0.0; var prevSway = 0.0; var prevWarp = 0.0; var prevDelta = 0.0
            var maxD = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
            var i = 0
            while (i < 40000) {
                val t = i * 1e-3
                val warp = mood.evaluate(t, v)
                val (b, o) = applied(mood, free, t, 1.4f)
                val delta = (o.angles[torso] - b.angles[torso]).toDouble()
                if (i > 0) {
                    maxD[0] = maxOf(maxD[0], abs(v[0] - prevRate)); maxD[1] = maxOf(maxD[1], abs(v[1] - prevDepth))
                    maxD[2] = maxOf(maxD[2], abs(v[2] - prevSway)); maxD[3] = maxOf(maxD[3], abs(warp - prevWarp))
                    maxD[4] = maxOf(maxD[4], abs(delta - prevDelta))
                }
                prevRate = v[0]; prevDepth = v[1]; prevSway = v[2]; prevWarp = warp; prevDelta = delta
                i++
            }
            // The fastest blend moves a multiplier by (max - min) * 1.5 / blend per second = 0.0013 per ms at most; the warp by that integrated.
            assertTrue("scale $scale rate step ${maxD[0]}", maxD[0] < 0.002)
            assertTrue("scale $scale depth step ${maxD[1]}", maxD[1] < 0.002)
            assertTrue("scale $scale sway step ${maxD[2]}", maxD[2] < 0.002)
            assertTrue("scale $scale warp step ${maxD[3]}", maxD[3] < 0.0006)
            // The breath itself changes by at most 2 pi f * (depth + 1) * a per second plus the ramps: well under 0.02 deg per ms.
            assertTrue("scale $scale delta step ${maxD[4]}", maxD[4] < 0.02)
        }
    }

    @Test
    fun anAllNormalTimelineAddsExactlyNothing() {
        val flat = MoodModel.bakeMood(FaceTimeline(0, floatArrayOf(3f, 9f), intArrayOf(0, 0)), 1.4)
        val none = MoodModel.bakeFree(FloatArray(0), BooleanArray(0))
        var t = 0.0
        while (t < 30.0) {
            val (b, o) = applied(flat, none, t, 1.4f, floatArrayOf(2f, -1f, 0.5f, 0.25f))
            for (i in b.angles.indices) assertEquals("t=$t bone $i", b.angles[i], o.angles[i], 0f)
            t += 0.0137
        }
    }

    @Test
    fun theMoodIsOffExactlyInsideAWalkAndFadesAroundIt() {
        val mood = MoodModel.bakeMood(face, 1.4)
        var t = 20.0 + ActingParams.MOOD_LOCO_RAMP_SEC + 0.01
        while (t < 24.0) {
            val (b, o) = applied(mood, free, t, 1.4f, floatArrayOf(2f, 1f, 1f, 1f))
            for (i in b.angles.indices) assertEquals("walk t=$t bone $i", b.angles[i], o.angles[i], 0f)
            t += 0.05
        }
        val g = DoubleArray(1)
        free.evaluate(19.99, g); assertEquals(1.0, g[0], 0.0)
        free.evaluate(20.0 + ActingParams.MOOD_LOCO_RAMP_SEC / 2, g); assertTrue(g[0] > 0.3 && g[0] < 0.7)
        free.evaluate(24.0 + ActingParams.MOOD_LOCO_RAMP_SEC + 0.01, g); assertEquals(1.0, g[0], 0.0)
        // An initial walk keyframe starts with the mood off.
        val startsWalking = MoodModel.bakeFree(floatArrayOf(0f, 5f), booleanArrayOf(true, false))
        startsWalking.evaluate(1.0, g); assertEquals(0.0, g[0], 0.0)
        startsWalking.evaluate(9.0, g); assertEquals(1.0, g[0], 0.0)
    }

    @Test
    fun aFrameWithoutAmplitudeMotionIsLeftAlone() {
        val mood = MoodModel.bakeMood(face, 1.4)
        val (b, o) = applied(mood, free, 12.0, 0f)
        for (i in b.angles.indices) assertEquals(b.angles[i], o.angles[i], 0f)
    }

    @Test
    fun theSwayScalesTheRecordedLegacyContributionAndTheBreathAddsTheDifference() {
        val mood = MoodModel.bakeMood(face, 1.0)
        // Steady ANGRY at 12 s: sway x1.3, depth 1.35, rate 1.3.
        val v = DoubleArray(3)
        val warp = mood.evaluate(12.0, v)
        assertEquals(1.30, v[2], 1e-12)
        val (b, o) = applied(mood, free, 12.0, 1.4f, floatArrayOf(2f, -1f, 0.5f, -0.5f))
        val acted = v[1] * sin(2.0 * PI * 0.35 * (12.0 + warp))
        val legacy = sin(2.0 * PI * 0.35 * 12.0)
        val d = 1.4 * (acted - legacy)
        near("torso", d + 0.3 * 2.0, (o.angles[torso] - b.angles[torso]).toDouble(), 5e-5)
        near("head", -0.5 * d + 0.3 * -1.0, (o.angles[head] - b.angles[head]).toDouble(), 5e-5)
        near("arm r", 0.3 * 0.5, (o.angles[armR] - b.angles[armR]).toDouble(), 5e-5)
        near("arm l", 0.3 * -0.5, (o.angles[armL] - b.angles[armL]).toDouble(), 5e-5)
    }

    @Test
    fun offHasNoMoodAndEveryOtherLevelHasOne() {
        val kfs = EngineHarness.compile(script(Triple(0f, "stand_straight", "angry"), Triple(5f, "stand_straight", "happy")))
        val m = ActingMotion(kfs, true)
        assertNull(m.moodFor(ActingIntensity.OFF))
        for (i in listOf(ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD)) assertNotNull("$i", m.moodFor(i))
    }

    // ── The engine ──────────────────────────────────────────────────────────

    private fun script(vararg e: Triple<Float, String, String?>): AnimScript =
        AnimScript(events = e.map { ScriptEvent(timeSec = it.first, pose = it.second, duration = 0.5f, expression = it.third) })

    private val amplitude = AmplitudeSettings(idleFidgetEnabled = false)

    private fun engine(s: AnimScript, intensity: ActingIntensity?): PlaybackEngine {
        val kfs = EngineHarness.compile(s)
        val envelope = TestEnvelopes.silence(30f)
        val e = EngineHarness.create(s, envelope, amplitude, kfs)
        if (intensity != null) e.actingContext = ActingContext.create(intensity.name, "mood")
        return e
    }

    /** Same poses, one script angry from 3 s and one never: any torso difference is the mood (the face goes to the head). */
    private val calmScript = script(Triple(0f, "stand_straight", "normal"), Triple(3f, "stand_straight", "normal"), Triple(18f, "stand_straight", "normal"))
    private val angryScript = script(Triple(0f, "stand_straight", "normal"), Triple(3f, "stand_straight", "angry"), Triple(18f, "stand_straight", "normal"))

    @Test
    fun angerChangesTheIdleBreathAndNormalDoesNot() {
        val calm = engine(calmScript, ActingIntensity.NORMAL)
        val angry = engine(angryScript, ActingIntensity.NORMAL)
        val env = TestEnvelopes.silence(30f)
        var before = 0.0
        var during = 0.0
        for (i in 0..(14 * 30)) {
            val t = i / 30f
            EngineHarness.seekFrame(calm, env, t)
            EngineHarness.seekFrame(angry, env, t)
            val d = abs(calm.renderAngles[torso] - angry.renderAngles[torso]).toDouble()
            if (t < 3f) before = maxOf(before, d)
            if (t in 8f..14f) during = maxOf(during, d)
        }
        assertEquals("nothing differs before the expression changes", 0.0, before, 0.0)
        assertTrue("the breath differs once ANGRY has settled: $during", during > 0.3)
    }

    @Test
    fun aWalkKeepsTheLegacyBreathUnderAnAngryMood() {
        fun walk(expr: String) = script(
            Triple(0f, "stand_straight", "normal"), Triple(3f, "stand_straight", expr),
            Triple(6f, "walk_a", null), Triple(6.6f, "walk_b", null), Triple(7.2f, "walk_a", null), Triple(7.8f, "walk_b", null),
            Triple(8.4f, "walk_a", null), Triple(9f, "walk_b", null)
        )
        val calm = engine(walk("normal"), ActingIntensity.NORMAL)
        val angry = engine(walk("angry"), ActingIntensity.NORMAL)
        val env = TestEnvelopes.silence(30f)
        var inside = 0.0
        for (i in 0..(10 * 30)) {
            val t = i / 30f
            EngineHarness.seekFrame(calm, env, t)
            EngineHarness.seekFrame(angry, env, t)
            // The gate is closed from 6.35 s (a 0.35 s ramp after the first walk keyframe) while the walk keyframes run.
            if (t in 6.4f..9.0f) inside = maxOf(inside, abs(calm.renderAngles[torso] - angry.renderAngles[torso]).toDouble())
        }
        assertEquals("the mood adds nothing inside a walk", 0.0, inside, 0.0)
    }

    @Test
    fun theRecordLeavesTheLegacyFrameAloneAndAPausedSeekHasNoMood() {
        // OFF: the engine's legacy frame is what is drawn, whatever the recording does.
        val off = engine(angryScript, ActingIntensity.OFF)
        val none = engine(angryScript, null)
        val env = TestEnvelopes.silence(30f)
        for (i in 0..(14 * 30)) {
            val t = i / 30f
            EngineHarness.seekFrame(off, env, t); EngineHarness.seekFrame(none, env, t)
            assertTrue(off.renderAngles === off.currentAngles)
            for (k in off.currentAngles.indices) assertEquals("t=$t bone $k", none.currentAngles[k], off.currentAngles[k], 0f)
        }
        // A paused seek builds a frame without amplitude motion: the acted frame must not gain a breath difference there.
        val calm = engine(calmScript, ActingIntensity.NORMAL)
        val angry = engine(angryScript, ActingIntensity.NORMAL)
        calm.seekTo(12f); angry.seekTo(12f)
        assertEquals(calm.renderAngles[torso], angry.renderAngles[torso], 0f)
    }

    @Test
    fun theSameFramesGiveTheSameMood() {
        val a = engine(angryScript, ActingIntensity.BOLD)
        val b = engine(angryScript, ActingIntensity.BOLD)
        val env = TestEnvelopes.silence(30f)
        for (i in 0..(16 * 30)) {
            val t = i / 30f
            EngineHarness.seekFrame(a, env, t); EngineHarness.seekFrame(b, env, t)
            for (k in a.renderAngles.indices) assertEquals("t=$t bone $k", a.renderAngles[k], b.renderAngles[k], 0f)
            assertTrue(a.actingFault.count == 0)
        }
    }
}
