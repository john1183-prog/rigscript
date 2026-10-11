package com.example

import com.example.data.AmplitudeSettings
import com.example.data.AnimScript
import com.example.engine.BakedKeyframe
import com.example.engine.MouthShape
import com.example.engine.PlaybackEngine
import com.example.engine.StickFigureRig
import com.example.engine.TimelineCompiler

/**
 * Builds and drives a [PlaybackEngine] the way the app does (WP0.4). The wiring mirrors
 * VideoExporter (compile, load schedules, captions, overlays) and its per-frame call
 * `seekToWithAmplitude`; the engine has no Android dependency, so it runs on the JVM unchanged.
 */
object EngineHarness {

    /** AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS (that object needs android.media, so it is not touched here). */
    const val ENV_FPS = 30

    /** Same pose lookup as MainViewModel.compileTimeline, minus custom project poses. */
    fun compile(script: AnimScript): List<BakedKeyframe> =
        TimelineCompiler.compile(script) { id -> StickFigureRig.BUILT_IN_POSE_INDEX[id] }

    /** VideoExporter's length rule: the later of the script end and the audio, plus one second. */
    fun totalSec(keyframes: List<BakedKeyframe>, audioSec: Float): Float {
        val last = keyframes.lastOrNull()
        val scriptEnd = if (last != null) last.timeSec + last.duration else 0f
        return maxOf(scriptEnd, audioSec) + 1f
    }

    fun create(
        script: AnimScript,
        envelope: FloatArray,
        settings: AmplitudeSettings = AmplitudeSettings(),
        keyframes: List<BakedKeyframe> = compile(script)
    ): PlaybackEngine {
        val total = totalSec(keyframes, envelope.size.toFloat() / ENV_FPS)
        return PlaybackEngine().also {
            it.loadTimeline(keyframes)
            it.amplitudeSettings = settings
            it.loadBlinkSchedule(script.blinkEvents, total)
            it.loadFidgetSchedule(envelope, ENV_FPS)
            it.loadCaptions(TimelineCompiler.extractCaptions(script))
            it.loadOverlayLayers(TimelineCompiler.extractOverlayLayers(script))
            it.loadSoundEffectCues(TimelineCompiler.extractSoundEffectCues(script))
        }
    }

    /** Envelope sample for a time, as VideoExporter reads it (clamped index, 0 when there is no envelope). */
    fun rawAmp(envelope: FloatArray, timeSec: Float): Float {
        if (envelope.isEmpty()) return 0f
        val idx = (timeSec * ENV_FPS).toInt().coerceIn(0, envelope.size - 1)
        return envelope[idx]
    }

    /**
     * Stand-in for the analyzer's mouth shapes (real ones come from audio and a zero-crossing rate):
     * the analyzer's own silence and wide thresholds, nothing else.
     */
    fun mouthFor(amp: Float): Int = when {
        amp < 0.06f -> MouthShape.CLOSED
        amp >= 0.35f -> MouthShape.WIDE
        else -> MouthShape.MID
    }

    /** What the export loop does for one frame. */
    fun seekFrame(engine: PlaybackEngine, envelope: FloatArray, timeSec: Float) {
        val amp = rawAmp(envelope, timeSec)
        engine.seekToWithAmplitude(timeSec, amp, mouthFor(amp))
    }
}
