package com.example

import com.example.engine.DiagnosticsReport
import com.example.engine.DiagnosticsReport.AudioCell
import com.example.engine.DiagnosticsReport.FrameEntry
import com.example.engine.DiagnosticsReport.ParityResult
import com.example.engine.DiagnosticsReport.Report
import com.example.engine.FrameMetrics
import com.example.engine.GlesAcceptance
import com.example.engine.GlesAcceptance.AudioMetrics
import com.example.engine.GlesAcceptance.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.6c: verdict assembly, summary text and the small JSON writer of the self-test report. Pure. */
class DiagnosticsReportTest {

    private fun metrics(expected: Set<Source>) = AudioMetrics(
        hasAudioTrack = true,
        durationSec = 7.0,
        expectedDurationSec = 7.0,
        narrationAmplitude = if (Source.NARRATION in expected) 0.30 else 0.0,
        musicAmplitude = if (Source.MUSIC in expected) 0.14 else 0.0,
        sfxAmplitude = if (Source.SFX in expected) 0.03 else 0.0,
        sfxCueMin = if (Source.SFX in expected) 0.40 else 0.0,
        sfxOutside = 0.0
    )

    private fun goodCell(backend: String, sources: Set<Source>) =
        AudioCell.of(backend, sources, metrics(sources), 7.0, 44100, 2, 900L, null, emptyList())

    private fun allCells(): List<AudioCell> = com.example.engine.DiagnosticsFixtures.AUDIO_CASES.flatMap {
        listOf(goodCell("CANVAS", it), goodCell("GLES", it))
    }

    private fun goodFrame(t: Double) = FrameEntry(t, FrameMetrics(0.2, 2.0, 1.0, 1.0, 1.0), "p.png")

    private fun parity(
        canvasError: String? = null,
        glesError: String? = null,
        frames: List<FrameEntry> = listOf(0.6, 1.2, 2.0, 2.6).map { goodFrame(it) }
    ) = ParityResult(canvasError, glesError, 3.0, 3.02, frames)

    private fun report(cells: List<AudioCell>, p: ParityResult?, complete: Boolean = true) =
        Report("2026-11-22 10:00:00", 61.5, "1.0 (1)", "Test Phone", "UNKNOWN", cells, p, listOf("a note"), complete)

    @Test
    fun aCleanRunPasses() {
        val v = DiagnosticsReport.verdict(allCells(), parity())
        assertTrue(v.failures.joinToString(), v.pass)
        assertEquals("PASS", v.word)
    }

    @Test
    fun aGlesCellThatProducedNoFileFailsTheVerdictWithItsReason() {
        val bad = AudioCell.of("GLES", GlesAcceptance.ALL_SOURCES, null, 0.0, 0, 0, 10L, "encoder died", emptyList())
        val cells = allCells().filterNot { it.backend == "GLES" && it.sources == GlesAcceptance.ALL_SOURCES } + bad
        val v = DiagnosticsReport.verdict(cells, parity())
        assertFalse(v.pass)
        assertTrue(v.failures.joinToString(), v.failures[0].contains("encoder died"))
        assertFalse(bad.pass)
    }

    @Test
    fun aMissingCanvasReferenceIsInconclusiveNotAFailureOfGles() {
        val v = DiagnosticsReport.verdict(allCells(), parity(canvasError = "no encoder"))
        assertEquals("INCONCLUSIVE", v.word)
        assertFalse(v.pass)
        assertTrue(v.evaluation == null)
    }

    @Test
    fun noParityAtAllIsInconclusive() {
        assertEquals("INCONCLUSIVE", DiagnosticsReport.verdict(allCells(), null).word)
    }

    @Test
    fun aGlesVisualFailureFails() {
        val v = DiagnosticsReport.verdict(allCells(), parity(glesError = "stage failed"))
        assertEquals("FAIL", v.word)
        assertTrue(v.failures[0].contains("stage failed"))
    }

    @Test
    fun aBadFrameFailsAndOnlyThatFrameIsMarked() {
        val frames = listOf(0.6, 1.2, 2.0, 2.6).mapIndexed { i, t ->
            if (i == 2) FrameEntry(t, FrameMetrics(1.75, 2.0, 1.0, 1.0, 1.0), null) else goodFrame(t)
        }
        val p = parity(frames = frames)
        assertTrue(p.frames[0].pass && !p.frames[2].pass)
        val v = DiagnosticsReport.verdict(allCells(), p)
        assertEquals("FAIL", v.word)
        assertTrue(v.failures[0].startsWith("frame 3 offset"))
    }

    @Test
    fun aNaNFrameNeverPasses() {
        assertFalse(FrameEntry(0.6, FrameMetrics(Double.NaN, 1.0, 1.0, 1.0), null).pass)
    }

    @Test
    fun aCellWithMissingToneFailsAndNamesTheCell() {
        val m = metrics(setOf(Source.MUSIC))  // narration expected but absent
        val c = AudioCell.of("CANVAS", setOf(Source.NARRATION), m, 7.0, 44100, 2, 5L, null, emptyList())
        assertFalse(c.pass)
        assertTrue(c.failures.joinToString(), c.failures.any { it.startsWith("CANVAS narration: narration (440 Hz)") })
    }

    @Test
    fun theSummaryListsEveryCellAndTheVerdict() {
        val text = DiagnosticsReport.summary(report(allCells(), parity()))
        assertTrue(text.contains("VERDICT (what the quick check would decide): PASS"))
        for (c in allCells()) assertTrue(c.label, text.contains(c.label))
        assertEquals(14, Regex("(PASS|FAIL) (CANVAS|GLES) ").findAll(text).count())
        assertTrue(text.contains("frame 4 at 2.6 s"))
        assertTrue(text.contains("a note"))
    }

    @Test
    fun theSummaryOfAFailingRunNamesTheFailure() {
        val bad = AudioCell.of("GLES", setOf(Source.SFX), null, 0.0, 0, 0, 1L, "timed out", emptyList())
        val cells = allCells().filterNot { it.backend == "GLES" && it.sources == setOf(Source.SFX) } + bad
        val text = DiagnosticsReport.summary(report(cells, parity()))
        assertTrue(text.contains("FAIL GLES sfx"))
        assertTrue(text.contains("timed out"))
        assertTrue(text.contains("VERDICT (what the quick check would decide): FAIL"))
    }

    @Test
    fun anIncompleteReportSaysSoAndHasNoVerdictLine() {
        val text = DiagnosticsReport.summary(report(allCells().take(2), null, complete = false))
        assertTrue(text.contains("INCOMPLETE"))
        assertFalse(text.contains("VERDICT"))
    }

    @Test
    fun theJsonWriterEscapesAndMapsNaNToNull() {
        val json = DiagnosticsReport.renderJson(
            linkedMapOf("a" to "x\"y\\z\n", "n" to Double.NaN, "l" to listOf(1, 2L, true, null), "e" to emptyList<Any>())
        )
        assertTrue(json, json.contains("\"a\": \"x\\\"y\\\\z\\n\""))
        assertTrue(json, json.contains("\"n\": null"))
        assertTrue(json, json.contains("\"e\": []"))
        assertTrue(json, json.contains("true"))
    }

    @Test
    fun theReportTreeHasTheTopLevelKeysAFutureSessionReads() {
        val tree = DiagnosticsReport.toTree(report(allCells(), parity()))
        for (k in listOf("verdict", "thresholds", "audioMatrix", "parity", "storedVerdictBefore", "complete")) {
            assertTrue(k, tree.containsKey(k))
        }
        assertEquals(14, (tree["audioMatrix"] as List<*>).size)
    }
}
