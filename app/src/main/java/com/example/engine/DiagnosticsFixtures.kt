package com.example.engine

import com.example.data.AnimScript
import com.example.data.BackgroundMusicSettings
import com.example.data.ExportSettings
import com.example.data.ProjectDef
import com.example.data.SoundEffectClip
import com.example.engine.GlesAcceptance.Source
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The media and the projects behind the GLES self-test and the quick eligibility check (WP1.6b). Nothing is bundled:
 * every sound is a sine tone synthesised here and every script is a JSON string, so a run needs no assets and the
 * numbers the analysis looks for are known exactly. Pure Kotlin (no Android types), tested on the JVM.
 *
 * Sources and what the analysis listens for:
 *  - narration: 440 Hz for 6 s,
 *  - music: 220 Hz (it loops without a click: 6 s holds a whole number of cycles), mixed under the narration at the
 *    project's music volume,
 *  - sound effects: 1 kHz bursts of 100 ms triggered by script cues at 1.0 s, 2.5 s and 4.0 s.
 *
 * Every audio script ends at 6.0 s, so the export length is `max(6.0, narration) + 1 = 7.0 s` whichever sources a case
 * has: one expected duration for all seven cells ([ExportAudioPlan.totalSec] is the rule both backends share).
 *
 * The scripts are plain JSON text on purpose: this file stays free of serialization so that it also compiles in the
 * local JVM harness. The caller decodes them with `AppJson.storage.decodeFromString<AnimScript>`.
 */
object DiagnosticsFixtures {

    // ------------------------------------------------------------------------------------------------ audio constants

    const val SAMPLE_RATE = 44_100
    const val NARRATION_HZ = 440.0
    const val MUSIC_HZ = 220.0
    const val SFX_HZ = 1_000.0

    const val NARRATION_SEC = 6.0
    const val MUSIC_SEC = 6.0
    const val SFX_BURST_SEC = 0.1

    /**
     * File amplitudes as a fraction of full scale. They are chosen so that the worst case where all three overlap,
     * `0.3 + 0.4 * 0.35 + 0.4 = 0.84`, stays under the mixer's soft limiter knee ([AudioMath.LIMITER_KNEE], 0.89): the
     * mix is then exactly the sum, with no limiter distortion to leak a tone into a bin that must read as absent. All
     * three are far above the presence threshold ([GlesAcceptance.MIN_PRESENT_AMPLITUDE], 0.01).
     */
    const val NARRATION_LEVEL = 0.3
    const val MUSIC_LEVEL = 0.4
    const val SFX_LEVEL = 0.4

    /** The music volume of the fixture project (the product default), so the mix level is 0.4 * 0.35 = 0.14. */
    const val MUSIC_VOLUME = 0.35f

    /** A short fade on each burst so its edges do not click and spread over other frequencies. */
    const val SFX_FADE_SEC = 0.005

    /** The id the script cues use and the temporary library defines. */
    const val SFX_CLIP_ID = "diag_sfx"

    /** When the three cues fire (seconds). */
    val SFX_CUES_SEC: List<Double> = listOf(1.0, 2.5, 4.0)

    /** The script ends here (its last event is at 5.5 s and lasts 0.5 s). */
    const val AUDIO_SCRIPT_END_SEC = 6.0

    /** What every audio cell must last: the script or narration (6 s) plus the one second tail. */
    const val AUDIO_EXPECTED_TOTAL_SEC = 7.0

    /**
     * The centres of the +-50 ms analysis windows: the middle of each burst, so a window covers exactly the 100 ms the
     * burst lasts (a window centred on the cue itself would only see its first half).
     */
    fun sfxWindowCentersSec(): List<Double> = SFX_CUES_SEC.map { it + SFX_BURST_SEC / 2.0 }

    /** The seven non-empty combinations of narration, music and sound effects, in the fixed order of the matrix. */
    val AUDIO_CASES: List<Set<Source>> = listOf(
        setOf(Source.NARRATION),
        setOf(Source.MUSIC),
        setOf(Source.SFX),
        setOf(Source.NARRATION, Source.MUSIC),
        setOf(Source.NARRATION, Source.SFX),
        setOf(Source.MUSIC, Source.SFX),
        setOf(Source.NARRATION, Source.MUSIC, Source.SFX)
    )

    /** `narration+music+sfx`: a stable label for a case, used in reports and in output file names. */
    fun caseLabel(sources: Set<Source>): String =
        Source.values().filter { it in sources }.joinToString("+") { it.name.lowercase() }

    // ------------------------------------------------------------------------------------------------ synthesis

    /**
     * A sine tone of [freqHz] for [seconds], at [level] (0..1 of full scale), with a linear fade of [fadeSec] at both
     * ends. The phase starts at zero, so a tone whose length holds a whole number of cycles can loop without a click.
     */
    fun tone(
        freqHz: Double,
        seconds: Double,
        level: Double,
        fadeSec: Double = 0.0,
        sampleRate: Int = SAMPLE_RATE
    ): ShortArray {
        val count = (seconds * sampleRate).roundToInt().coerceAtLeast(0)
        val fade = (fadeSec * sampleRate).roundToInt().coerceAtLeast(0)
        val peak = level.coerceIn(0.0, 1.0) * 32767.0
        return ShortArray(count) { i ->
            var gain = 1.0
            if (fade > 0) {
                if (i < fade) gain = i.toDouble() / fade
                val tail = count - 1 - i
                if (tail < fade) gain = minOf(gain, tail.toDouble() / fade)
            }
            (peak * gain * sin(2.0 * PI * freqHz * i / sampleRate)).roundToInt().toShort()
        }
    }

    fun narrationSamples(): ShortArray = tone(NARRATION_HZ, NARRATION_SEC, NARRATION_LEVEL)

    fun musicSamples(): ShortArray = tone(MUSIC_HZ, MUSIC_SEC, MUSIC_LEVEL)

    fun sfxSamples(): ShortArray = tone(SFX_HZ, SFX_BURST_SEC, SFX_LEVEL, SFX_FADE_SEC)

    /** A mono 16-bit PCM WAV file: the 44-byte RIFF header followed by the samples, little endian. */
    fun wavBytes(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // byte rate
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }

    /** The three fixture files in one directory. */
    class Files(val narration: File, val music: File, val sfx: File)

    /** Writes the three WAV files into [dir] (created if needed) and returns them. Safe to call again: it overwrites. */
    fun writeAudioFiles(dir: File): Files {
        dir.mkdirs()
        val files = Files(File(dir, "diag_narration.wav"), File(dir, "diag_music.wav"), File(dir, "diag_sfx.wav"))
        files.narration.writeBytes(wavBytes(narrationSamples()))
        files.music.writeBytes(wavBytes(musicSamples()))
        files.sfx.writeBytes(wavBytes(sfxSamples()))
        return files
    }

    // ------------------------------------------------------------------------------------------------ scripts

    /**
     * The script of the audio cells (JSON text). With [withSfx] the three events at 1.0, 2.5 and 4.0 s carry the
     * [SFX_CLIP_ID] cue; without it no event mentions a sound effect, so a case that has no sound effects does not
     * produce "unknown clip" warnings.
     */
    fun audioScriptJson(withSfx: Boolean): String {
        fun cue(): String = if (withSfx) ",\"soundEffect\":\"$SFX_CLIP_ID\",\"soundEffectVolume\":1.0" else ""
        return "{\"version\":\"1.0\",\"events\":[" +
            "{\"timeSec\":0.0,\"pose\":\"stand_straight\",\"duration\":0.5}," +
            "{\"timeSec\":1.0,\"pose\":\"wave\",\"duration\":0.5${cue()}}," +
            "{\"timeSec\":2.5,\"pose\":\"explain\",\"duration\":0.5${cue()}}," +
            "{\"timeSec\":4.0,\"pose\":\"present\",\"duration\":0.5${cue()}}," +
            "{\"timeSec\":5.5,\"pose\":\"stand_straight\",\"duration\":0.5}" +
            "]}"
    }

    // ---- the visual fixture (parity frames) ----

    /** The frame times (seconds) the parity check samples; [GlesAcceptance.REQUIRED_FRAMES] of them. */
    val VISUAL_SAMPLE_TIMES_SEC: List<Double> = listOf(0.6, 1.2, 2.0, 2.6)

    /** The visual script ends at 2.0 s, so the export is 3.0 s long (no audio in the visual fixture). */
    const val VISUAL_SCRIPT_END_SEC = 2.0

    /** The text colour of the visual fixture (0xFFFFD500, yellow), which no other part of the frame uses. */
    const val VISUAL_TEXT_COLOR = 4294956288L

    /**
     * One figure on a mountain scene, a rotated and scaled text overlay in a colour of its own, and a caption; the
     * camera zooms in at 1.6 s. The sky (0xFF7A4B2A) is brown-orange and the ground (0xFF2E5E3A) green so that the
     * figure's bone colour (blue, the
     * product default) and the text colour (yellow) can each be found in a frame by colour alone.
     * Mountains are the diagonal filled polygons the edge fringe (R3) is about; the text is the text-raster case
     * (R2, R5); the caption exercises the wrapped box.
     */
    fun visualScriptJson(): String =
        "{\"version\":\"1.0\",\"events\":[" +
            "{\"timeSec\":0.0,\"pose\":\"explain\",\"duration\":0.3,\"sceneShape\":\"mountains\"," +
            "\"sceneAtmosphere\":\"none\",\"skyColor\":4286204714,\"groundColor\":4281228858,\"horizonY\":0.68," +
            "\"cameraZoom\":1.0,\"caption\":\"Parity check: caption text that wraps over two lines\"," +
            "\"captionDurationSec\":3.0}," +
            "{\"timeSec\":1.6,\"pose\":\"present\",\"duration\":0.4,\"cameraZoom\":1.25}" +
            "]," +
            "\"overlayLayers\":[" +
            "{\"id\":\"diag_text\",\"type\":\"text\",\"text\":\"PARITY\",\"x\":0.5,\"y\":0.2,\"fontSize\":0.09," +
            "\"color\":$VISUAL_TEXT_COLOR,\"rotationDeg\":12.0,\"scale\":1.3,\"startSec\":0.0,\"endSec\":3.0," +
            "\"enterStyle\":\"none\",\"exitStyle\":\"none\"}" +
            "]}"

    // ------------------------------------------------------------------------------------------------ projects

    /** Names of the settings the self-test varies: only the renderer. 360p, 30 fps, a low bitrate, audio embedded. */
    private fun exportSettings(renderer: String): ExportSettings = ExportSettings(
        aspectRatio = "9:16",
        resolution = "360p",
        fps = 30,
        bitrateMbps = 2,
        embedAudio = true,
        dualAspectExport = false,
        renderer = renderer
    )

    /**
     * The project of one audio cell. [script] is [audioScriptJson] decoded (with sound effects iff [Source.SFX] is in
     * [sources]); [files] are the written WAVs; [renderer] is `"CANVAS"` or `"GLES"`. Narration, music and the sound
     * effect library are present only for the sources the case has.
     */
    fun audioProject(sources: Set<Source>, files: Files, script: AnimScript, renderer: String): ProjectDef {
        val label = caseLabel(sources)
        return ProjectDef(
            id = "diag_audio_${label}_$renderer",
            projectName = "diag_${label.replace('+', '_')}_${renderer.lowercase()}",
            audioFilePath = if (Source.NARRATION in sources) files.narration.absolutePath else null,
            audioDurationSec = if (Source.NARRATION in sources) NARRATION_SEC.toFloat() else 0f,
            script = script,
            exportSettings = exportSettings(renderer),
            backgroundMusic = if (Source.MUSIC in sources) {
                BackgroundMusicSettings(
                    musicFilePath = files.music.absolutePath,
                    volume = MUSIC_VOLUME,
                    narrationVolume = 1.0f,
                    loop = true
                )
            } else BackgroundMusicSettings(),
            soundEffects = if (Source.SFX in sources) {
                listOf(SoundEffectClip(SFX_CLIP_ID, files.sfx.absolutePath, 1.0f))
            } else emptyList(),
            lastModifiedMs = 0L
        )
    }

    /** The project of the parity frames: no audio, the visual script, the given renderer. */
    fun visualProject(script: AnimScript, renderer: String): ProjectDef = ProjectDef(
        id = "diag_visual_$renderer",
        projectName = "diag_visual_${renderer.lowercase()}",
        script = script,
        exportSettings = exportSettings(renderer),
        lastModifiedMs = 0L
    )
}
