package com.example

import com.example.data.AppJson
import com.example.data.AnimScript
import com.example.engine.DiagnosticsFixtures
import com.example.engine.ScriptValidator
import com.example.engine.TimelineCompiler
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.6b: the fixture scripts through the real JSON the app uses. Runs on CI only (kotlinx.serialization). */
class DiagnosticsFixturesJsonTest {

    private fun parse(json: String): AnimScript = AppJson.storage.decodeFromString<AnimScript>(json)

    private fun compile(script: AnimScript) =
        TimelineCompiler.compile(script) { id -> com.example.engine.StickFigureRig.BUILT_IN_POSE_INDEX[id] }

    @Test
    fun theAudioScriptWithCuesParsesAndYieldsThreeCuesAtTheExpectedTimes() {
        val script = parse(DiagnosticsFixtures.audioScriptJson(withSfx = true))
        val cues = TimelineCompiler.extractSoundEffectCues(script)
        assertEquals(3, cues.size)
        assertEquals(DiagnosticsFixtures.SFX_CUES_SEC.map { it.toFloat() }, cues.map { it.timeSec })
        assertTrue(cues.all { it.clipId == DiagnosticsFixtures.SFX_CLIP_ID && it.volumeMultiplier == 1f })
    }

    @Test
    fun theAudioScriptWithoutCuesHasNone() {
        assertEquals(0, TimelineCompiler.extractSoundEffectCues(parse(DiagnosticsFixtures.audioScriptJson(false))).size)
    }

    @Test
    fun theAudioScriptEndsWhereTheExpectedDurationAssumes() {
        for (withSfx in listOf(false, true)) {
            val keyframes = compile(parse(DiagnosticsFixtures.audioScriptJson(withSfx)))
            assertEquals(5, keyframes.size)
            val last = keyframes.last()
            assertEquals(DiagnosticsFixtures.AUDIO_SCRIPT_END_SEC.toFloat(), last.timeSec + last.duration, 1e-4f)
        }
    }

    @Test
    fun theAudioScriptsAreValidWithTheClipInTheLibrary() {
        val ids = setOf(DiagnosticsFixtures.SFX_CLIP_ID)
        assertEquals(emptyList<String>(), ScriptValidator.validate(parse(DiagnosticsFixtures.audioScriptJson(true)), ids))
        assertEquals(emptyList<String>(), ScriptValidator.validate(parse(DiagnosticsFixtures.audioScriptJson(false)), ids))
    }

    @Test
    fun theVisualScriptHasTheFigureTheTextAndTheCaption() {
        val script = parse(DiagnosticsFixtures.visualScriptJson())
        assertEquals(emptyList<String>(), ScriptValidator.validate(script, emptySet()))
        assertEquals(1, TimelineCompiler.extractCaptions(script).size)
        val layers = TimelineCompiler.extractOverlayLayers(script)
        assertEquals(1, layers.size)
        assertEquals(12.0f, layers[0].rotationDeg, 1e-4f)
        assertEquals(DiagnosticsFixtures.VISUAL_TEXT_COLOR, layers[0].color)
        val keyframes = compile(script)
        assertEquals(2, keyframes.size)
        val last = keyframes.last()
        assertEquals(DiagnosticsFixtures.VISUAL_SCRIPT_END_SEC.toFloat(), last.timeSec + last.duration, 1e-4f)
    }
}
