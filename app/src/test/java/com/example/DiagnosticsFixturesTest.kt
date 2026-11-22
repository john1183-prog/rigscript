package com.example

import com.example.data.AnimScript
import com.example.engine.AudioMath
import com.example.engine.DiagnosticsDsp
import com.example.engine.DiagnosticsFixtures
import com.example.engine.ExportAudioPlan
import com.example.engine.GlesAcceptance
import com.example.engine.GlesAcceptance.AudioMetrics
import com.example.engine.GlesAcceptance.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** WP1.6b: the synthetic media and projects of the GLES self-test (pure; the media glue itself needs a device). */
class DiagnosticsFixturesTest {

    private val F = DiagnosticsFixtures
    private val rate = DiagnosticsFixtures.SAMPLE_RATE

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected +- $tolerance but was $actual", abs(expected - actual) <= tolerance)
    }

    private fun level(samples: ShortArray, hz: Double): Double =
        DiagnosticsDsp.goertzelAmplitude(samples, 0, samples.size, rate, hz)

    // ------------------------------------------------------------------------------------------------- synthesis

    @Test
    fun aToneReadsAtItsLevelAndNowhereElse() {
        val s = F.tone(440.0, 2.0, 0.5)
        assertEquals(2 * rate, s.size)
        near("440 Hz", 0.5, level(s, 440.0), 0.02)
        assertTrue("220 Hz leak ${level(s, 220.0)}", level(s, 220.0) < 0.003)
        assertTrue("1 kHz leak ${level(s, 1000.0)}", level(s, 1000.0) < 0.003)
    }

    @Test
    fun aFadedToneStartsAndEndsAtZero() {
        val s = F.tone(1000.0, 0.1, 0.4, fadeSec = 0.005)
        assertEquals(0, s.first().toInt())
        assertEquals(0, s.last().toInt())
        // Somewhere in the middle it reaches (nearly) the full level.
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak > (0.39 * 32767).toInt())
    }

    @Test
    fun degenerateTonesAreEmptyNotAnError() {
        assertEquals(0, F.tone(440.0, 0.0, 0.5).size)
        assertEquals(0, F.tone(440.0, -1.0, 0.5).size)
        // A level above 1 is clamped: no sample wraps around.
        val loud = F.tone(440.0, 0.1, 5.0)
        assertTrue(loud.all { abs(it.toInt()) <= 32767 })
    }

    @Test
    fun narrationAndMusicHoldWholeCyclesSoTheyLoopWithoutAClick() {
        for ((freq, seconds) in listOf(F.NARRATION_HZ to F.NARRATION_SEC, F.MUSIC_HZ to F.MUSIC_SEC)) {
            val cycles = freq * seconds
            near("cycles of $freq Hz in $seconds s", cycles.roundToInt().toDouble(), cycles, 1e-9)
        }
        val music = F.musicSamples()
        // Looping puts sample 0 (zero) right after the last sample, so the last step must be one ordinary step.
        val step = abs(music.last().toInt())
        val ordinary = (F.MUSIC_LEVEL * 32767 * sin(2.0 * PI * F.MUSIC_HZ / rate)).toInt()
        assertTrue("last sample $step vs one step $ordinary", step <= ordinary + 2)
    }

    @Test
    fun theThreeTonesAreSeparatedAndEachReadsAtItsOwnLevel() {
        val narration = F.narrationSamples()
        val music = F.musicSamples()
        near("narration", F.NARRATION_LEVEL, level(narration, F.NARRATION_HZ), 0.02)
        near("music", F.MUSIC_LEVEL, level(music, F.MUSIC_HZ), 0.02)
        // 440 Hz is the second harmonic of 220 Hz: a pure sine of one must read as nothing at the other.
        assertTrue(level(narration, F.MUSIC_HZ) < 0.003)
        assertTrue(level(music, F.NARRATION_HZ) < 0.003)
    }

    // ------------------------------------------------------------------------------------------------- WAV

    @Test
    fun wavBytesHaveAValidMonoPcm16Header() {
        val samples = shortArrayOf(0, 1000, -1000, 32767, -32768)
        val bytes = F.wavBytes(samples, 22050)
        assertEquals(44 + samples.size * 2, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + samples.size * 2, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, b.getInt(16))
        assertEquals(1.toShort(), b.getShort(20)) // PCM
        assertEquals(1.toShort(), b.getShort(22)) // mono
        assertEquals(22050, b.getInt(24))
        assertEquals(44100, b.getInt(28)) // byte rate
        assertEquals(2.toShort(), b.getShort(32)) // block align
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(samples.size * 2, b.getInt(40))
        for (i in samples.indices) assertEquals(samples[i], b.getShort(44 + 2 * i))
    }

    @Test
    fun writeAudioFilesWritesThreeWavsOfTheRightLength() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rigscript_diag_${System.nanoTime()}")
        try {
            val files = F.writeAudioFiles(dir)
            assertEquals(44L + 6 * rate * 2, files.narration.length())
            assertEquals(44L + 6 * rate * 2, files.music.length())
            assertEquals(44L + (0.1 * rate).roundToInt() * 2, files.sfx.length())
            // Writing again overwrites, it does not append or fail.
            F.writeAudioFiles(dir)
            assertEquals(44L + 6 * rate * 2, files.narration.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------------------- the matrix

    @Test
    fun theSevenCasesAreDistinctNonEmptyAndInAFixedOrder() {
        assertEquals(7, F.AUDIO_CASES.size)
        assertEquals(7, F.AUDIO_CASES.toSet().size)
        assertTrue(F.AUDIO_CASES.all { it.isNotEmpty() })
        assertEquals(setOf(Source.NARRATION), F.AUDIO_CASES.first())
        assertEquals(GlesAcceptance.ALL_SOURCES, F.AUDIO_CASES.last())
        assertEquals(
            listOf("narration", "music", "sfx", "narration+music", "narration+sfx", "music+sfx", "narration+music+sfx"),
            F.AUDIO_CASES.map { F.caseLabel(it) }
        )
    }

    @Test
    fun theCueWindowsAreCentredOnTheMiddleOfEachBurst() {
        assertEquals(listOf(1.05, 2.55, 4.05), F.sfxWindowCentersSec().map { Math.round(it * 100) / 100.0 })
        // A window centred on the cue itself would see half a burst: show that the burst centre is the better choice.
        val burst = F.sfxSamples()
        val track = ShortArray(3 * rate)
        System.arraycopy(burst, 0, track, rate, burst.size) // burst starts at 1.0 s
        val onCue = DiagnosticsDsp.toneInWindows(track, rate, F.SFX_HZ, listOf(1.0)).insideMin
        val onBurst = DiagnosticsDsp.toneInWindows(track, rate, F.SFX_HZ, listOf(1.05)).insideMin
        assertTrue("centred on the burst $onBurst should beat centred on the cue $onCue", onBurst > onCue * 1.5)
    }

    @Test
    fun theLoudestPossibleOverlapStaysUnderTheLimiterKnee() {
        val worst = F.NARRATION_LEVEL + F.MUSIC_LEVEL * F.MUSIC_VOLUME + F.SFX_LEVEL
        assertTrue("worst-case sum $worst vs knee ${AudioMath.LIMITER_KNEE}", worst < AudioMath.LIMITER_KNEE)
    }

    private fun addAt(sum: IntArray, source: ShortArray, start: Int, gain: Double) {
        for (i in source.indices) {
            val at = start + i
            if (at in sum.indices) sum[at] += (source[i] * gain).roundToInt()
        }
    }

    /** What a correct export of [sources] should contain: narration 0-6 s, music looped over 7 s, bursts at the cues. */
    private fun syntheticTrack(sources: Set<Source>): ShortArray {
        val total = (F.AUDIO_EXPECTED_TOTAL_SEC * rate).roundToInt()
        val sum = IntArray(total)
        if (Source.NARRATION in sources) addAt(sum, F.narrationSamples(), 0, 1.0)
        if (Source.MUSIC in sources) {
            val music = F.musicSamples()
            var at = 0
            while (at < total) {
                addAt(sum, music, at, F.MUSIC_VOLUME.toDouble())
                at += music.size
            }
        }
        if (Source.SFX in sources) for (cue in F.SFX_CUES_SEC) addAt(sum, F.sfxSamples(), (cue * rate).roundToInt(), 1.0)
        return ShortArray(total) { sum[it].coerceIn(-32768, 32767).toShort() }
    }

    /** The same measurements `DiagnosticsMedia.analyzeAudio` makes, on a synthetic track. */
    private fun measure(samples: ShortArray): AudioMetrics {
        val bursts = DiagnosticsDsp.toneInWindows(samples, rate, F.SFX_HZ, F.sfxWindowCentersSec())
        return AudioMetrics(
            hasAudioTrack = true,
            durationSec = samples.size.toDouble() / rate,
            expectedDurationSec = F.AUDIO_EXPECTED_TOTAL_SEC,
            narrationAmplitude = level(samples, F.NARRATION_HZ),
            musicAmplitude = level(samples, F.MUSIC_HZ),
            sfxAmplitude = level(samples, F.SFX_HZ),
            sfxCueMin = bursts.insideMin,
            sfxOutside = bursts.outside
        )
    }

    @Test
    fun everyCaseOfTheMatrixPassesOnItsOwnSyntheticTrack() {
        for (sources in F.AUDIO_CASES) {
            val m = measure(syntheticTrack(sources))
            val verdict = GlesAcceptance.evaluateAudioCell(F.caseLabel(sources), sources, m)
            assertTrue("${F.caseLabel(sources)}: ${verdict.failures} from $m", verdict.pass)
        }
    }

    @Test
    fun aMissingOrUnexpectedSourceFailsTheCell() {
        // Narration only, but the cell expects narration + music: the music is missing.
        val narrationOnly = measure(syntheticTrack(setOf(Source.NARRATION)))
        val missing = GlesAcceptance.evaluateAudioCell("x", setOf(Source.NARRATION, Source.MUSIC), narrationOnly)
        assertFalse(missing.pass)
        assertTrue(missing.failures.any { it.contains("music") })
        // The full mix, but the cell expects narration only: music and sfx are present where they must be absent.
        val full = measure(syntheticTrack(GlesAcceptance.ALL_SOURCES))
        val extra = GlesAcceptance.evaluateAudioCell("x", setOf(Source.NARRATION), full)
        assertFalse(extra.pass)
        assertTrue(extra.failures.any { it.contains("music") })
        assertTrue(extra.failures.any { it.contains("sfx") })
        // A silent track fails every expected source.
        val silent = measure(ShortArray((F.AUDIO_EXPECTED_TOTAL_SEC * rate).toInt()))
        assertFalse(GlesAcceptance.evaluateAudioCell("x", GlesAcceptance.ALL_SOURCES, silent).pass)
    }

    @Test
    fun aMixLosingItsLastCueIsCaughtByTheCueWindows() {
        val full = syntheticTrack(setOf(Source.SFX))
        // Erase the burst at 4.0 s, as a mixer that dropped a cue would.
        val start = (4.0 * rate).roundToInt()
        for (i in 0 until (0.1 * rate).roundToInt() + 10) if (start + i < full.size) full[start + i] = 0
        val verdict = GlesAcceptance.evaluateAudioCell("sfx", setOf(Source.SFX), measure(full))
        assertFalse("a missing cue must fail: ${verdict.failures}", verdict.pass)
    }

    // ------------------------------------------------------------------------------------------------- scripts and projects

    @Test
    fun theAudioScriptCarriesTheCuesOnlyWhenAsked() {
        val with = F.audioScriptJson(withSfx = true)
        val without = F.audioScriptJson(withSfx = false)
        assertEquals(3, Regex("\"soundEffect\":\"${F.SFX_CLIP_ID}\"").findAll(with).count())
        assertEquals(0, Regex("soundEffect").findAll(without).count())
        // Same timeline either way.
        assertEquals(5, Regex("\"timeSec\"").findAll(with).count())
        assertEquals(5, Regex("\"timeSec\"").findAll(without).count())
        for (json in listOf(with, without, F.visualScriptJson())) {
            assertEquals("braces", json.count { it == '{' }, json.count { it == '}' })
            assertEquals("brackets", json.count { it == '[' }, json.count { it == ']' })
        }
    }

    @Test
    fun everyCaseExportsForTheSameLengthWhateverItsSources() {
        assertEquals(7f, ExportAudioPlan.totalSec(F.AUDIO_SCRIPT_END_SEC.toFloat(), F.NARRATION_SEC.toFloat()), 0f)
        assertEquals(7f, ExportAudioPlan.totalSec(F.AUDIO_SCRIPT_END_SEC.toFloat(), 0f), 0f)
        near("expected total", F.AUDIO_EXPECTED_TOTAL_SEC, F.AUDIO_SCRIPT_END_SEC + 1.0, 1e-9)
    }

    @Test
    fun anAudioProjectHoldsOnlyTheSourcesOfItsCase() {
        val files = DiagnosticsFixtures.Files(File("/d/n.wav"), File("/d/m.wav"), File("/d/s.wav"))
        for (sources in F.AUDIO_CASES) {
            val p = F.audioProject(sources, files, AnimScript.EMPTY, "GLES")
            val label = F.caseLabel(sources)
            if (Source.NARRATION in sources) {
                assertEquals(label, files.narration.absolutePath, p.audioFilePath)
                near(label, 6.0, p.audioDurationSec.toDouble(), 1e-6)
            } else {
                assertTrue(label, p.audioFilePath == null)
                near(label, 0.0, p.audioDurationSec.toDouble(), 1e-6)
            }
            if (Source.MUSIC in sources) {
                assertEquals(label, files.music.absolutePath, p.backgroundMusic.musicFilePath)
                near(label, F.MUSIC_VOLUME.toDouble(), p.backgroundMusic.volume.toDouble(), 1e-6)
                assertTrue(label, p.backgroundMusic.loop)
            } else {
                assertTrue(label, p.backgroundMusic.musicFilePath == null)
            }
            if (Source.SFX in sources) {
                assertEquals(label, 1, p.soundEffects.size)
                assertEquals(label, F.SFX_CLIP_ID, p.soundEffects[0].id)
                assertEquals(label, files.sfx.absolutePath, p.soundEffects[0].filePath)
            } else {
                assertTrue(label, p.soundEffects.isEmpty())
            }
            // The settings the matrix fixes: 360p portrait, embedded audio, one aspect, the chosen renderer.
            assertEquals("GLES", p.exportSettings.renderer)
            assertEquals("360p", p.exportSettings.resolution)
            assertEquals("9:16", p.exportSettings.aspectRatio)
            assertTrue(p.exportSettings.embedAudio)
            assertFalse(p.exportSettings.dualAspectExport)
        }
    }

    @Test
    fun projectsOfTheTwoBackendsDifferOnlyInTheRendererAndTheNames() {
        val files = DiagnosticsFixtures.Files(File("/d/n.wav"), File("/d/m.wav"), File("/d/s.wav"))
        val canvas = F.audioProject(GlesAcceptance.ALL_SOURCES, files, AnimScript.EMPTY, "CANVAS")
        val gles = F.audioProject(GlesAcceptance.ALL_SOURCES, files, AnimScript.EMPTY, "GLES")
        assertEquals("CANVAS", canvas.exportSettings.renderer)
        assertEquals("GLES", gles.exportSettings.renderer)
        assertEquals(canvas.copy(id = gles.id, projectName = gles.projectName, exportSettings = gles.exportSettings), gles)
        assertTrue(canvas.projectName != gles.projectName)
        assertTrue(canvas.id != gles.id)
        // Fixed, so a project built twice is equal (no clock, no random id).
        assertEquals(canvas, F.audioProject(GlesAcceptance.ALL_SOURCES, files, AnimScript.EMPTY, "CANVAS"))
    }

    @Test
    fun theVisualProjectHasNoAudioAndTheRightSamples() {
        val p = F.visualProject(AnimScript.EMPTY, "CANVAS")
        assertNull(p.audioFilePath)
        assertEquals(0f, p.audioDurationSec, 0f)
        assertTrue(p.soundEffects.isEmpty())
        assertNull(p.backgroundMusic.musicFilePath)
        assertEquals(GlesAcceptance.REQUIRED_FRAMES, F.VISUAL_SAMPLE_TIMES_SEC.size)
        // Every sample lies inside the 3 s export (script end 2.0 s plus the second of tail), sorted, distinct.
        assertTrue(F.VISUAL_SAMPLE_TIMES_SEC.all { it > 0.0 && it < F.VISUAL_SCRIPT_END_SEC + 1.0 })
        assertEquals(F.VISUAL_SAMPLE_TIMES_SEC.sorted(), F.VISUAL_SAMPLE_TIMES_SEC)
        assertEquals(F.VISUAL_SAMPLE_TIMES_SEC.size, F.VISUAL_SAMPLE_TIMES_SEC.toSet().size)
        assertNotNull(p.exportSettings)
    }
}
