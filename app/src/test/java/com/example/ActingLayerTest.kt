package com.example

import com.example.data.AmplitudeSettings
import com.example.data.AppearanceSettings
import com.example.engine.PlaybackEngine
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingFault
import com.example.engine.acting.ActingFrame
import com.example.engine.acting.ActingIntensity
import com.example.engine.acting.ActingLayer
import com.example.engine.acting.ActingParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.1 (acting plumbing and switch): identity when OFF, determinism, seek equals sequential, fail-soft, the settings
 * default, and that a context changes nothing the renderers read when OFF. The motion stage itself is in ActingMotionTest.
 */
class ActingLayerTest {

    private val noAmplitude = AmplitudeSettings(enabled = false)
    private val allIntensities = listOf(ActingIntensity.OFF, ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD)

    private fun withActing(base: PlaybackEngine, intensity: ActingIntensity?, projectId: String = "p-test"): PlaybackEngine {
        if (intensity != null) base.actingContext = ActingContext.create(intensity.name, projectId)
        return base
    }

    // ── Settings and parsing ────────────────────────────────────────────────

    @Test
    fun theStoredDefaultIsNormalAndParsingDegradesGracefully() {
        assertEquals("NORMAL", AppearanceSettings().actingIntensity)
        assertEquals(ActingIntensity.NORMAL, ActingIntensity.parse(AppearanceSettings().actingIntensity))
        assertEquals(ActingIntensity.OFF, ActingIntensity.parse("OFF"))
        assertEquals(ActingIntensity.SUBTLE, ActingIntensity.parse("subtle"))
        assertEquals(ActingIntensity.BOLD, ActingIntensity.parse("  Bold "))
        // I4: an unknown, blank or missing value is read as the default, never a crash.
        assertEquals(ActingIntensity.NORMAL, ActingIntensity.parse("LOUD"))
        assertEquals(ActingIntensity.NORMAL, ActingIntensity.parse(""))
        assertEquals(ActingIntensity.NORMAL, ActingIntensity.parse(null))
        assertEquals(listOf("OFF", "SUBTLE", "NORMAL", "BOLD"), ActingIntensity.SELECTABLE_NAMES)
        assertEquals(ActingIntensity.SELECTABLE_NAMES, allIntensities.map { it.name })
    }

    @Test
    fun theParameterTableMatchesTheBriefAndGrowsWithIntensity() {
        val s = ActingParams.levelFor(ActingIntensity.SUBTLE)
        val n = ActingParams.levelFor(ActingIntensity.NORMAL)
        val b = ActingParams.levelFor(ActingIntensity.BOLD)
        // Brief Part C, Phase 2 table (starting points, not truth).
        assertTrue(s.leadFollowDelaySec == 0.012f && n.leadFollowDelaySec == 0.025f && b.leadFollowDelaySec == 0.040f)
        assertTrue(s.durationStretchPerLevel == 0.03f && n.durationStretchPerLevel == 0.06f && b.durationStretchPerLevel == 0.09f)
        assertTrue(s.anticipationFraction == 0.03f && s.anticipationCapDeg == 4f && n.anticipationCapDeg == 6f && b.anticipationCapDeg == 9f)
        assertTrue(s.overshootFraction == 0.025f && s.overshootCapDeg == 3f && n.overshootCapDeg == 5f && b.overshootCapDeg == 8f)
        assertTrue(s.expressionBlendSec == 0.12f && n.expressionBlendSec == 0.18f && b.expressionBlendSec == 0.24f)
        for (pick in listOf<(com.example.engine.acting.ActingLevel) -> Float>(
            { it.leadFollowDelaySec }, { it.durationStretchPerLevel }, { it.anticipationFraction },
            { it.anticipationCapDeg }, { it.overshootFraction }, { it.overshootCapDeg }, { it.expressionBlendSec }
        )) {
            assertTrue(pick(s) < pick(n) && pick(n) < pick(b))
        }
        assertTrue(ActingParams.levelFor(ActingIntensity.OFF).leadFollowDelaySec == 0f)
    }

    @Test
    fun theSeedIsStableFnv1aOverTheProjectId() {
        // Reference values computed in Python (FNV-1a, 64 bit, signed): "" is the offset basis.
        assertTrue(ActingContext.seedForProject("") == -3750763034362895579L)
        assertTrue(ActingContext.seedForProject("a") == -5808556873153909620L)
        assertTrue(ActingContext.seedForProject("project-1") == 2656245455449127464L)
        assertTrue(ActingContext.seedForProject("project-1") != ActingContext.seedForProject("project-2"))
    }

    @Test
    fun theEnvelopeIsReadWithAClampedIndexAndIsZeroWithoutOne() {
        val ctx = ActingContext(ActingIntensity.NORMAL, 1L, floatArrayOf(0.1f, 0.5f, 0.9f), 30)
        assertTrue(ctx.envelopeAt(0f) == 0.1f)
        assertTrue(ctx.envelopeAt(1f / 30f + 0.001f) == 0.5f)
        assertTrue(ctx.envelopeAt(99f) == 0.9f)
        assertTrue(ctx.envelopeAt(-3f) == 0.1f)
        assertTrue(ActingContext(ActingIntensity.NORMAL, 1L).envelopeAt(1f) == 0f)
        assertEquals(ActingIntensity.BOLD, ctx.withIntensity(ActingIntensity.BOLD).intensity)
        assertTrue(ctx.withIntensity(ActingIntensity.BOLD).seed == 1L)
    }

    // ── The layer ───────────────────────────────────────────────────────────

    @Test
    fun withNoContextOrIntensityOffTheLayerReturnsTheBaseFrameItself() {
        val base = ActingFrame(floatArrayOf(1f, 2f, 3f))
        val out = ActingFrame(FloatArray(3))
        assertTrue(ActingLayer.apply(base, null, 1.5f, out) === base)
        assertTrue(ActingLayer.apply(base, ActingContext.create("OFF", "p"), 1.5f, out) === base)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), base.angles, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out.angles, 0f)
    }

    @Test
    fun withoutAMotionTheLayerHasNothingToActOnAndReturnsTheBaseFrame() {
        // WP2.2 replaced the "no stage yet" test: the stage needs the timeline (ActingFrame.motion); see ActingMotionTest.
        val base = ActingFrame(floatArrayOf(10f, -20f))
        val out = ActingFrame(FloatArray(2))
        for (i in allIntensities) {
            assertTrue("$i", ActingLayer.apply(base, ActingContext.create(i.name, "p"), 0.7f, out) === base)
        }
        assertArrayEquals(floatArrayOf(10f, -20f), base.angles, 0f)
    }

    @Test
    fun anExceptionInsideTheLayerFallsBackToTheBaseFrameAndIsRecordedOnce() {
        val fault = ActingFault()
        val base = ActingFrame(FloatArray(2))
        assertTrue(fault.count == 0 && fault.message == null)
        val first = fault.guard(base) { throw IllegalStateException("boom") }
        assertTrue(first === base)
        val second = fault.guard(base) { throw IllegalArgumentException("later") }
        assertTrue(second === base)
        assertEquals(2, fault.count)
        assertEquals("IllegalStateException: boom", fault.message)
        val ok = ActingFrame(FloatArray(2))
        assertTrue(fault.guard(base) { ok } === ok)
    }

    // ── The engine plumbing ─────────────────────────────────────────────────

    @Test
    fun withActingOffTheRenderAnglesAreTheLegacyArrayItself() {
        val script = Fixtures.script("02_poses_expressions")
        val e = withActing(EngineHarness.create(script, TestEnvelopes.silence(20f), noAmplitude), ActingIntensity.OFF)
        e.seekTo(2.5f)
        assertTrue(e.renderAngles === e.currentAngles)
        assertTrue(e.actingFault.count == 0)
    }

    @Test
    fun offLeavesEveryFixtureFrameBitIdenticalToTheLegacyEngine() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val frames = (total * EngineHarness.ENV_FPS).toInt()
            for (intensity in listOf(ActingIntensity.OFF)) {
                val legacy = EngineHarness.create(script, envelope, keyframes = kfs)
                val acted = withActing(EngineHarness.create(script, envelope, keyframes = kfs), intensity)
                for (i in 0..frames) {
                    val t = i.toFloat() / EngineHarness.ENV_FPS
                    EngineHarness.seekFrame(legacy, envelope, t)
                    EngineHarness.seekFrame(acted, envelope, t)
                    assertArrayEquals("$name $intensity frame $i", legacy.currentAngles, acted.renderAngles, 0f)
                    EngineAsserts.assertSameState("$name $intensity frame $i", legacy, acted, 0f)
                }
                assertTrue("$name $intensity: no fault", acted.actingFault.count == 0)
            }
        }
    }

    @Test
    fun livePlaybackIsAlsoUnchangedWhenActingIsOff() {
        val script = Fixtures.script("06_long_dense")
        val kfs = EngineHarness.compile(script)
        val envelope = GoldenSnapshot.envelopeFor(EngineHarness.totalSec(kfs, 0f))
        for (intensity in listOf(ActingIntensity.OFF)) {
            val legacy = EngineHarness.create(script, envelope, keyframes = kfs)
            val acted = withActing(EngineHarness.create(script, envelope, keyframes = kfs), intensity)
            for (i in 0 until 600) {
                val amp = EngineHarness.rawAmp(envelope, i / 60f)
                legacy.tick(1f / 60f, amp)
                acted.tick(1f / 60f, amp)
                assertArrayEquals("$intensity tick $i", legacy.currentAngles, acted.renderAngles, 0f)
            }
        }
    }

    @Test
    fun theSameContextGivesTheSameOutputTwice() {
        val script = Fixtures.script("03_camera_scene_captions")
        val kfs = EngineHarness.compile(script)
        val total = EngineHarness.totalSec(kfs, 0f)
        val envelope = GoldenSnapshot.envelopeFor(total)
        val a = withActing(EngineHarness.create(script, envelope, keyframes = kfs), ActingIntensity.NORMAL, "same")
        val b = withActing(EngineHarness.create(script, envelope, keyframes = kfs), ActingIntensity.NORMAL, "same")
        for (i in 0..(total * EngineHarness.ENV_FPS).toInt()) {
            val t = i.toFloat() / EngineHarness.ENV_FPS
            EngineHarness.seekFrame(a, envelope, t)
            EngineHarness.seekFrame(b, envelope, t)
            assertArrayEquals("frame $i", a.renderAngles, b.renderAngles, 0f)
        }
    }

    @Test
    fun seekEqualsSequentialForTheRenderedPose() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val walker = withActing(EngineHarness.create(script, envelope, noAmplitude, kfs), ActingIntensity.NORMAL, name)
            val checkpoints = GoldenSnapshot.checkpoints(kfs, total)
            // Sequential: advance frame by frame (export order). Seek: a fresh engine jumps straight to the same time.
            var i = 0
            for (t in checkpoints) {
                val target = (t * EngineHarness.ENV_FPS).toInt()
                while (i <= target) {
                    EngineHarness.seekFrame(walker, envelope, i.toFloat() / EngineHarness.ENV_FPS)
                    i++
                }
                val fresh = withActing(EngineHarness.create(script, envelope, noAmplitude, kfs), ActingIntensity.NORMAL, name)
                fresh.seekTo(target.toFloat() / EngineHarness.ENV_FPS)
                assertArrayEquals("$name t=$t", fresh.renderAngles, walker.renderAngles, 1e-4f)
            }
        }
    }

    @Test
    fun changingOrClearingTheContextTakesEffectAtOnce() {
        val script = Fixtures.script("02_poses_expressions")
        val e = EngineHarness.create(script, TestEnvelopes.silence(20f), noAmplitude)
        e.seekTo(3f)
        e.actingContext = ActingContext.create("BOLD", "p")
        assertTrue(e.renderAngles !== e.currentAngles) // the motion stage is active at BOLD (WP2.2)
        e.actingContext = ActingContext.create("OFF", "p")
        assertTrue(e.renderAngles === e.currentAngles)
        e.actingContext = null
        assertTrue(e.renderAngles === e.currentAngles)
        assertTrue(e.actingFault.count == 0)
    }
}
