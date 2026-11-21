package com.example.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The on-device definition of "GLES works flawlessly" (WP1.6): the thresholds as named constants and a pure verdict
 * over measured numbers. The Android glue (WP1.6b to d) produces [CheckMetrics] (and, for the Self-test's audio
 * matrix, [AudioMetrics]) with [DiagnosticsDsp]; nothing here touches Android, files or the clock.
 *
 * Rules of the road: a number that is NaN, or a measurement that is missing, FAILS (never a silent pass); a GLES frame
 * that is sharper or smoother than Canvas does not fail (the ratios are one-sided); the mouth ink ratio is reported,
 * not gating. The thresholds are starting points (the campaign brief's), to be tuned from device reports.
 */
object GlesAcceptance {

    /** Estimated GLES-vs-Canvas offset, pixels. R1 (a vertical offset of about 1.75 px) sits well above it. */
    const val MAX_OFFSET_PX = 0.75

    /** Mean absolute gray difference inside the figure's bounding box. */
    const val MAX_MEAN_ABS_DIFF = 6.0

    /** Laplacian variance GLES / Canvas on text: GLES must be at least this sharp. */
    const val MIN_LAPLACIAN_RATIO = 0.90

    /** Intermediate-gray edge pixels GLES / Canvas: GLES must have at least this much anti-aliasing. */
    const val MIN_EDGE_AA_RATIO = 0.85

    /** The exported GLES and Canvas videos may differ in duration by at most this. */
    const val MAX_DURATION_DELTA_SEC = 0.100

    /** Frames the quick check compares. */
    const val REQUIRED_FRAMES = 4

    /** A source the cell expects must reach this tone amplitude (fraction of full scale, about -40 dBFS). */
    const val MIN_PRESENT_AMPLITUDE = 0.01

    /** A source the cell does not expect must stay below this (about -50 dBFS). */
    const val MAX_ABSENT_AMPLITUDE = 0.003

    /** The weakest SFX cue window against the 1 kHz level away from the cues. */
    const val MIN_SFX_CUE_RATIO = 4.0

    /** The three fixture sources: narration (440 Hz), music (220 Hz) and SFX bursts (1 kHz). */
    enum class Source { NARRATION, MUSIC, SFX }

    val ALL_SOURCES: Set<Source> = setOf(Source.NARRATION, Source.MUSIC, Source.SFX)

    /** What the audio analysis measured in one exported file: one cell of the matrix, or the quick check's mix. */
    data class AudioMetrics(
        val hasAudioTrack: Boolean,
        val durationSec: Double,
        val expectedDurationSec: Double,
        /** 440 Hz amplitude over the whole track (narration). */
        val narrationAmplitude: Double,
        /** 220 Hz amplitude over the whole track (music). */
        val musicAmplitude: Double,
        /** 1 kHz amplitude over the whole track; only used to prove an unexpected SFX is absent. */
        val sfxAmplitude: Double,
        /** 1 kHz amplitude in the weakest +-50 ms cue window. */
        val sfxCueMin: Double,
        /** 1 kHz amplitude away from the cues. */
        val sfxOutside: Double
    )

    /** Everything the quick check measured. */
    data class CheckMetrics(
        /** null when the GLES export ran, finished in time and its output decodes; otherwise why not. */
        val glesFailure: String?,
        val mixOnGles: AudioMetrics?,
        val sfxOnlyOnGles: AudioMetrics?,
        val canvasDurationSec: Double,
        val glesDurationSec: Double,
        val frames: List<FrameMetrics>
    )

    /** The outcome: every failed criterion, in order, each naming the number and the limit. Empty means pass. */
    class Evaluation(val failures: List<String>) {
        val pass: Boolean get() = failures.isEmpty()

        /** One line for the stored verdict reason: the first failure and how many more there are. */
        fun summary(maxLength: Int = 150): String {
            if (failures.isEmpty()) return "all checks passed"
            val text = if (failures.size > 1) "${failures[0]} (+${failures.size - 1} more)" else failures[0]
            return if (text.length <= maxLength) text else text.substring(0, maxLength - 3) + "..."
        }

        /** The verdict the app stores: [GlesState.Eligible], or [GlesState.Ineligible] with [summary] as the reason. */
        fun toState(): GlesState = if (pass) GlesState.Eligible else GlesState.Ineligible(summary())
    }

    /** The verdict for one quick check. A GLES export that failed short-circuits: nothing else was measured. */
    fun evaluate(m: CheckMetrics): Evaluation {
        m.glesFailure?.let { return Evaluation(listOf("GLES export failed: $it")) }
        val failures = ArrayList<String>()
        failures += evaluateAudioCell("mix", ALL_SOURCES, m.mixOnGles).failures
        failures += evaluateAudioCell("sfx-only", setOf(Source.SFX), m.sfxOnlyOnGles).failures
        if (!(abs(m.glesDurationSec - m.canvasDurationSec) <= MAX_DURATION_DELTA_SEC)) {
            failures += "video duration GLES ${fmt(m.glesDurationSec)} s vs Canvas ${fmt(m.canvasDurationSec)} s " +
                "(limit ${fmt(MAX_DURATION_DELTA_SEC)} s)"
        }
        if (m.frames.size < REQUIRED_FRAMES) failures += "only ${m.frames.size} of $REQUIRED_FRAMES frames were compared"
        m.frames.forEachIndexed { index, frame ->
            val label = "frame ${index + 1}"
            checkMax(failures, "$label offset", frame.offsetPx, MAX_OFFSET_PX, " px")
            checkMax(failures, "$label mean difference", frame.meanAbsDiff, MAX_MEAN_ABS_DIFF)
            checkMin(failures, "$label sharpness ratio", frame.laplacianRatio, MIN_LAPLACIAN_RATIO)
            checkMin(failures, "$label anti-aliasing ratio", frame.edgeAaRatio, MIN_EDGE_AA_RATIO)
        }
        return Evaluation(failures)
    }

    /**
     * One cell of the audio matrix, or one of the quick check's files: [expected] sources must be present, every
     * other source absent, the duration must match, and an expected SFX must be loudest at its cues. [label] prefixes
     * each failure (for example "mix" or "Canvas narration+sfx").
     */
    fun evaluateAudioCell(label: String, expected: Set<Source>, m: AudioMetrics?): Evaluation {
        if (m == null) return Evaluation(listOf("$label: audio was not measured"))
        if (!m.hasAudioTrack) return Evaluation(listOf("$label: no audio track"))
        val failures = ArrayList<String>()
        if (!(abs(m.durationSec - m.expectedDurationSec) <= MAX_DURATION_DELTA_SEC)) {
            failures += "$label: duration ${fmt(m.durationSec)} s, expected ${fmt(m.expectedDurationSec)} s " +
                "(limit ${fmt(MAX_DURATION_DELTA_SEC)} s)"
        }
        presence(failures, "$label: narration (440 Hz)", Source.NARRATION in expected, m.narrationAmplitude)
        presence(failures, "$label: music (220 Hz)", Source.MUSIC in expected, m.musicAmplitude)
        if (Source.SFX in expected) {
            checkMin(failures, "$label: sfx (1 kHz) at the cues", m.sfxCueMin, MIN_PRESENT_AMPLITUDE)
            val ratio = m.sfxCueMin / max(m.sfxOutside, DiagnosticsDsp.RATIO_FLOOR)
            checkMin(failures, "$label: sfx cue-to-background ratio", ratio, MIN_SFX_CUE_RATIO)
        } else {
            checkMax(failures, "$label: sfx (1 kHz)", m.sfxAmplitude, MAX_ABSENT_AMPLITUDE)
        }
        return Evaluation(failures)
    }

    private fun presence(out: MutableList<String>, what: String, expected: Boolean, amplitude: Double) {
        if (expected) checkMin(out, what, amplitude, MIN_PRESENT_AMPLITUDE) else checkMax(out, what, amplitude, MAX_ABSENT_AMPLITUDE)
    }

    private fun checkMax(out: MutableList<String>, what: String, value: Double, limit: Double, unit: String = "") {
        if (value.isNaN()) out += "$what not measurable"
        else if (!(value <= limit)) out += "$what ${fmt(value)}$unit > ${fmt(limit)}$unit"
    }

    private fun checkMin(out: MutableList<String>, what: String, value: Double, limit: Double) {
        if (value.isNaN()) out += "$what not measurable"
        else if (!(value >= limit)) out += "$what ${fmt(value)} < ${fmt(limit)}"
    }

    private fun fmt(x: Double): String = String.format(Locale.ROOT, "%.4f", x)
}
