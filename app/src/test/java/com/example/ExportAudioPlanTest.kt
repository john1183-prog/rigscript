package com.example

import com.example.data.SoundEffectClip
import com.example.engine.ExportAudioPlan
import com.example.engine.ExportAudioPlan.Mode
import com.example.engine.SoundEffectCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.1: the pure decisions of the shared export-audio unit. */
class ExportAudioPlanTest {

    @Test
    fun decideModeCoversEveryInputCombination() {
        var rows = 0
        for (embed in listOf(false, true)) for (narration in listOf(false, true)) for (music in listOf(false, true)) {
            for (sfx in listOf(0, 1, 3)) {
                val expected = when {
                    !embed -> Mode.NONE
                    music || sfx > 0 -> Mode.MIX
                    narration -> Mode.PASSTHROUGH
                    else -> Mode.NONE
                }
                assertEquals("embed=$embed narration=$narration music=$music sfx=$sfx", expected,
                    ExportAudioPlan.decideMode(embed, narration, music, sfx))
                rows++
            }
        }
        assertEquals(24, rows)
    }

    @Test
    fun decideModeKeyRows() {
        // Not embedding means nothing, whatever exists.
        assertEquals(Mode.NONE, ExportAudioPlan.decideMode(false, true, true, 5))
        // Narration alone is copied losslessly.
        assertEquals(Mode.PASSTHROUGH, ExportAudioPlan.decideMode(true, true, false, 0))
        // Music or a cue makes it a mix, with or without narration.
        assertEquals(Mode.MIX, ExportAudioPlan.decideMode(true, true, true, 0))
        assertEquals(Mode.MIX, ExportAudioPlan.decideMode(true, false, true, 0))
        assertEquals(Mode.MIX, ExportAudioPlan.decideMode(true, false, false, 1))
        // Embedding with nothing to play is silent, not an error.
        assertEquals(Mode.NONE, ExportAudioPlan.decideMode(true, false, false, 0))
    }

    private val library = listOf(
        SoundEffectClip(id = "click", filePath = "/sfx/click.wav", volume = 0.5f),
        SoundEffectClip(id = "boom", filePath = "/sfx/boom.wav", volume = 1f)
    )

    private fun cue(t: Float, id: String, m: Float = 1f) = SoundEffectCue(timeSec = t, clipId = id, volumeMultiplier = m)

    @Test
    fun cuesResolveInOrderWithTheMultipliedVolume() {
        val r = ExportAudioPlan.resolveCues(listOf(cue(1f, "click", 0.5f), cue(2f, "boom"), cue(3f, "click")), library)
        assertEquals(
            listOf(
                ExportAudioPlan.ResolvedCue(1f, "/sfx/click.wav", 0.25f),
                ExportAudioPlan.ResolvedCue(2f, "/sfx/boom.wav", 1f),
                ExportAudioPlan.ResolvedCue(3f, "/sfx/click.wav", 0.5f)
            ),
            r.resolved
        )
        assertTrue(r.unresolvedIds.isEmpty())
        assertEquals(0, r.skippedCueCount)
        assertTrue("no warning when everything resolves", r.warnings.isEmpty())
    }

    @Test
    fun unknownCueIdsAreReportedOncePerIdWithTheNumberOfSkippedCues() {
        val r = ExportAudioPlan.resolveCues(
            listOf(cue(1f, "ghost"), cue(2f, "click"), cue(3f, "ghost"), cue(4f, "phantom"), cue(5f, "ghost")),
            library
        )
        assertEquals(listOf("ghost", "phantom"), r.unresolvedIds)
        assertEquals(4, r.skippedCueCount)
        assertEquals(1, r.resolved.size)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0], r.warnings[0].contains("4 sound-effect cue(s)"))
        assertTrue(r.warnings[0], r.warnings[0].contains("ghost, phantom"))
    }

    @Test
    fun anEmptyLibraryOrNoCuesIsHandled() {
        assertTrue(ExportAudioPlan.resolveCues(emptyList(), library).resolved.isEmpty())
        val r = ExportAudioPlan.resolveCues(listOf(cue(1f, "click")), emptyList())
        assertEquals(listOf("click"), r.unresolvedIds)
        assertTrue(r.resolved.isEmpty())
    }

    @Test
    fun aFailedMixFallsBackToTheNarrationAndNamesTheReason() {
        val o = ExportAudioPlan.afterMixFailure(hasNarration = true, reason = "decoder stalled")
        assertEquals(Mode.PASSTHROUGH, o.mode)
        assertEquals(1, o.warnings.size)
        assertTrue(o.warnings[0], o.warnings[0].contains("decoder stalled") && o.warnings[0].contains("narration only"))
    }

    @Test
    fun aFailedMixWithoutNarrationSaysThereIsNoAudio() {
        val o = ExportAudioPlan.afterMixFailure(hasNarration = false, reason = ExportAudioPlan.NOTHING_DECODABLE)
        assertEquals(Mode.NONE, o.mode)
        assertTrue(o.warnings[0], o.warnings[0].contains("no audio") && o.warnings[0].contains(ExportAudioPlan.NOTHING_DECODABLE))
    }

    @Test
    fun aFailedNarrationProbeSaysThereIsNoAudio() {
        val o = ExportAudioPlan.afterNarrationFailure("no audio track in the file")
        assertEquals(Mode.NONE, o.mode)
        assertTrue(o.warnings[0], o.warnings[0].contains("no audio track in the file") && o.warnings[0].contains("no audio"))
    }

    @Test
    fun reasonsAreOneShortLine() {
        assertEquals("first line", ExportAudioPlan.reasonOf(IllegalStateException("\n  first line  \nsecond line")))
        assertEquals("IllegalStateException", ExportAudioPlan.reasonOf(IllegalStateException()))
        assertEquals("IllegalStateException", ExportAudioPlan.reasonOf(IllegalStateException("   ")))
        assertEquals("unknown error", ExportAudioPlan.reasonOf(null))
    }

    @Test
    fun totalSecMatchesTheFormulaTheCanvasExportUses() {
        assertEquals(13f, ExportAudioPlan.totalSec(10f, 12f), 0f)   // audio longer than the script
        assertEquals(15f, ExportAudioPlan.totalSec(14f, 12f), 0f)   // script longer than the audio
        assertEquals(1f, ExportAudioPlan.totalSec(0f, 0f), 0f)      // empty: just the tail
        assertEquals(EngineHarness.totalSec(emptyList(), 7f), ExportAudioPlan.totalSec(0f, 7f), 0f)
    }
}
