package com.example

import com.example.data.AmplitudeSettings
import com.example.data.AnimScript
import com.example.data.ScriptEvent
import com.example.engine.PlaybackEngine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP0.5, spring parity (B1): live playback (`tick`) and seek/export (`seekToWithAmplitude`) evaluate the
 * timeline identically, at every frame, for every ease and spring setting. Before WP0.5 the preview chased the
 * eased value with a fixed stiffness-320, damping-32 spring and ignored the event's own parameters, so preview
 * and export diverged for any non-default setting.
 */
class SpringParityTest {

    private val noAmplitude = AmplitudeSettings(enabled = false)

    private fun maxAngleDiff(a: PlaybackEngine, b: PlaybackEngine): Float {
        var worst = 0f
        for (i in a.currentAngles.indices) worst = max(worst, abs(a.currentAngles[i] - b.currentAngles[i]))
        return worst
    }

    /** A script that makes the figure change pose five times with the given ease and spring parameters. */
    private fun matrixScript(ease: String, stiffness: Float, damping: Float, overlapping: Boolean = false): AnimScript {
        fun ev(t: Float, pose: String, d: Float) = ScriptEvent(
            timeSec = t, pose = pose, duration = d, ease = ease, springStiffness = stiffness, springDamping = damping
        )
        return AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f),
                ev(1f, "wave", 0.8f),
                ev(if (overlapping) 1.3f else 2.5f, "shrug", 0.8f),
                ev(3.2f, "point_up", 0.4f),
                ev(4f, "stand_straight", 0.6f)
            )
        )
    }

    @Test
    fun liveMatchesSeekAtEveryFrameOfEveryFixture() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            if (kfs.isEmpty()) continue
            val end = kfs.last().timeSec + kfs.last().duration + 2f
            val silent = TestEnvelopes.silence(ceil(end))
            val live = EngineHarness.create(script, silent, noAmplitude, kfs)
            val seek = EngineHarness.create(script, silent, noAmplitude, kfs)
            var worst = 0f
            for (i in 1..(end * EngineHarness.ENV_FPS).toInt()) {
                live.tick(1f / EngineHarness.ENV_FPS, 0f)
                seek.seekTo(live.currentTimeSec)
                worst = max(worst, maxAngleDiff(live, seek))
                EngineAsserts.assertSameState("$name frame $i", live, seek, 1e-4f)
            }
            assertTrue("$name: worst angle difference $worst", worst <= 1e-4f)
        }
    }

    @Test
    fun liveMatchesTheExportLoopWithAmplitudeMotionToo() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            if (kfs.isEmpty()) continue
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val live = EngineHarness.create(script, envelope, keyframes = kfs)
            val export = EngineHarness.create(script, envelope, keyframes = kfs)
            var worst = 0f
            for (i in 1..(total * EngineHarness.ENV_FPS).toInt()) {
                val dt = 1f / EngineHarness.ENV_FPS
                val t = live.currentTimeSec + dt
                val amp = EngineHarness.rawAmp(envelope, t)
                val mouth = EngineHarness.mouthFor(amp)
                live.rawMouthShape = mouth
                live.tick(dt, amp)
                export.seekToWithAmplitude(live.currentTimeSec, amp, mouth)
                worst = max(worst, maxAngleDiff(live, export))
                EngineAsserts.assertSameState("$name frame $i", live, export, 1e-4f)
                assertEquals("$name frame $i: mouth", export.currentMouthShape, live.currentMouthShape)
                assertEquals("$name frame $i: hip", export.currentHipBobOffset, live.currentHipBobOffset, 1e-4f)
                assertEquals("$name frame $i: amplitude", export.currentAmplitude, live.currentAmplitude, 1e-4f)
            }
            assertTrue("$name: worst angle difference with amplitude motion $worst", worst <= 1e-4f)
        }
    }

    @Test
    fun liveMatchesSeekForEveryEaseAndSpringSetting() {
        val eases = listOf("spring", "elastic_out", "bounce", "ease_in_out", "linear", "rigid")
        val settings = listOf(280f to 28f, 120f to 12f, 400f to 40f, 60f to 6f, 900f to 20f, 50f to 50f)
        var runs = 0
        for (ease in eases) for ((k, d) in settings) for (overlapping in listOf(false, true)) {
            val script = matrixScript(ease, k, d, overlapping)
            val kfs = EngineHarness.compile(script)
            for (all in listOf(true, false)) for (dt in listOf(1f / 30f, 1f / 60f)) {
                val settingsFor = AmplitudeSettings(enabled = false, easeAllWithSpring = all)
                val silent = TestEnvelopes.silence(8f)
                val live = EngineHarness.create(script, silent, settingsFor, kfs)
                val seek = EngineHarness.create(script, silent, settingsFor, kfs)
                var worst = 0f
                for (i in 1..(6f / dt).toInt()) {
                    live.tick(dt, 0f)
                    seek.seekTo(live.currentTimeSec)
                    worst = max(worst, maxAngleDiff(live, seek))
                }
                assertTrue("$ease k=$k d=$d overlapping=$overlapping easeAll=$all dt=$dt: worst $worst", worst <= 1e-4f)
                runs++
            }
        }
        assertEquals(6 * 6 * 2 * 2 * 2, runs)
    }

    @Test
    fun liveHonoursTheEventsOwnSpringParameters() {
        // Two scripts that differ only in stiffness and damping: the live angles must differ mid-transition.
        fun angleAt(stiffness: Float, damping: Float): Float {
            val script = matrixScript("spring", stiffness, damping)
            val live = EngineHarness.create(script, TestEnvelopes.silence(8f), noAmplitude)
            repeat((1.3f * EngineHarness.ENV_FPS).toInt()) { live.tick(1f / EngineHarness.ENV_FPS, 0f) }
            return live.currentAngles[3]
        }
        val a = angleAt(280f, 28f)
        val b = angleAt(60f, 6f)
        assertTrue("angles $a and $b should differ by more than a degree", abs(a - b) > 1f)
    }

    @Test
    fun hotReloadWhilePlayingEasesInsteadOfPoppingThenMatchesSeek() {
        val standing = AnimScript(events = listOf(ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f)))
        val waving = AnimScript(events = listOf(ScriptEvent(timeSec = 0f, pose = "wave", duration = 0.3f)))
        val kfsStanding = EngineHarness.compile(standing)
        val kfsWaving = EngineHarness.compile(waving)
        val silent = TestEnvelopes.silence(8f)

        val target = EngineHarness.create(waving, silent, noAmplitude, kfsWaving)
        target.seekTo(1f)
        val before = EngineHarness.create(standing, silent, noAmplitude, kfsStanding)
        before.seekTo(1f)
        var jump = 0f
        for (i in target.currentAngles.indices) jump = max(jump, abs(target.currentAngles[i] - before.currentAngles[i]))
        assertTrue("the two poses differ ($jump)", jump > 20f)

        // Edited while playing (snapToCurrentTime = false): no pop on the first frames, converged after a second or two.
        val live = EngineHarness.create(standing, silent, noAmplitude, kfsStanding)
        repeat(30) { live.tick(1f / 30f, 0f) }
        live.loadTimeline(kfsWaving, snapToCurrentTime = false)
        live.tick(1f / 30f, 0f)
        var moved = 0f
        for (i in live.currentAngles.indices) moved = max(moved, abs(live.currentAngles[i] - before.currentAngles[i]))
        assertTrue("first frame after the reload moved $moved of $jump", moved < 0.5f * jump)
        repeat(90) { live.tick(1f / 30f, 0f) }
        val seek = EngineHarness.create(waving, silent, noAmplitude, kfsWaving)
        seek.seekTo(live.currentTimeSec)
        assertTrue("converged: ${maxAngleDiff(live, seek)}", maxAngleDiff(live, seek) <= 1e-4f)

        // Edited while paused (snapToCurrentTime = true, the default): the new pose shows at once.
        val paused = EngineHarness.create(standing, silent, noAmplitude, kfsStanding)
        repeat(30) { paused.tick(1f / 30f, 0f) }
        paused.loadTimeline(kfsWaving)
        val pausedSeek = EngineHarness.create(waving, silent, noAmplitude, kfsWaving)
        pausedSeek.seekTo(paused.currentTimeSec)
        assertTrue("snapped: ${maxAngleDiff(paused, pausedSeek)}", maxAngleDiff(paused, pausedSeek) <= 1e-4f)
    }
}
