package com.example

import com.example.data.AppJson
import com.example.data.ExportSettings
import com.example.engine.RendererSetting
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.3 (I12): the renderer field through the real JSON the app stores. Runs on CI only (kotlinx.serialization). */
class ExportSettingsJsonTest {

    private val json = AppJson.storage

    @Test
    fun theRendererRoundTrips() {
        for (value in listOf("AUTO", "GLES", "CANVAS")) {
            val s = ExportSettings(renderer = value)
            assertEquals(s, json.decodeFromString<ExportSettings>(json.encodeToString(s)))
        }
    }

    @Test
    fun aBlobWithoutTheFieldDecodesToAuto() {
        // What every project saved before WP1.3 looks like (LegacyJsonTest pins the real captured file).
        assertEquals("AUTO", json.decodeFromString<ExportSettings>("{\"fps\":24}").renderer)
        assertEquals("AUTO", json.decodeFromString<ExportSettings>("{}").renderer)
    }

    @Test
    fun aStoredValueIsKeptAsWrittenAndMeansAutoWhenItIsUnknown() {
        val s = json.decodeFromString<ExportSettings>("{\"renderer\":\"VULKAN\"}")
        assertEquals("VULKAN", s.renderer)
        assertEquals("AUTO", RendererSetting.normalize(s.renderer))
    }

    @Test
    fun aNewlySavedBlobCarriesTheField() {
        assertTrue(json.encodeToString(ExportSettings()).contains("\"renderer\":\"AUTO\""))
    }
}
