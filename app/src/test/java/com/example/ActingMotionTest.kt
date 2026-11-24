package com.example

import com.example.data.AmplitudeSettings
import com.example.data.AnimScript
import com.example.data.ScriptEvent
import com.example.engine.PlaybackEngine
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingFrame
import com.example.engine.acting.ActingIntensity
import com.example.engine.acting.ActingLayer
import com.example.engine.acting.ActingLevel
import com.example.engine.acting.ActingMotion
import com.example.engine.acting.ActingMotionModel
import com.example.engine.acting.ActingParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * WP2.2 (motion model). The pure model is pinned to the Python prototype (`tools/acting_proto/acting_proto.py`), and the
 * brief's acceptance list is tested on it: arrival and peak-velocity ordering, overshoot cap, exact landing, no position
 * pop at a retarget, locomotion keeps lead/follow only, NaN-free, determinism, legacy pose equals the engine's base pose.
 */
class ActingMotionTest {

    private val seed = 12345L

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected, got $actual (tolerance $tolerance)", abs(expected - actual) <= tolerance)
    }

    private fun script(vararg e: Triple<Float, String, Pair<Float, String>>): AnimScript =
        AnimScript(events = e.map { ScriptEvent(timeSec = it.first, pose = it.second, duration = it.third.first, ease = it.third.second) })

    private fun motion(s: AnimScript, easeAll: Boolean = true) = ActingMotion(EngineHarness.compile(s), easeAll)

    /** The script that `acting_proto.py` calls REF_EVENTS. */
    private val refScript = script(
        Triple(0.0f, "stand_straight", 0.4f to "ease_in_out"), Triple(0.5f, "explain", 0.6f to "ease_in_out"),
        Triple(0.53f, "think", 0.6f to "ease_in_out"), Triple(1.9f, "wave", 0.6f to "linear"),
        Triple(2.4f, "stand_straight", 0.5f to "rigid"), Triple(3.0f, "walk_a", 0.6f to "ease_in_out"),
        Triple(3.6f, "walk_b", 0.6f to "ease_in_out"), Triple(4.5f, "stand_straight", 0.6f to "ease_in_out")
    )

    /** Two well separated moves: stand, a big reach, stand again. */
    private val sparse = script(
        Triple(0.0f, "stand_straight", 0.4f to "ease_in_out"), Triple(1.0f, "think", 0.6f to "ease_in_out"),
        Triple(4.0f, "stand_straight", 0.6f to "ease_in_out")
    )

    private fun noHold(l: ActingLevel) = ActingLevel(
        l.leadFollowDelaySec, l.durationStretchPerLevel, l.anticipationFraction, l.anticipationCapDeg,
        l.overshootFraction, l.overshootCapDeg, l.expressionBlendSec, l.anticipationLeadSec, 0f
    )

    private val levels = listOf(ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD).map { it to ActingParams.levelFor(it) }

    // Reference rows (bone, time, value) from the Python prototype (seed 12345, moving hold on).
    private val normalRef = arrayOf(
            doubleArrayOf(0.0, 0.45, -89.814167),
            doubleArrayOf(0.0, 0.507, -90.362639),
            doubleArrayOf(0.0, 0.523, -92.263296),
            doubleArrayOf(0.0, 0.557, -88.946460),
            doubleArrayOf(0.0, 0.6, -84.497911),
            doubleArrayOf(0.0, 0.7, -83.177695),
            doubleArrayOf(0.0, 1.0, -81.960966),
            doubleArrayOf(0.0, 1.907, -82.580223),
            doubleArrayOf(0.0, 2.0, -90.396006),
            doubleArrayOf(0.0, 2.1, -90.233104),
            doubleArrayOf(0.0, 2.407, -90.000081),
            doubleArrayOf(0.0, 2.5, -90.013306),
            doubleArrayOf(0.0, 3.1, -82.946011),
            doubleArrayOf(0.0, 3.7, -83.000000),
            doubleArrayOf(0.0, 4.6, -90.053989),
            doubleArrayOf(0.0, 5.8, -90.000000),
            doubleArrayOf(1.0, 0.45, -0.165081),
            doubleArrayOf(1.0, 0.507, -0.174758),
            doubleArrayOf(1.0, 0.523, -0.175290),
            doubleArrayOf(1.0, 0.557, -0.125625),
            doubleArrayOf(1.0, 0.6, -16.279344),
            doubleArrayOf(1.0, 0.7, -20.827426),
            doubleArrayOf(1.0, 1.0, -19.899600),
            doubleArrayOf(1.0, 1.907, -20.220929),
            doubleArrayOf(1.0, 2.0, 0.403286),
            doubleArrayOf(1.0, 2.1, 0.659027),
            doubleArrayOf(1.0, 2.407, -0.000008),
            doubleArrayOf(1.0, 2.5, -0.002991),
            doubleArrayOf(1.0, 3.1, 0.000000),
            doubleArrayOf(1.0, 3.7, 0.000000),
            doubleArrayOf(1.0, 4.6, 0.000000),
            doubleArrayOf(1.0, 5.8, 0.000000),
            doubleArrayOf(3.0, 0.45, 10.164630),
            doubleArrayOf(3.0, 0.507, 11.907880),
            doubleArrayOf(3.0, 0.523, 11.212978),
            doubleArrayOf(3.0, 0.557, 10.418796),
            doubleArrayOf(3.0, 0.6, -42.579106),
            doubleArrayOf(3.0, 0.7, -107.152843),
            doubleArrayOf(3.0, 1.0, -90.146673),
            doubleArrayOf(3.0, 1.907, -94.804329),
            doubleArrayOf(3.0, 2.0, -22.774884),
            doubleArrayOf(3.0, 2.1, -6.740072),
            doubleArrayOf(3.0, 2.407, 9.999949),
            doubleArrayOf(3.0, 2.5, 9.993208),
            doubleArrayOf(3.0, 3.1, 10.000000),
            doubleArrayOf(3.0, 3.7, 10.000000),
            doubleArrayOf(3.0, 4.6, 10.000000),
            doubleArrayOf(3.0, 5.8, 10.000000),
            doubleArrayOf(7.0, 0.45, 4.962001),
            doubleArrayOf(7.0, 0.507, 4.981202),
            doubleArrayOf(7.0, 0.523, 4.987923),
            doubleArrayOf(7.0, 0.557, 5.000000),
            doubleArrayOf(7.0, 0.6, 5.000000),
            doubleArrayOf(7.0, 0.7, 5.003689),
            doubleArrayOf(7.0, 1.0, 5.113733),
            doubleArrayOf(7.0, 1.907, 5.024681),
            doubleArrayOf(7.0, 2.0, 5.000011),
            doubleArrayOf(7.0, 2.1, 4.999916),
            doubleArrayOf(7.0, 2.407, 4.999992),
            doubleArrayOf(7.0, 2.5, 4.998126),
            doubleArrayOf(7.0, 3.1, -13.454223),
            doubleArrayOf(7.0, 3.7, 11.520162),
            doubleArrayOf(7.0, 4.6, 6.934060),
            doubleArrayOf(7.0, 5.8, 5.000000),
    )
    private val boldRef = arrayOf(
            doubleArrayOf(0.0, 0.45, -89.777000),
            doubleArrayOf(0.0, 0.507, -90.362639),
            doubleArrayOf(0.0, 0.523, -92.263296),
            doubleArrayOf(0.0, 0.557, -88.946460),
            doubleArrayOf(0.0, 0.6, -84.274159),
            doubleArrayOf(0.0, 0.7, -82.912560),
            doubleArrayOf(0.0, 1.0, -81.958187),
            doubleArrayOf(0.0, 1.907, -82.580223),
            doubleArrayOf(0.0, 2.0, -90.632018),
            doubleArrayOf(0.0, 2.1, -90.378391),
            doubleArrayOf(0.0, 2.407, -90.000097),
            doubleArrayOf(0.0, 2.5, -90.015967),
            doubleArrayOf(0.0, 3.1, -82.946011),
            doubleArrayOf(0.0, 3.7, -83.000000),
            doubleArrayOf(0.0, 4.6, -90.053989),
            doubleArrayOf(0.0, 5.8, -90.000000),
            doubleArrayOf(1.0, 0.45, -0.188941),
            doubleArrayOf(1.0, 0.507, -0.202889),
            doubleArrayOf(1.0, 0.523, -0.204280),
            doubleArrayOf(1.0, 0.557, 0.171885),
            doubleArrayOf(1.0, 0.6, -11.129957),
            doubleArrayOf(1.0, 0.7, -21.513831),
            doubleArrayOf(1.0, 1.0, -19.819508),
            doubleArrayOf(1.0, 1.907, -20.773424),
            doubleArrayOf(1.0, 2.0, -1.078857),
            doubleArrayOf(1.0, 2.1, 1.286429),
            doubleArrayOf(1.0, 2.407, -0.000010),
            doubleArrayOf(1.0, 2.5, -0.003589),
            doubleArrayOf(1.0, 3.1, 0.000000),
            doubleArrayOf(1.0, 3.7, 0.000000),
            doubleArrayOf(1.0, 4.6, 0.000000),
            doubleArrayOf(1.0, 5.8, 0.000000),
            doubleArrayOf(3.0, 0.45, 10.176112),
            doubleArrayOf(3.0, 0.507, 12.813908),
            doubleArrayOf(3.0, 0.523, 12.823006),
            doubleArrayOf(3.0, 0.557, 10.990915),
            doubleArrayOf(3.0, 0.6, 1.373323),
            doubleArrayOf(3.0, 0.7, -109.785539),
            doubleArrayOf(3.0, 1.0, -90.038016),
            doubleArrayOf(3.0, 1.907, -97.213522),
            doubleArrayOf(3.0, 2.0, -65.831127),
            doubleArrayOf(3.0, 2.1, -3.707636),
            doubleArrayOf(3.0, 2.407, 9.999938),
            doubleArrayOf(3.0, 2.5, 9.991850),
            doubleArrayOf(3.0, 3.1, 10.000000),
            doubleArrayOf(3.0, 3.7, 10.000000),
            doubleArrayOf(3.0, 4.6, 10.000000),
            doubleArrayOf(3.0, 5.8, 10.000000),
            doubleArrayOf(7.0, 0.45, 4.959351),
            doubleArrayOf(7.0, 0.507, 4.979256),
            doubleArrayOf(7.0, 0.523, 4.986566),
            doubleArrayOf(7.0, 0.557, 5.004140),
            doubleArrayOf(7.0, 0.6, 5.000000),
            doubleArrayOf(7.0, 0.7, 5.001545),
            doubleArrayOf(7.0, 1.0, 5.120539),
            doubleArrayOf(7.0, 1.907, 5.029617),
            doubleArrayOf(7.0, 2.0, 5.000000),
            doubleArrayOf(7.0, 2.1, 4.999950),
            doubleArrayOf(7.0, 2.407, 4.999990),
            doubleArrayOf(7.0, 2.5, 4.997752),
            doubleArrayOf(7.0, 3.1, -1.646440),
            doubleArrayOf(7.0, 3.7, -6.728229),
            doubleArrayOf(7.0, 4.6, 13.374669),
            doubleArrayOf(7.0, 5.8, 5.000000),
    )

    @Test
    fun theKotlinModelMatchesThePythonPrototype() {
        val m = motion(refScript)
        for ((lv, rows) in listOf(ActingParams.NORMAL_LEVEL to normalRef, ActingParams.BOLD_LEVEL to boldRef)) {
            for (r in rows) {
                val v = ActingMotionModel.actedAngle(m, r[0].toInt(), r[1], lv, seed)
                near("bone ${r[0].toInt()} t=${r[1]}", r[2], v, 3e-3)
            }
        }
    }

    @Test
    fun theHoldHashMatchesPython() {
        near("b0 k0", 0.133079668661, ActingMotionModel.unit(seed, 0, 0), 1e-9)
        near("b3 k2", 0.171546393064, ActingMotionModel.unit(seed, 3, 2), 1e-9)
        near("b9 k1", 0.090549063574, ActingMotionModel.unit(seed, 9, 1), 1e-9)
    }

    @Test
    fun chainLevelsComeFromTheRealBoneNames() {
        assertTrue(listOf(0, 1, 1, 2, 1, 2, 1, 2, 1, 2) == ActingMotionModel.LEVEL_BY_BONE.toList())
    }

    @Test
    fun theLegacyPoseIsExactlyTheEnginesBasePose() {
        val kfs = EngineHarness.compile(refScript)
        val m = ActingMotion(kfs, true)
        val e = EngineHarness.create(refScript, TestEnvelopes.silence(8f), AmplitudeSettings(enabled = false), kfs)
        var i = 0
        while (i <= 700) {
            val t = i / 120f
            e.seekTo(t)
            for (b in 0 until 10) near("bone $b t=$t", e.currentAngles[b].toDouble(), ActingMotionModel.legacyAngle(m, b, t.toDouble()), 1e-4)
            i++
        }
    }

    @Test
    fun jointsArriveAndPeakInChainOrder_torsoThenHeadThenForearm() {
        val m = motion(sparse)
        val lv = noHold(ActingParams.NORMAL_LEVEL)
        val think = m.keyframes[1]
        val bones = intArrayOf(0, 1, 3)  // torso, head, lower_arm_r
        val reached = DoubleArray(3)
        val peakAt = DoubleArray(3)
        for ((n, b) in bones.withIndex()) {
            val from = think.fromAngles[b].toDouble(); val to = think.toAngles[b].toDouble()
            var best = 0.0; var prev = ActingMotionModel.actedAngle(m, b, 0.9, lv, seed)
            reached[n] = -1.0
            var t = 0.9
            while (t < 2.0) {
                t += 1.0 / 480
                val v = ActingMotionModel.actedAngle(m, b, t, lv, seed)
                if (reached[n] < 0 && (v - from) / (to - from) >= 0.9) reached[n] = t
                val speed = abs(v - prev) * 480
                if (speed > best) { best = speed; peakAt[n] = t }
                prev = v
            }
        }
        assertTrue("arrival order ${reached.toList()}", reached[0] < reached[1] && reached[1] < reached[2])
        assertTrue("peak-velocity order ${peakAt.toList()}", peakAt[0] < peakAt[1] && peakAt[1] < peakAt[2])
    }

    @Test
    fun overshootNeverExceedsTheCapAndTheWindBackNeverExceedsItsCap() {
        val m = motion(sparse)
        for ((intensity, base) in levels) {
            val lv = noHold(base)
            for (b in 0 until 10) {
                val k = m.keyframes[1]
                val move = (k.toAngles[b] - k.fromAngles[b]).toDouble()
                if (move == 0.0) continue
                val level = ActingMotionModel.LEVEL_BY_BONE[b]
                var worstOver = 0.0; var worstDip = 0.0
                var t = 0.5
                while (t < 3.5) {
                    val v = ActingMotionModel.actedAngle(m, b, t, lv, seed)
                    if (t < 1.0 + level * lv.leadFollowDelaySec) worstDip = max(worstDip, -sign(move) * (v - k.fromAngles[b]))
                    else worstOver = max(worstOver, sign(move) * (v - k.toAngles[b]))
                    t += 1.0 / 240
                }
                assertTrue("$intensity bone $b overshoot $worstOver", worstOver <= lv.overshootCapDeg + 1e-3)
                assertTrue("$intensity bone $b wind-back $worstDip", worstDip <= lv.anticipationCapDeg + 1e-3)
            }
        }
    }

    @Test
    fun theLandingIsExactAfterTheSettleTime() {
        val m = motion(sparse)
        for ((intensity, base) in levels) {
            val lv = noHold(base)
            for (b in 0 until 10) {
                val to = m.keyframes[1].toAngles[b].toDouble()
                near("$intensity bone $b", to, ActingMotionModel.actedAngle(m, b, 3.5, lv, seed), 1e-5)
            }
        }
    }

    @Test
    fun aRetargetInTheMiddleOfAMoveHasNoPositionPop_whereTheLegacyPoseDoes() {
        val m = motion(refScript)
        val lv = noHold(ActingParams.NORMAL_LEVEL)
        for (b in intArrayOf(0, 1, 2, 3, 4, 5)) {
            var worstActed = 0.0; var worstLegacy = 0.0
            var t = 0.45
            var pa = ActingMotionModel.actedAngle(m, b, t, lv, seed); var pl = ActingMotionModel.legacyAngle(m, b, t)
            while (t < 0.9) {
                t += 1.0 / 480
                val a = ActingMotionModel.actedAngle(m, b, t, lv, seed); val l = ActingMotionModel.legacyAngle(m, b, t)
                worstActed = max(worstActed, abs(a - pa)); worstLegacy = max(worstLegacy, abs(l - pl))
                pa = a; pl = l
            }
            assertTrue("bone $b acted step $worstActed per 1/480 s", worstActed <= 8.0)
            if (b == 2) assertTrue("the legacy pose pops at the retarget (bone 2: $worstLegacy)", worstLegacy > 15.0)
        }
    }

    @Test
    fun walkAndJogChangesKeepOnlyLeadFollow() {
        val s = script(Triple(0.0f, "stand_straight", 0.4f to "ease_in_out"), Triple(1.0f, "walk_a", 0.6f to "ease_in_out"),
            Triple(5.0f, "stand_straight", 0.6f to "ease_in_out"))
        val m = motion(s)
        val lv = ActingParams.BOLD_LEVEL
        val lvStill = noHold(lv)   // the first (stand) hold is a normal moving hold; only the walk hold must be exactly still
        assertTrue(m.locomotion[1] && m.locomotion[2] && !m.locomotion[0])
        for (b in intArrayOf(0, 6, 7, 8, 9)) {
            val k = m.keyframes[1]
            val move = (k.toAngles[b] - k.fromAngles[b]).toDouble()
            if (move == 0.0) continue
            var t = 0.2
            while (t < 4.5) {
                val v = ActingMotionModel.actedAngle(m, b, t, lvStill, seed)
                if (t < 1.0) near("no wind-back, bone $b t=$t", k.fromAngles[b].toDouble(), v, 1e-6)
                else assertTrue("no overshoot beyond the legacy shape, bone $b t=$t", sign(move) * (v - k.toAngles[b]) <= 1.0)
                t += 0.01
            }
            near("held exactly, no drift, bone $b", k.toAngles[b].toDouble(), ActingMotionModel.actedAngle(m, b, 4.5, lv, seed), 1e-5)
        }
    }

    @Test
    fun theMovingHoldIsBoundedSeededAndZeroWhileMoving() {
        var peak = 0.0
        var t = 0.0
        while (t < 120.0) { for (b in 0 until 10) peak = max(peak, abs(ActingMotionModel.drift(seed, b, t, 0.3))); t += 0.05 }
        assertTrue("drift peak $peak", peak <= 0.3 + 1e-9)
        assertTrue(ActingMotionModel.drift(1L, 3, 10.0, 0.3) != ActingMotionModel.drift(2L, 3, 10.0, 0.3))
        assertTrue(ActingMotionModel.drift(7L, 3, 10.0, 0.3) == ActingMotionModel.drift(7L, 3, 10.0, 0.3))
        val m = motion(sparse)
        val lv = ActingParams.NORMAL_LEVEL
        // Late in a long hold the joint is within the hold amplitude of its target; at the start of a move there is no drift.
        val to = m.keyframes[1].toAngles[1].toDouble()
        assertTrue(abs(ActingMotionModel.actedAngle(m, 1, 3.5, lv, seed) - to) <= lv.holdDriftDeg + 1e-6)
        near("no drift before the first move", m.keyframes[0].fromAngles[1].toDouble(), ActingMotionModel.actedAngle(m, 1, 0.0, lv, seed), 1e-9)
    }

    @Test
    fun everyFixtureStaysFiniteAndBoundedAtEveryIntensity() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val s = Fixtures.script(name)
            val kfs = EngineHarness.compile(s)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            for (i in listOf(ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD)) {
                val e = EngineHarness.create(s, envelope, keyframes = kfs)
                e.actingContext = ActingContext.create(i.name, "p-$name")
                for (f in 0..(total * EngineHarness.ENV_FPS).toInt()) {
                    EngineHarness.seekFrame(e, envelope, f.toFloat() / EngineHarness.ENV_FPS)
                    for (b in 0 until 10) {
                        val v = e.renderAngles[b]
                        assertTrue("$name $i frame $f bone $b is $v", v.isFinite() && abs(v - e.currentAngles[b]) <= 400f)
                    }
                }
                assertTrue("$name $i: no fault (${e.actingFault.message})", e.actingFault.count == 0)
            }
        }
    }

    @Test
    fun theLayerReturnsTheOutputFrameOnceAMotionExistsAndTheBaseWhenOffOrEmpty() {
        val m = motion(sparse)
        val base = ActingFrame(FloatArray(10) { m.keyframes[0].fromAngles[it] }).also { it.motion = m }
        val out = ActingFrame(FloatArray(10))
        for (i in listOf(ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD)) {
            assertTrue("$i", ActingLayer.apply(base, ActingContext.create(i.name, "p"), 1.02f, out) === out)
        }
        assertTrue(ActingLayer.apply(base, ActingContext.create("OFF", "p"), 1.02f, out) === base)
        val empty = ActingFrame(FloatArray(10)).also { it.motion = ActingMotion(emptyList(), true) }
        assertTrue(ActingLayer.apply(empty, ActingContext.create("NORMAL", "p"), 1f, out) === empty)
        val none = ActingFrame(FloatArray(10))
        assertTrue(ActingLayer.apply(none, ActingContext.create("NORMAL", "p"), 1f, out) === none)
    }

    @Test
    fun actingChangesTheRenderedPoseDuringAMoveAndLeavesTheBasePoseAlone() {
        val e = EngineHarness.create(sparse, TestEnvelopes.silence(8f), AmplitudeSettings(enabled = false))
        e.actingContext = ActingContext.create("NORMAL", "p")
        e.seekTo(1.03f)
        assertTrue(e.renderAngles !== e.currentAngles)
        var differs = false
        for (b in 0 until 10) if (abs(e.renderAngles[b] - e.currentAngles[b]) > 1f) differs = true
        assertTrue("mid-move the acted pose differs from the legacy pose", differs)
        val legacy = EngineHarness.create(sparse, TestEnvelopes.silence(8f), AmplitudeSettings(enabled = false))
        legacy.seekTo(1.03f)
        assertArrayEquals("currentAngles is still the base state", legacy.currentAngles, e.currentAngles, 0f)
    }

    @Test
    fun theSameInputsGiveTheSameOutputAndSeekEqualsSequential() {
        val m = motion(refScript)
        val lv = ActingParams.NORMAL_LEVEL
        var t = 0.0
        while (t < 6.0) {
            for (b in 0 until 10) {
                val a = ActingMotionModel.actedAngle(m, b, t, lv, seed)
                assertTrue(a == ActingMotionModel.actedAngle(m, b, t, lv, seed))
                assertTrue(a.isFinite())
            }
            t += 0.0173
        }
    }
}
