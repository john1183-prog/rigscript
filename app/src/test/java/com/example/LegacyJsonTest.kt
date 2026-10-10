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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I12 launch safety. The JSON files under fixtures/legacy_json were captured by CI from the baseline
 * data classes (commit eb6b0b3, WP0.3a) before any V2 field existed. Whatever fields later work adds,
 * these must keep decoding to the same objects: settings stored by the installed app must never stop
 * loading. If a test here fails after a change, a default moved or a field lost its default.
 */
class LegacyJsonTest {

    private val json = AppJson.storage

    /** The project the capture test serialized; keep identical to that definition. */
    private fun expectedLegacyProject() = ProjectDef(
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

    @Test
    fun legacyAppearanceSettingsDecodeToTheDefaults() {
        assertEquals(AppearanceSettings(), json.decodeFromString<AppearanceSettings>(Fixtures.legacyJson("appearance_settings")))
    }

    @Test
    fun legacyExportSettingsDecodeToTheDefaults() {
        assertEquals(ExportSettings(), json.decodeFromString<ExportSettings>(Fixtures.legacyJson("export_settings")))
    }

    @Test
    fun legacyBackgroundMusicSettingsDecodeToTheDefaults() {
        assertEquals(
            BackgroundMusicSettings(),
            json.decodeFromString<BackgroundMusicSettings>(Fixtures.legacyJson("background_music_settings"))
        )
    }

    @Test
    fun legacyProjectDecodesToTheSameProject() {
        assertEquals(expectedLegacyProject(), json.decodeFromString<ProjectDef>(Fixtures.legacyJson("project_def")))
    }

    @Test
    fun legacyFilesAreIntactBaselineCaptures() {
        // Pins the capture itself: an accidental edit of a fixture changes a key count.
        assertEquals(36, json.parseToJsonElement(Fixtures.legacyJson("appearance_settings")).jsonObject.size)
        assertEquals(7, json.parseToJsonElement(Fixtures.legacyJson("export_settings")).jsonObject.size)
        assertEquals(4, json.parseToJsonElement(Fixtures.legacyJson("background_music_settings")).jsonObject.size)
        assertEquals(15, json.parseToJsonElement(Fixtures.legacyJson("project_def")).jsonObject.size)
    }

    @Test
    fun everySettingsClassDecodesFromAnEmptyObject() {
        // A stored blob that predates a field has no key for it: every field must have a default.
        assertEquals(AppearanceSettings(), json.decodeFromString<AppearanceSettings>("{}"))
        assertEquals(ExportSettings(), json.decodeFromString<ExportSettings>("{}"))
        assertEquals(BackgroundMusicSettings(), json.decodeFromString<BackgroundMusicSettings>("{}"))
        assertEquals(AnimScript(), json.decodeFromString<AnimScript>("{}"))
        assertEquals(AnimScript.EMPTY, json.decodeFromString<ProjectDef>("{}").script)
    }

    @Test
    fun unknownKeysFromANewerAppAreIgnored() {
        val extra = "{\"someFutureField\":1,\"another\":{\"nested\":true}}"
        assertEquals(AppearanceSettings(), json.decodeFromString<AppearanceSettings>(extra))
        assertEquals(ExportSettings(), json.decodeFromString<ExportSettings>(extra))
        assertEquals(BackgroundMusicSettings(), json.decodeFromString<BackgroundMusicSettings>(extra))
    }

    @Test
    fun theLegacyProjectSurvivesAnEncodeDecodeRoundTrip() {
        val project = expectedLegacyProject()
        assertEquals(project, json.decodeFromString<ProjectDef>(json.encodeToString(project)))
    }
}
