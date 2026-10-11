package com.example

import com.example.data.AmplitudeSettings
import com.example.engine.BakedKeyframe
import com.example.engine.PlaybackEngine
import com.example.engine.SoundEffectCue
import com.example.engine.TimelineCompiler
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WP0.4 property tests over the fixture corpus, run against the baseline engine (invariant I2 and friends).
 * They assert only what already holds at the baseline. Known gap, owned by WP0.5: during a transition the live
 * path (`tick`) and the export path (`seekToWithAmplitude`) evaluate eases differently (spring parity, B1), so
 * live-versus-seek equality is asserted only once an event has settled.
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

    private fun assertSameState(label: String, a: PlaybackEngine, b: PlaybackEngine) {
        assertArrayEquals("$label: angles", a.currentAngles, b.currentAngles, 1e-6f)
        assertEquals("$label: expression", a.currentExpression, b.currentExpression)
        assertEquals(
            "$label: camera",
            listOf(a.currentCameraZoom, a.currentCameraPanX, a.currentCameraPanY, a.currentShakeIntensity),
            listOf(b.currentCameraZoom, b.currentCameraPanX, b.currentCameraPanY, b.currentShakeIntensity)
        )
        assertEquals("$label: figure", a.currentFigureOverrides, b.currentFigureOverrides)
        assertEquals(
            "$label: scene",
            listOf(a.currentSkyColor, a.currentGroundColor, a.currentHorizonY, a.currentSceneShape, a.currentSceneAtmosphere),
            listOf(b.currentSkyColor, b.currentGroundColor, b.currentHorizonY, b.currentSceneShape, b.currentSceneAtmosphere)
        )
        assertEquals("$label: caption", a.currentCaption, b.currentCaption)
        assertEquals("$label: overlays", a.currentOverlays, b.currentOverlays)
        assertEquals("$label: eyes", a.currentEyeOpenness, b.currentEyeOpenness, 0f)
    }

    private fun near(label: String, what: String, a: Float?, b: Float?) {
        require((a == null) == (b == null)) { "$label: $what null mismatch ($a vs $b)" }
        if (a != null && b != null) require(abs(a - b) <= 1e-4f) { "$label: $what $a vs $b" }
    }

    /** True once the active keyframe finished its transition more than 1.5 s ago, so the live spring has converged. */
    private fun isSettled(keyframes: List<BakedKeyframe>, t: Float): Boolean {
        var idx = -1
        for (i in keyframes.indices) {
            if (keyframes[i].timeSec <= t) idx = i else break
        }
        if (idx < 0) return false
        val kf = keyframes[idx]
        return t - kf.timeSec >= kf.duration + 1.5f
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
                assertSameState("$name at $t", fresh, walker)
            }
        }
    }

    @Test
    fun livePlaybackMatchesSeekOnceTheEngineHasSettled() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            if (kfs.isEmpty()) continue
            val last = kfs.last()
            val end = last.timeSec + last.duration + 3f
            val silent = TestEnvelopes.silence(ceil(end))
            val live = EngineHarness.create(script, silent, noAmplitude, kfs)
            val seek = EngineHarness.create(script, silent, noAmplitude, kfs)
            var compared = 0
            var worst = 0f
            for (i in 1..(end * EngineHarness.ENV_FPS).toInt()) {
                live.tick(1f / EngineHarness.ENV_FPS, 0f)
                val t = live.currentTimeSec
                if (!isSettled(kfs, t)) continue
                seek.seekTo(t)
                for (b in live.currentAngles.indices) worst = max(worst, abs(live.currentAngles[b] - seek.currentAngles[b]))
                val label = "$name at $t"
                near(label, "zoom", live.currentCameraZoom, seek.currentCameraZoom)
                near(label, "panX", live.currentCameraPanX, seek.currentCameraPanX)
                near(label, "panY", live.currentCameraPanY, seek.currentCameraPanY)
                near(label, "horizon", live.currentHorizonY, seek.currentHorizonY)
                val fl = live.currentFigureOverrides
                val fs = seek.currentFigureOverrides
                near(label, "figureX", fl.x, fs.x)
                near(label, "figureY", fl.y, fs.y)
                near(label, "figureScale", fl.scale, fs.scale)
                near(label, "headScale", fl.headScale, fs.headScale)
                near(label, "figureOpacity", fl.opacity, fs.opacity)
                assertEquals("$label: expression", live.currentExpression, seek.currentExpression)
                assertEquals("$label: scene", live.currentSceneShape + live.currentSceneAtmosphere, seek.currentSceneShape + seek.currentSceneAtmosphere)
                assertEquals("$label: caption", live.currentCaption, seek.currentCaption)
                assertEquals("$label: overlays", live.currentOverlays, seek.currentOverlays)
                compared++
            }
            assertTrue("$name: no settled frame was compared", compared > 0)
            assertTrue("$name: worst angle difference $worst over $compared settled frames", worst <= 1e-4f)
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
