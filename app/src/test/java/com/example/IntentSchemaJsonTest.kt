package com.example

import com.example.data.AnimScript
import com.example.data.AppJson
import com.example.engine.ScriptValidator
import com.example.engine.TimelineCompiler
import com.example.engine.acting.ActVerbTimeline
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.5 through the app's real JSON parser (CI only: the local harness has no kotlinx.serialization). A legacy event decodes
 * with the new fields at their defaults and round-trips through the storage JSON; the device test script `v2/wp25_intents.json` parses,
 * validates without a verb or emote warning, and compiles to the verbs and emotes it names.
 */
class IntentSchemaJsonTest {

    @Test
    fun aLegacyEventDecodesWithDefaultsAndRoundTrips() {
        val s = AppJson.storage.decodeFromString<AnimScript>("""{"version":"1.0","events":[{"timeSec":1.5,"pose":"wave"}]}""")
        val e = s.events[0]
        assertEquals("wave", e.pose)
        assertNull(e.act); assertNull(e.actTarget); assertNull(e.emote)
        assertEquals(0.7f, e.actIntensity, 0f)
        // The storage JSON writes every field (nulls and defaults too), so the new ones appear as null and 0.7: what matters is that it round-trips.
        val out = AppJson.storage.encodeToString(s)
        assertEquals(s, AppJson.storage.decodeFromString<AnimScript>(out))
    }

    @Test
    fun anEventWithoutAPoseDecodes() {
        val s = AppJson.storage.decodeFromString<AnimScript>("""{"events":[{"timeSec":2.0,"act":"look_at","actTarget":"layer:x","emote":"idea"}]}""")
        val e = s.events[0]
        assertNull(e.pose)
        assertEquals("look_at", e.act)
        assertEquals("layer:x", e.actTarget)
        assertEquals("idea", e.emote)
    }

    @Test
    fun theDeviceTestScriptParsesValidatesAndCompiles() {
        val s = AppJson.storage.decodeFromString<AnimScript>(Fixtures.text("v2/wp25_intents.json"))
        val w = ScriptValidator.validate(s, emptySet())
        for (part in listOf("Unknown act", "Unknown emote", "actTarget", "actIntensity", "no pose and no other", "under 1 s apart", "Unknown pose")) {
            assertTrue("unexpected warning '$part': $w", w.none { it.contains(part) })
        }
        val kfs = EngineHarness.compile(s)
        assertEquals("every event is kept", s.events.size, kfs.size)
        assertEquals(13, ActVerbTimeline.fromKeyframes(kfs).size)
        assertEquals(9, kfs.count { it.emote != null })
        assertEquals("the nine emotes expand to 16 layers", 16, TimelineCompiler.extractOverlayLayers(s).count { it.id.startsWith("emote_") })
        assertTrue(TimelineCompiler.extractOverlayLayers(s).any { it.id == "logo" })
    }
}
