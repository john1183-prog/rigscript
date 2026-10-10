package com.example

import com.example.data.AnimScript
import com.example.data.AppJson
import com.example.data.AppearanceSettings
import com.example.data.BackgroundMusicSettings
import com.example.data.ExportSettings
import com.example.data.OverlayLayer
import com.example.data.ProjectDef
import com.example.data.ScriptEvent
import com.example.data.SoundEffectClip
import kotlinx.serialization.encodeToString
import org.junit.Test

/**
 * WP0.3 THROWAWAY. Prints the JSON that the data classes serialize to at this commit
 * (baseline schema, before any V2 field exists) so it can be read from the CI report and
 * committed as fixtures/legacy_json (invariant I12). Removed in the commit that adds the fixtures.
 */
class LegacyJsonCaptureTest {

    private fun emit(name: String, json: String) {
        println("CAPTURE|$name|$json")
    }

    @Test
    fun printLegacyJson() {
        emit("appearance_settings", AppJson.storage.encodeToString(AppearanceSettings()))
        emit("export_settings", AppJson.storage.encodeToString(ExportSettings()))
        emit("background_music_settings", AppJson.storage.encodeToString(BackgroundMusicSettings()))

        val project = ProjectDef(
            id = "legacy-project-0001",
            projectName = "Legacy fixture project",
            audioFilePath = "/data/user/0/com.example/files/audio/legacy.m4a",
            audioDurationSec = 12.5f,
            amplitudeEnvelopePath = "/data/user/0/com.example/files/envelopes/legacy-project-0001_amp.bin",
            mouthShapeEnvelopePath = "/data/user/0/com.example/files/envelopes/legacy-project-0001_mouth.bin",
            script = AnimScript(
                events = listOf(
                    ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f, ease = "ease_out"),
                    ScriptEvent(
                        timeSec = 1.5f, pose = "wave", duration = 0.6f, ease = "spring",
                        expression = "happy", caption = "Hello", soundEffect = "whoosh"
                    ),
                    ScriptEvent(timeSec = 4f, pose = "explain", cameraZoom = 1.2f, figureX = 0.4f)
                ),
                blinkEvents = listOf(1.3f, 3.1f),
                overlayLayers = listOf(
                    OverlayLayer(id = "title", type = "text", text = "HELLO", startSec = 0.2f, endSec = 3f, slot = "upper"),
                    OverlayLayer(id = "dot", type = "shape", shape = "circle", startSec = 1f, endSec = 2f, radius = 0.02f)
                )
            ),
            backgroundMusic = BackgroundMusicSettings(musicFilePath = "/data/user/0/com.example/files/music/legacy.mp3"),
            soundEffects = listOf(
                SoundEffectClip(id = "whoosh", filePath = "/data/user/0/com.example/files/sfx/whoosh.wav", volume = 0.8f)
            ),
            lastModifiedMs = 1760000000000L
        )
        emit("project_def", AppJson.storage.encodeToString(project))
    }
}
