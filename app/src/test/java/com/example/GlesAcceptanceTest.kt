package com.example

import com.example.engine.FrameMetrics
import com.example.engine.GlesAcceptance
import com.example.engine.GlesAcceptance.AudioMetrics
import com.example.engine.GlesAcceptance.CheckMetrics
import com.example.engine.GlesAcceptance.Source
import com.example.engine.GlesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.6a: the GLES acceptance thresholds and the pure verdict over measured numbers. */
class GlesAcceptanceTest {

    private fun frame(
        offset: Double = 0.2,
        diff: Double = 2.0,
        sharp: Double = 1.0,
        aa: Double = 1.0,
        mouth: Double = 1.0
    ) = FrameMetrics(offset, diff, sharp, aa, mouth)

    private fun audio(expected: Set<Source>) = AudioMetrics(
        hasAudioTrack = true,
        durationSec = 6.0,
        expectedDurationSec = 6.0,
        narrationAmplitude = if (Source.NARRATION in expected) 0.40 else 0.0,
        musicAmplitude = if (Source.MUSIC in expected) 0.10 else 0.0,
        sfxAmplitude = if (Source.SFX in expected) 0.05 else 0.0,
        sfxCueMin = if (Source.SFX in expected) 0.20 else 0.0,
        sfxOutside = 0.0
    )

    private fun good(frames: List<FrameMetrics> = List(4) { frame() }) = CheckMetrics(
        glesFailure = null,
        mixOnGles = audio(GlesAcceptance.ALL_SOURCES),
        sfxOnlyOnGles = audio(setOf(Source.SFX)),
        canvasDurationSec = 6.0,
        glesDurationSec = 6.02,
        frames = frames
    )

    private fun framesWith(index: Int, f: FrameMetrics) = List(4) { if (it == index) f else frame() }

    @Test
    fun aCleanRunIsEligible() {
        val e = GlesAcceptance.evaluate(good())
        assertTrue(e.failures.joinToString(), e.pass)
        assertTrue(e.failures.isEmpty())
        assertTrue(e.toState() === GlesState.Eligible)
    }

    @Test
    fun thresholdsAreTheCampaignBriefsStartingPoints() {
        assertTrue(GlesAcceptance.MAX_OFFSET_PX == 0.75)
        assertTrue(GlesAcceptance.MAX_MEAN_ABS_DIFF == 6.0)
        assertTrue(GlesAcceptance.MIN_LAPLACIAN_RATIO == 0.90)
        assertTrue(GlesAcceptance.MIN_EDGE_AA_RATIO == 0.85)
        assertTrue(GlesAcceptance.MAX_DURATION_DELTA_SEC == 0.100)
        assertEquals(4, GlesAcceptance.REQUIRED_FRAMES)
        assertTrue(GlesAcceptance.MIN_SFX_CUE_RATIO == 4.0)
        assertTrue(GlesAcceptance.MAX_ABSENT_AMPLITUDE < GlesAcceptance.MIN_PRESENT_AMPLITUDE)
    }

    @Test
    fun frameLimitsAreInclusive() {
        fun passes(f: FrameMetrics) = GlesAcceptance.evaluate(good(framesWith(1, f))).pass
        assertTrue(passes(frame(offset = GlesAcceptance.MAX_OFFSET_PX)))
        assertFalse(passes(frame(offset = 0.7501)))
        assertTrue(passes(frame(diff = GlesAcceptance.MAX_MEAN_ABS_DIFF)))
        assertFalse(passes(frame(diff = 6.0001)))
        assertTrue(passes(frame(sharp = GlesAcceptance.MIN_LAPLACIAN_RATIO)))
        assertFalse(passes(frame(sharp = 0.8999)))
        assertTrue(passes(frame(aa = GlesAcceptance.MIN_EDGE_AA_RATIO)))
        assertFalse(passes(frame(aa = 0.8499)))
    }

    @Test
    fun aFailingFrameNamesTheFrameAndTheCriterion() {
        val e = GlesAcceptance.evaluate(good(framesWith(2, frame(offset = 1.75))))
        assertEquals(1, e.failures.size)
        assertTrue(e.failures[0], e.failures[0].startsWith("frame 3 offset"))
        assertTrue(e.failures[0], e.failures[0].contains("1.7500") && e.failures[0].contains("0.7500"))
        val state = e.toState()
        assertTrue(state is GlesState.Ineligible)
        assertTrue((state as GlesState.Ineligible).reason.contains("frame 3 offset"))
    }

    @Test
    fun everyFrameIsCheckedNotJustTheFirst() {
        val frames = listOf(frame(), frame(), frame(sharp = 0.5), frame(aa = 0.5))
        val e = GlesAcceptance.evaluate(good(frames))
        assertEquals(2, e.failures.size)
        assertTrue(e.failures[0].startsWith("frame 3 sharpness"))
        assertTrue(e.failures[1].startsWith("frame 4 anti-aliasing"))
    }

    @Test
    fun aNumberThatCouldNotBeMeasuredNeverPasses() {
        val nan = Double.NaN
        for (bad in listOf(frame(offset = nan), frame(diff = nan), frame(sharp = nan), frame(aa = nan))) {
            val e = GlesAcceptance.evaluate(good(framesWith(0, bad)))
            assertFalse(e.pass)
            assertTrue(e.failures.joinToString(), e.failures.all { it.contains("not measurable") })
        }
    }

    @Test
    fun aSharperOrSmootherGlesNeverFailsAndTheMouthIsOnlyReported() {
        val e = GlesAcceptance.evaluate(good(List(4) { frame(offset = 0.0, diff = 0.0, sharp = 3.0, aa = 2.0, mouth = 0.1) }))
        assertTrue(e.failures.joinToString(), e.pass)
        val nanMouth = GlesAcceptance.evaluate(good(List(4) { frame(mouth = Double.NaN) }))
        assertTrue(nanMouth.failures.joinToString(), nanMouth.pass)
    }

    @Test
    fun fewerThanFourFramesFailsAndExtraFramesAreFine() {
        val few = GlesAcceptance.evaluate(good(List(3) { frame() }))
        assertFalse(few.pass)
        assertTrue(few.failures[0], few.failures[0].contains("3 of 4"))
        assertFalse(GlesAcceptance.evaluate(good(emptyList())).pass)
        assertTrue(GlesAcceptance.evaluate(good(List(6) { frame() })).pass)
    }

    @Test
    fun videoDurationsMustAgree() {
        fun passes(gles: Double) = GlesAcceptance.evaluate(good().copy(glesDurationSec = gles)).pass
        assertTrue(passes(6.1))
        assertTrue(passes(5.9))
        assertFalse(passes(6.11))
        assertFalse(passes(Double.NaN))
        assertFalse(passes(0.0))
    }

    @Test
    fun aGlesExportFailureShortCircuits() {
        val e = GlesAcceptance.evaluate(good().copy(glesFailure = "timed out after 90 s", frames = emptyList()))
        assertFalse(e.pass)
        assertEquals(1, e.failures.size)
        assertTrue(e.failures[0].contains("timed out after 90 s"))
        val state = e.toState()
        assertTrue(state is GlesState.Ineligible && state.reason.contains("timed out"))
    }

    @Test
    fun missingAudioMeasurementsFail() {
        assertFalse(GlesAcceptance.evaluate(good().copy(mixOnGles = null)).pass)
        val e = GlesAcceptance.evaluate(good().copy(sfxOnlyOnGles = null))
        assertFalse(e.pass)
        assertTrue(e.failures[0].startsWith("sfx-only"))
    }

    @Test
    fun everyNonEmptySourceCombinationPassesWhenItIsExactlyWhatWasExpected() {
        for (mask in 1..7) {
            val expected = Source.values().filter { mask and (1 shl it.ordinal) != 0 }.toSet()
            val e = GlesAcceptance.evaluateAudioCell("cell", expected, audio(expected))
            assertTrue("$expected: ${e.failures}", e.pass)
        }
    }

    @Test
    fun anExpectedSourceThatIsMissingFails() {
        val all = GlesAcceptance.ALL_SOURCES
        val noNarration = audio(all).copy(narrationAmplitude = 0.002)
        val noMusic = audio(all).copy(musicAmplitude = 0.0)
        val noSfx = audio(all).copy(sfxCueMin = 0.005)
        assertTrue(GlesAcceptance.evaluateAudioCell("c", all, noNarration).failures[0].contains("narration"))
        assertTrue(GlesAcceptance.evaluateAudioCell("c", all, noMusic).failures[0].contains("music"))
        assertTrue(GlesAcceptance.evaluateAudioCell("c", all, noSfx).failures.any { it.contains("sfx") })
        // the limit itself is a pass
        assertTrue(GlesAcceptance.evaluateAudioCell("c", all, audio(all).copy(narrationAmplitude = GlesAcceptance.MIN_PRESENT_AMPLITUDE)).pass)
    }

    @Test
    fun anUnexpectedSourceThatLeaksInFails() {
        val narrationOnly = setOf(Source.NARRATION)
        assertTrue(GlesAcceptance.evaluateAudioCell("c", narrationOnly, audio(narrationOnly)).pass)
        assertFalse(GlesAcceptance.evaluateAudioCell("c", narrationOnly, audio(narrationOnly).copy(musicAmplitude = 0.05)).pass)
        assertFalse(GlesAcceptance.evaluateAudioCell("c", narrationOnly, audio(narrationOnly).copy(sfxAmplitude = 0.05)).pass)
        assertTrue(GlesAcceptance.evaluateAudioCell("c", narrationOnly, audio(narrationOnly).copy(musicAmplitude = GlesAcceptance.MAX_ABSENT_AMPLITUDE)).pass)
        assertFalse(GlesAcceptance.evaluateAudioCell("c", narrationOnly, audio(narrationOnly).copy(narrationAmplitude = Double.NaN)).pass)
    }

    @Test
    fun anExpectedSfxMustStandOutAtItsCues() {
        val sfx = setOf(Source.SFX)
        assertTrue(GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(sfxCueMin = 0.2, sfxOutside = 0.05)).pass)
        val weak = GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(sfxCueMin = 0.2, sfxOutside = 0.1))
        assertFalse(weak.pass)
        assertTrue(weak.failures.joinToString(), weak.failures[0].contains("ratio"))
        // digital silence outside the cues must not divide by zero
        assertTrue(GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(sfxOutside = 0.0)).pass)
    }

    @Test
    fun aMissingTrackOrWrongDurationFails() {
        val sfx = setOf(Source.SFX)
        val noTrack = GlesAcceptance.evaluateAudioCell("Canvas sfx", sfx, audio(sfx).copy(hasAudioTrack = false))
        assertEquals(listOf("Canvas sfx: no audio track"), noTrack.failures)
        assertTrue(GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(durationSec = 6.1)).pass)
        val long = GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(durationSec = 6.2))
        assertFalse(long.pass)
        assertTrue(long.failures[0].contains("duration"))
        assertFalse(GlesAcceptance.evaluateAudioCell("c", sfx, audio(sfx).copy(expectedDurationSec = Double.NaN)).pass)
        assertFalse(GlesAcceptance.evaluateAudioCell("c", sfx, null).pass)
    }

    @Test
    fun theSummaryIsOneBoundedLine() {
        assertEquals("all checks passed", GlesAcceptance.Evaluation(emptyList()).summary())
        assertEquals("one", GlesAcceptance.Evaluation(listOf("one")).summary())
        assertEquals("one (+2 more)", GlesAcceptance.Evaluation(listOf("one", "two", "three")).summary())
        val long = GlesAcceptance.Evaluation(listOf("x".repeat(400), "y")).summary()
        assertTrue(long.length <= 150)
        assertTrue(long.endsWith("..."))
        val state = GlesAcceptance.Evaluation(listOf("one", "two")).toState()
        assertEquals("one (+1 more)", (state as GlesState.Ineligible).reason)
    }
}
