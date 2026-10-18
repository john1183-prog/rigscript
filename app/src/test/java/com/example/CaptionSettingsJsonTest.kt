package com.example

import com.example.data.AppJson
import com.example.data.AppearanceSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.4 / R11 (I12): the caption height cap through the real JSON the app stores. Runs on CI only (kotlinx.serialization). */
class CaptionSettingsJsonTest {

    private val json = AppJson.storage

    @Test
    fun theCapDefaultsToHalfTheCanvas() {
        assertEquals(0.5f, AppearanceSettings().captionMaxHeightFraction, 0f)
        assertEquals(0.5f, json.decodeFromString<AppearanceSettings>("{}").captionMaxHeightFraction, 0f)
    }

    @Test
    fun aStoredValueIsKeptAndRoundTrips() {
        val s = json.decodeFromString<AppearanceSettings>("{\"captionMaxHeightFraction\":0.3}")
        assertEquals(0.3f, s.captionMaxHeightFraction, 0f)
        assertEquals(s, json.decodeFromString<AppearanceSettings>(json.encodeToString(s)))
    }

    @Test
    fun aNewlySavedBlobCarriesTheField() {
        assertTrue(json.encodeToString(AppearanceSettings()).contains("\"captionMaxHeightFraction\":0.5"))
    }
}
