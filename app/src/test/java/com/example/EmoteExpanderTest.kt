package com.example

import com.example.data.AnimScript
import com.example.data.OverlayLayer
import com.example.data.ScriptEvent
import com.example.engine.EmoteExpander
import com.example.engine.OverlayResolver
import com.example.engine.TimelineCompiler
import com.example.engine.acting.ActVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP2.5 emotes: expansion into at most four head-parented overlay layers, seek-safe, and no change for scripts without them. */
class EmoteExpanderTest {

    private fun script(emote: String, t: Float = 1f, vararg more: ScriptEvent) =
        AnimScript(events = listOf(ScriptEvent(timeSec = 0f, pose = "stand_straight"), ScriptEvent(timeSec = t, emote = emote)) + more)

    @Test
    fun everyEmoteExpandsToOneToFourHeadParentedLayers() {
        for (name in ActVocabulary.EMOTES) {
            val layers = EmoteExpander.expand(script(name))
            assertTrue("$name: ${layers.size} layers", layers.size in 1..4)
            assertEquals(EmoteExpander.partCount(name), layers.size)
            for (l in layers) {
                assertEquals("head", l.parentBone)
                assertTrue("$name starts at or after the event", l.startSec >= 1f - 1e-6f)
                assertTrue("$name is short-lived", l.endSec - 1f <= 2.5f && l.endSec > l.startSec)
                assertTrue(l.type == "text" || l.type == "shape")
                assertTrue("$name stays near the head", Math.abs(l.x) < 0.2f && Math.abs(l.y) < 0.2f)
            }
            assertEquals("unique ids", layers.size, layers.map { it.id }.toSet().size)
            assertEquals("deterministic", layers, EmoteExpander.expand(script(name)))
        }
    }

    @Test
    fun anUnknownEmoteOrNoEmoteGivesNothingAndLegacyOverlaysAreUntouched() {
        assertTrue(EmoteExpander.expand(script("confetti")).isEmpty())
        assertEquals(0, EmoteExpander.partCount(null))
        val own = listOf(
            OverlayLayer(id = "b", type = "shape", shape = "circle", startSec = 2f, endSec = 3f),
            OverlayLayer(id = "a", type = "text", text = "hi", startSec = 0.5f, endSec = 1.5f)
        )
        val plain = AnimScript(events = listOf(ScriptEvent(timeSec = 0f, pose = "wave")), overlayLayers = own)
        assertEquals(own.sortedBy { it.startSec }, TimelineCompiler.extractOverlayLayers(plain))
        val withEmote = plain.copy(events = plain.events + ScriptEvent(timeSec = 1f, emote = "heart"))
        val merged = TimelineCompiler.extractOverlayLayers(withEmote)
        assertEquals(3, merged.size)
        assertEquals("sorted by start", merged.map { it.startSec }, merged.map { it.startSec }.sorted())
        assertTrue(merged.map { it.id }.containsAll(listOf("a", "b")))
    }

    @Test
    fun theMarkFollowsTheFigureOpacity() {
        val s = AnimScript(events = listOf(
            ScriptEvent(timeSec = 0f, pose = "stand_straight", figureOpacity = 0.4f),
            ScriptEvent(timeSec = 2f, emote = "sweat"),
            ScriptEvent(timeSec = 4f, figureOpacity = 1f), ScriptEvent(timeSec = 5f, emote = "sweat")
        ))
        val layers = EmoteExpander.expand(s)
        assertEquals(listOf(0.4f, 1f), layers.map { it.opacity })
    }

    @Test
    fun theEngineShowsTheMarkOnlyInsideItsWindowAndItsPositionFollowsTheHead() {
        val s = script("exclaim", 1f)
        val e = EngineHarness.create(s, FloatArray(0))
        e.seekTo(0.5f)
        assertTrue("before: nothing", e.currentOverlays.isEmpty())
        e.seekTo(1.6f)
        val now = e.currentOverlays
        assertEquals(1, now.size)
        assertEquals("head", now[0].parentBone)
        val a = OverlayResolver.applyParenting(now, mapOf("head" to (0.5f to 0.30f)))
        val b = OverlayResolver.applyParenting(now, mapOf("head" to (0.7f to 0.45f)))
        assertEquals("moves with the head, x", 0.2f, b[0].x - a[0].x, 1e-5f)
        assertEquals("moves with the head, y", 0.15f, b[0].y - a[0].y, 1e-5f)
        assertTrue("above the head", a[0].y < 0.30f)
        e.seekTo(4.0f)
        assertTrue("after: gone", e.currentOverlays.isEmpty())
        e.seekTo(1.6f)
        assertEquals("seek back reproduces it", a[0].y, OverlayResolver.applyParenting(e.currentOverlays, mapOf("head" to (0.5f to 0.30f)))[0].y, 0f)
    }

    @Test
    fun aDriftingMarkMovesAndAStaggeredOneAppearsInOrder() {
        val up = EmoteExpander.expand(script("exclaim"))[0]
        val early = OverlayResolver.resolveTimeBased(listOf(up), 1.3f)[0].localY
        val late = OverlayResolver.resolveTimeBased(listOf(up), 2.0f)[0].localY
        assertTrue("it drifts up (y decreases)", late < early)
        val z = EmoteExpander.expand(script("zzz"))
        assertEquals(3, z.size)
        assertTrue(z[0].startSec < z[1].startSec && z[1].startSec < z[2].startSec)
    }
}
