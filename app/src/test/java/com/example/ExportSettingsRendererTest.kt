package com.example

import com.example.data.ExportSettings
import com.example.engine.RendererSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.3: the renderer setting's default, and the size rule both backends share. */
class ExportSettingsRendererTest {

    @Test
    fun theRendererDefaultsToAuto() {
        assertEquals(RendererSetting.AUTO, ExportSettings().renderer)
        assertEquals("AUTO", ExportSettings().renderer)
    }

    @Test
    fun changingTheRendererLeavesTheOtherSettingsAlone() {
        val base = ExportSettings(aspectRatio = "16:9", resolution = "720p", fps = 60, bitrateMbps = 12, embedAudio = false, dualAspectExport = true)
        val forced = base.copy(renderer = "GLES")
        assertEquals(base, forced.copy(renderer = base.renderer))
        assertEquals("GLES", forced.renderer)
    }

    @Test
    fun theUsualSizesAreKnown() {
        assertEquals(1088 to 1920, ExportSettings(resolution = "1080p").dimensions("9:16"))
        assertEquals(1920 to 1088, ExportSettings(resolution = "1080p").dimensions("16:9"))
        assertEquals(720 to 1280, ExportSettings(resolution = "720p").dimensions("9:16"))
        assertEquals(1280 to 720, ExportSettings(resolution = "720p").dimensions("16:9"))
        assertEquals(368 to 640, ExportSettings(resolution = "360p").dimensions("9:16"))
        assertEquals(640 to 368, ExportSettings(resolution = "360p").dimensions("16:9"))
    }

    @Test
    fun everySizeIsAMultipleOfSixteenSoTheGlesEncoderNeedsNoExtraAlignment() {
        // WP1.3: the GLES backend takes its sizes from the same function as Canvas, so the two outputs share a pixel grid.
        for (resolution in listOf("360p", "720p", "1080p", "nonsense")) {
            for (aspect in listOf("9:16", "16:9", "1:1", "nonsense")) {
                val (w, h) = ExportSettings(resolution = resolution).dimensions(aspect)
                assertTrue("$resolution $aspect width $w", w % 16 == 0 && w > 0)
                assertTrue("$resolution $aspect height $h", h % 16 == 0 && h > 0)
            }
        }
    }
}
