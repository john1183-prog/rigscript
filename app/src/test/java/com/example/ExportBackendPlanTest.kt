package com.example

import com.example.engine.Backend
import com.example.engine.EncoderPlan
import com.example.engine.ExportBackendPlan
import com.example.engine.ExportBackendPlan.Stage
import com.example.engine.GlesState
import com.example.engine.RendererSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.3: how `AUTO` resolves, what happens when GLES fails, and the encoder parameter rules. */
class ExportBackendPlanTest {

    private val eligible = GlesState.Eligible
    private val unknown = GlesState.Unknown
    private val ineligible = GlesState.Ineligible("no recordable EGL configuration")

    // ── The setting ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSettingIsNormalizedAndUnknownValuesMeanAuto() {
        assertEquals("AUTO", RendererSetting.normalize("AUTO"))
        assertEquals("AUTO", RendererSetting.normalize("auto"))
        assertEquals("GLES", RendererSetting.normalize(" gles "))
        assertEquals("CANVAS", RendererSetting.normalize("Canvas"))
        assertEquals("AUTO", RendererSetting.normalize(null))
        assertEquals("AUTO", RendererSetting.normalize(""))
        assertEquals("AUTO", RendererSetting.normalize("VULKAN"))
    }

    // ── How AUTO resolves ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun autoUsesGlesOnlyWhenTheCheckPassed() {
        assertEquals(Backend.GLES, ExportBackendPlan.resolve("AUTO", eligible).backend)
        assertEquals(Backend.CANVAS, ExportBackendPlan.resolve("AUTO", unknown).backend)
        assertEquals(Backend.CANVAS, ExportBackendPlan.resolve("AUTO", ineligible).backend)
    }

    @Test
    fun anUnknownSettingBehavesAsAuto() {
        for (state in listOf(eligible, unknown, ineligible)) {
            assertEquals(ExportBackendPlan.resolve("AUTO", state).backend, ExportBackendPlan.resolve("nonsense", state).backend)
            assertEquals(ExportBackendPlan.resolve("AUTO", state).backend, ExportBackendPlan.resolve(null, state).backend)
        }
    }

    @Test
    fun aForcedBackendIgnoresTheVerdict() {
        for (state in listOf(eligible, unknown, ineligible)) {
            assertEquals(Backend.GLES, ExportBackendPlan.resolve("GLES", state).backend)
            assertEquals(Backend.CANVAS, ExportBackendPlan.resolve("CANVAS", state).backend)
        }
    }

    @Test
    fun theExplanationSaysWhyInPlainWords() {
        assertTrue(ExportBackendPlan.resolve("AUTO", unknown).explanation.contains("not been checked"))
        assertTrue(ExportBackendPlan.resolve("AUTO", ineligible).explanation.contains("no recordable EGL configuration"))
        assertTrue(ExportBackendPlan.resolve("AUTO", eligible).explanation.contains("passed"))
        // Forcing GLES on a device where the check failed says so.
        assertTrue(ExportBackendPlan.resolve("GLES", ineligible).explanation.contains("check failed"))
        assertTrue(ExportBackendPlan.resolve("GLES", unknown).explanation.contains("not been checked"))
        assertFalse(ExportBackendPlan.resolve("GLES", eligible).explanation.contains("failed"))
        assertEquals("Canvas is selected.", ExportBackendPlan.resolve("CANVAS", ineligible).explanation)
    }

    @Test
    fun theChoiceReportsTheNormalizedSetting() {
        assertEquals("AUTO", ExportBackendPlan.resolve("nonsense", unknown).setting)
        assertEquals("GLES", ExportBackendPlan.resolve("gles", unknown).setting)
    }

    // ── Fallback decisions ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun autoFallsBackToCanvasAndBlamesGlesWhenItFailsEarly() {
        for (stage in listOf(Stage.PROBE, Stage.INIT, Stage.FIRST_FRAMES)) {
            val d = ExportBackendPlan.onGlesFailure("AUTO", stage, "eglCreateContext failed")
            assertTrue("stage $stage", d.runCanvasInstead)
            assertTrue("stage $stage", d.recordIneligible)
            assertFalse("stage $stage", d.offerRetryWithCanvas)
            assertEquals("GLES failed: eglCreateContext failed; exported with Canvas.", d.message)
        }
    }

    @Test
    fun autoStillRunsCanvasAfterALateFailureButDoesNotBlameGles() {
        val d = ExportBackendPlan.onGlesFailure("AUTO", Stage.LATER, "no space left on device")
        assertTrue(d.runCanvasInstead)
        assertFalse(d.recordIneligible)
        assertFalse(d.offerRetryWithCanvas)
        assertTrue(d.message.contains("part-way"))
        assertTrue(d.message.contains("no space left on device"))
    }

    @Test
    fun aForcedGlesIsNeverReplacedSilently() {
        for (stage in Stage.values()) {
            val d = ExportBackendPlan.onGlesFailure("GLES", stage, "shader compile failed")
            assertFalse("stage $stage", d.runCanvasInstead)
            assertTrue("stage $stage", d.offerRetryWithCanvas)
            assertEquals("stage $stage", stage != Stage.LATER, d.recordIneligible)
            assertEquals("GLES export failed: shader compile failed", d.message)
        }
    }

    @Test
    fun aCanvasExportIsNotAffectedByGlesFailures() {
        for (stage in Stage.values()) {
            val d = ExportBackendPlan.onGlesFailure("CANVAS", stage, "anything")
            assertFalse(d.runCanvasInstead)
            assertFalse(d.recordIneligible)
            assertFalse(d.offerRetryWithCanvas)
            assertEquals("", d.message)
        }
    }

    @Test
    fun anUnknownSettingFailsLikeAuto() {
        assertEquals(
            ExportBackendPlan.onGlesFailure("AUTO", Stage.INIT, "x"),
            ExportBackendPlan.onGlesFailure("whatever", Stage.INIT, "x")
        )
    }

    @Test
    fun theFirstFramesWindowIsTwoSecondsOfFrames() {
        assertEquals(48, ExportBackendPlan.firstFramesWindow(24))
        assertEquals(60, ExportBackendPlan.firstFramesWindow(30))
        assertEquals(120, ExportBackendPlan.firstFramesWindow(60))
        assertEquals(2, ExportBackendPlan.firstFramesWindow(0))       // a broken fps still gives a window
        assertEquals(Stage.FIRST_FRAMES, ExportBackendPlan.stageForFrame(0, 30))
        assertEquals(Stage.FIRST_FRAMES, ExportBackendPlan.stageForFrame(59, 30))
        assertEquals(Stage.LATER, ExportBackendPlan.stageForFrame(60, 30))
        assertEquals(Stage.LATER, ExportBackendPlan.stageForFrame(100_000, 30))
    }

    @Test
    fun theProbeNamesWhatIsMissing() {
        assertNull(ExportBackendPlan.probeProblem(true, true))
        assertNull(ExportBackendPlan.probeProblem(null, null))                 // could not tell: let the real init decide
        assertNull(ExportBackendPlan.probeProblem(true, null))
        assertEquals("no H.264 encoder with surface input", ExportBackendPlan.probeProblem(false, true))
        assertEquals("no H.264 encoder with surface input", ExportBackendPlan.probeProblem(false, false))
        assertEquals("no recordable EGL configuration", ExportBackendPlan.probeProblem(true, false))
    }

    // ── Encoder size ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun usualSizesAreAlreadyAlignedForEveryUsualEncoder() {
        // The sizes ExportSettings.dimensions produces: multiples of 16.
        for ((w, h) in listOf(1088 to 1920, 1920 to 1088, 720 to 1280, 1280 to 720, 368 to 640, 640 to 368)) {
            for (align in listOf(1, 2, 4, 8, 16)) {
                val a = EncoderPlan.alignedSize(w, h, align, align)
                assertEquals(EncoderPlan.Size(w, h), a.size)
                assertFalse(a.changed)
            }
        }
    }

    @Test
    fun anEncoderThatWantsMoreAlignmentRoundsUp() {
        val a = EncoderPlan.alignedSize(1080, 1920, 32, 32)
        assertEquals(EncoderPlan.Size(1088, 1920), a.size)
        assertTrue(a.changed)
        val b = EncoderPlan.alignedSize(720, 1280, 64, 64)
        assertEquals(EncoderPlan.Size(768, 1280), b.size)
        // Width and height can differ.
        assertEquals(EncoderPlan.Size(1088, 1088), EncoderPlan.alignedSize(1088, 1080, 16, 32).size)
    }

    @Test
    fun brokenAlignmentsAndSizesNeverProduceZero() {
        assertEquals(EncoderPlan.Size(640, 368), EncoderPlan.alignedSize(640, 368, 0, -4).size)
        assertEquals(EncoderPlan.Size(16, 16), EncoderPlan.alignedSize(0, -5, 16, 16).size)
    }

    @Test
    fun aChangedSizeIsExplainedAndAnUnchangedOneIsNot() {
        val same = EncoderPlan.Size(1080, 1920)
        assertNull(EncoderPlan.sizeNote(same, same))
        val note = EncoderPlan.sizeNote(same, EncoderPlan.Size(1088, 1920))
        assertNotNull(note)
        assertTrue(note!!.contains("1088x1920"))
        assertTrue(note.contains("1080x1920"))
    }

    @Test
    fun aSizeTheEncoderRejectsIsAProblemButUnknownIsNot() {
        assertEquals("the video encoder does not support 1080x1920", EncoderPlan.sizeProblem(1080, 1920, false))
        assertNull(EncoderPlan.sizeProblem(1080, 1920, true))
        assertNull(EncoderPlan.sizeProblem(1080, 1920, null))
    }

    // ── Bitrate ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theBitrateIsTheSettingWhenNoRangeIsKnown() {
        val b = EncoderPlan.bitrate(8, null, null)
        assertEquals(8_000_000, b.bps)
        assertFalse(b.clamped)
        assertNull(EncoderPlan.bitrateNote(b))
    }

    @Test
    fun aBitrateInsideTheRangeIsKept() {
        val b = EncoderPlan.bitrate(8, 1_000_000, 20_000_000)
        assertEquals(8_000_000, b.bps)
        assertFalse(b.clamped)
    }

    @Test
    fun aBitrateAboveTheRangeIsLoweredAndSaidSo() {
        val b = EncoderPlan.bitrate(12, 1_000_000, 6_000_000)
        assertEquals(6_000_000, b.bps)
        assertTrue(b.clamped)
        val note = EncoderPlan.bitrateNote(b)
        assertNotNull(note)
        assertTrue(note!!.contains("at most 6.0 Mbit/s"))
        assertTrue(note.contains("12.0"))
    }

    @Test
    fun aBitrateBelowTheRangeIsRaisedAndSaidSo() {
        val b = EncoderPlan.bitrate(2, 3_500_000, null)
        assertEquals(3_500_000, b.bps)
        assertTrue(b.clamped)
        val note = EncoderPlan.bitrateNote(b)
        assertNotNull(note)
        assertTrue(note!!.contains("at least 3.5 Mbit/s"))
        assertTrue(note.contains("2.0"))
    }

    @Test
    fun absurdSettingsAndBounds() {
        assertEquals(1_000_000, EncoderPlan.bitrate(0, null, null).bps)
        assertEquals(1_000_000, EncoderPlan.bitrate(-7, null, null).bps)
        assertEquals(200_000_000, EncoderPlan.bitrate(1_000_000, null, null).bps)      // stays inside an Int
        assertEquals(8_000_000, EncoderPlan.bitrate(8, 0, -1).bps)                      // non-positive bounds are ignored
        assertEquals(5_000_000, EncoderPlan.bitrate(8, 9_000_000, 5_000_000).bps)       // a contradictory range: the upper bound wins
    }
}
