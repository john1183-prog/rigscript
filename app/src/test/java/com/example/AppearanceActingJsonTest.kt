package com.example

import com.example.data.AppJson
import com.example.data.AppearanceSettings
import com.example.data.ProjectDef
import com.example.engine.acting.ActingIntensity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.1 / I12: the acting intensity through the real JSON the app stores. The legacy blobs are the ones captured from
 * the baseline app (eb6b0b3), before this field existed. Runs on CI only (kotlinx.serialization).
 */
class AppearanceActingJsonTest {

    private val json = AppJson.storage

    @Test
    fun aBlobSavedBeforeTheFieldExistedDecodesWithTheDefault() {
        val legacy = Fixtures.legacyJson("appearance_settings")
        assertFalse("the fixture must predate the field", legacy.contains("actingIntensity"))
        assertEquals("NORMAL", json.decodeFromString<AppearanceSettings>(legacy).actingIntensity)
        assertEquals(AppearanceSettings(), json.decodeFromString<AppearanceSettings>(legacy))
    }

    @Test
    fun aLegacyProjectDecodesWithTheDefaultActing() {
        val legacy = Fixtures.legacyJson("project_def")
        assertFalse("the fixture must predate the field", legacy.contains("actingIntensity"))
        assertEquals("NORMAL", json.decodeFromString<ProjectDef>(legacy).appearance.actingIntensity)
    }

    @Test
    fun anEmptyObjectGivesNormal() {
        assertEquals("NORMAL", json.decodeFromString<AppearanceSettings>("{}").actingIntensity)
    }

    @Test
    fun aStoredValueIsKeptAndRoundTrips() {
        for (v in ActingIntensity.SELECTABLE_NAMES) {
            val s = json.decodeFromString<AppearanceSettings>("{\"actingIntensity\":\"$v\"}")
            assertEquals(v, s.actingIntensity)
            assertEquals(s, json.decodeFromString<AppearanceSettings>(json.encodeToString(s)))
        }
    }

    @Test
    fun anUnknownStoredValueStillDecodesAndIsReadAsNormal() {
        val s = json.decodeFromString<AppearanceSettings>("{\"actingIntensity\":\"LOUD\"}")
        assertEquals("LOUD", s.actingIntensity)
        assertEquals(ActingIntensity.NORMAL, ActingIntensity.parse(s.actingIntensity))
    }

    @Test
    fun aNewlySavedBlobCarriesTheField() {
        assertTrue(json.encodeToString(AppearanceSettings()).contains("\"actingIntensity\":\"NORMAL\""))
    }
}
