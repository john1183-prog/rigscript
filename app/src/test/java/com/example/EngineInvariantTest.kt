package com.example

import com.example.data.AmplitudeSettings
import com.example.engine.PlaybackEngine
import com.example.engine.SoundEffectCue
import com.example.engine.TimelineCompiler
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WP0.4 property tests over the fixture corpus (invariant I2 and friends): deterministic snapshots, no NaN or
 * infinity, seek independent of earlier seeks, cue order. Live-versus-seek parity is in [SpringParityTest].
 */
class EngineInvariantTest {

    private val noAmplitude = AmplitudeSettings(enabled = false)

    private fun finite(v: Float?): Boolean = v == null || (!v.isNaN() && !v.isInfinite())

    private fun require(ok: Boolean, message: () -> String) {
        if (!ok) fail(message())
    }

    private fun checkFinite(label: String, e: PlaybackEngine) {
        for (a in e.currentAngles) require(finite(a)) { "$label: angle $a" }
        val f = e.currentFigureOverrides
        val scalars = listOf(
            e.currentCameraZoom, e.currentCameraPanX, e.currentCameraPanY, e.currentShakeIntensity,
            e.currentHipBobOffset, e.currentAmplitude, e.currentEyeOpenness, e.currentHorizonY,
            f.x, f.y, f.scale, f.headScale, f.opacity, f.groundLineYFraction
        )
        require(scalars.all { finite(it) }) { "$label: scalars $scalars" }
        for (o in e.currentOverlays) {
            val own = listOf(
                o.localX, o.localY, o.localRotationDeg, o.localScale, o.opacity, o.fontSize,
                o.glowRadius, o.width, o.height, o.radius, o.velocityAngleDeg
            )
            require(own.all { finite(it) }) { "$label: overlay '${o.id}' $own" }
            require(o.trailPointsLocal.all { finite(it.first) && finite(it.second) }) { "$label: overlay '${o.id}' trail" }
            require((o.figurePoseAngles ?: emptyList()).all { finite(it) }) { "$label: overlay '${o.id}' pose angles" }
        }
    }

    @Test
    fun bakedKeyframesAreOrderedFiniteAndPositive() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val kfs = EngineHarness.compile(Fixtures.script(name))
            assertTrue("$name: keyframes are sorted by time", kfs.zipWithNext().all { (a, b) -> b.timeSec >= a.timeSec })
            assertTrue("$name: durations are positive", kfs.all { it.duration > 0f && finite(it.duration) })
            assertTrue("$name: angles are finite", kfs.all { kf -> kf.fromAngles.all { finite(it) } && kf.toAngles.all { finite(it) } })
        }
    }

    @Test
    fun snapshotsAreDeterministic() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val first = GoldenSnapshot.build(name)
            val second = GoldenSnapshot.build(name)
            assertTrue("$name: two builds of the same snapshot differ", first == second)
        }
    }

    @Test
    fun snapshotsHaveTheExpectedShapeAndNoNaN() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val text = GoldenSnapshot.build(name)
            val lines = text.lines()
            assertEquals("$name: header", "golden 1 fixture=$name", lines[0])
            val checkpoints = lines.count { it.startsWith("cp t=") }
            assertTrue("$name: $checkpoints checkpoints", checkpoints in 8..30)
            assertTrue("$name: every ang line has 10 numbers", lines.filter { it.startsWith("ang ") }.all { it.split(' ').size == 11 })
            assertFalse("$name: NaN in snapshot", text.contains("NaN"))
            assertFalse("$name: Infinity in snapshot", text.contains("Infinity"))
            assertTrue("$name: snapshot size ${text.length}", text.length < 150_000)
        }
    }

    @Test
    fun everyFrameOfEveryFixtureIsFinite() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val engine = EngineHarness.create(script, envelope, keyframes = kfs)
            val frames = (total * EngineHarness.ENV_FPS).toInt()
            for (i in 0..frames) {
                EngineHarness.seekFrame(engine, envelope, i.toFloat() / EngineHarness.ENV_FPS)
                checkFinite("$name frame $i", engine)
            }
        }
    }

    @Test
    fun seekIsIndependentOfEarlierSeeks() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val times = GoldenSnapshot.checkpoints(kfs, total)
            val scrambled = times.sortedBy { (it * 7919f).toInt() % 97 }
            val walker = EngineHarness.create(script, envelope, noAmplitude, kfs)
            for (t in scrambled) {
                walker.seekTo(t)
                val fresh = EngineHarness.create(script, envelope, noAmplitude, kfs)
                fresh.seekTo(t)
                EngineAsserts.assertSameState("$name at $t", fresh, walker)
            }
        }
    }

    @Test
    fun soundEffectCuesAreSortedAndFireInOrderAtMostOnceDuringLivePlayback() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val cues = TimelineCompiler.extractSoundEffectCues(script)
            assertTrue("$name: cues are sorted", cues.zipWithNext().all { (a, b) -> b.timeSec >= a.timeSec })
            val captions = TimelineCompiler.extractCaptions(script)
            assertTrue("$name: captions are sorted", captions.zipWithNext().all { (a, b) -> b.startSec >= a.startSec })
            assertTrue("$name: captions last a positive time", captions.all { it.endSec > it.startSec })
            val kfs = EngineHarness.compile(script)
            if (kfs.isEmpty()) continue
            val last = kfs.last()
            val end = last.timeSec + last.duration + 1f
            val live = EngineHarness.create(script, TestEnvelopes.silence(ceil(end)), noAmplitude, kfs)
            val fired = mutableListOf<SoundEffectCue>()
            for (i in 1..(end * EngineHarness.ENV_FPS).toInt()) {
                live.tick(1f / EngineHarness.ENV_FPS, 0f)
                fired += live.pollTriggeredSoundEffects()
            }
            assertTrue("$name: cues fire in time order", fired.zipWithNext().all { (a, b) -> b.timeSec >= a.timeSec })
            // A cue at exactly t = 0 is not asserted either way: live ticking scans (lastScan, now], so it
            // never fires there (baseline behaviour, logged for WP0.5).
            val expected = cues.filter { it.timeSec > 0f && it.timeSec <= live.currentTimeSec }
            assertEquals("$name: every cue after t = 0 fires exactly once", expected.size, fired.count { it.timeSec > 0f })
        }
    }
}
