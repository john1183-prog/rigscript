package com.example.engine

import com.example.engine.GlesAcceptance.AudioMetrics
import com.example.engine.GlesAcceptance.Source
import java.util.Locale

/**
 * What the GLES self-test found, and how it is written down (WP1.6c). Pure Kotlin with no Android types, so the
 * verdict assembly, the summary text and the JSON are tested on the JVM. [DiagnosticsRunner] (Android) produces the
 * [AudioCell]s and the [ParityResult]; this object turns them into the numbers [GlesAcceptance] judges, into
 * `diagnostics_summary.txt` (for a person, or to paste into a chat) and into `diagnostics_report.json` (for a tool).
 *
 * A missing measurement is never a pass: a cell whose export failed, or a frame that could not be compared, shows up
 * as a failure with its reason, and a run whose Canvas reference could not be made is INCONCLUSIVE rather than a
 * verdict against GLES (Canvas is the yardstick; if the yardstick is broken the check says nothing about GLES).
 */
object DiagnosticsReport {

    /** One export of the audio matrix, already judged. */
    class AudioCell(
        /** `"CANVAS"` or `"GLES"`. */
        val backend: String,
        val sources: Set<Source>,
        /** Null when the export failed or timed out (see [error]). */
        val metrics: AudioMetrics?,
        val trackDurationSec: Double,
        val sampleRate: Int,
        val channels: Int,
        val elapsedMs: Long,
        /** Why no file was produced, or null. */
        val error: String?,
        /** What the export itself reported it did not do (a skipped cue, a failed mix). */
        val warnings: List<String>,
        /** Every failed criterion, each prefixed with [label]. Empty means the cell passed. */
        val failures: List<String>
    ) {
        val label: String get() = "$backend ${DiagnosticsFixtures.caseLabel(sources)}"
        val pass: Boolean get() = error == null && failures.isEmpty()

        companion object {
            /** Judges a cell: [error] (no file) wins over the measurements; otherwise [GlesAcceptance.evaluateAudioCell]. */
            fun of(
                backend: String,
                sources: Set<Source>,
                metrics: AudioMetrics?,
                trackDurationSec: Double,
                sampleRate: Int,
                channels: Int,
                elapsedMs: Long,
                error: String?,
                warnings: List<String>
            ): AudioCell {
                val label = "$backend ${DiagnosticsFixtures.caseLabel(sources)}"
                val failures = when {
                    error != null -> listOf("$label: $error")
                    else -> GlesAcceptance.evaluateAudioCell(label, sources, metrics).failures
                }
                return AudioCell(
                    backend, sources, metrics, trackDurationSec, sampleRate, channels, elapsedMs, error, warnings, failures
                )
            }
        }
    }

    /** One compared frame of the visual fixture. */
    class FrameEntry(val timeSec: Double, val metrics: FrameMetrics, val pngName: String?) {
        /** Every gating threshold met (a NaN never passes). The mouth ink ratio is reported only. */
        val pass: Boolean
            get() = metrics.offsetPx <= GlesAcceptance.MAX_OFFSET_PX &&
                metrics.meanAbsDiff <= GlesAcceptance.MAX_MEAN_ABS_DIFF &&
                metrics.laplacianRatio >= GlesAcceptance.MIN_LAPLACIAN_RATIO &&
                metrics.edgeAaRatio >= GlesAcceptance.MIN_EDGE_AA_RATIO
    }

    /** The two exports of the visual fixture and the frames compared between them. */
    class ParityResult(
        /** Why the Canvas reference export failed, or null. */
        val canvasError: String?,
        /** Why the GLES export failed, or null. */
        val glesError: String?,
        val canvasDurationSec: Double,
        val glesDurationSec: Double,
        val frames: List<FrameEntry>
    )

    /** Pass, fail or inconclusive. [evaluation] is null exactly when [inconclusive] is set. */
    class Verdict(val evaluation: GlesAcceptance.Evaluation?, val inconclusive: String?) {
        val pass: Boolean get() = evaluation?.pass == true
        val failures: List<String> get() = evaluation?.failures ?: emptyList()
        val word: String get() = if (inconclusive != null) "INCONCLUSIVE" else if (pass) "PASS" else "FAIL"
    }

    class Report(
        val startedAt: String,
        val totalSec: Double,
        val app: String,
        val device: String,
        /** What the stored GLES verdict was before the run (the self-test does not change it). */
        val storedVerdict: String,
        val cells: List<AudioCell>,
        val parity: ParityResult?,
        val notes: List<String>,
        /** False for the partial file written after each cell, so a crash leaves what finished. */
        val complete: Boolean
    ) {
        val verdict: Verdict by lazy { verdict(cells, parity) }
    }

    // ------------------------------------------------------------------------------------------------ verdict

    /**
     * The numbers the quick check judges, taken from the self-test's cells: the GLES narration+music+sfx mix, the GLES
     * sfx-only case and the visual parity. [CheckMetrics.glesFailure] names the first GLES export that produced no file.
     */
    fun checkMetrics(cells: List<AudioCell>, parity: ParityResult?): GlesAcceptance.CheckMetrics {
        fun find(sources: Set<Source>): AudioCell? = cells.firstOrNull { it.backend == "GLES" && it.sources == sources }
        val mix = find(GlesAcceptance.ALL_SOURCES)
        val sfx = find(setOf(Source.SFX))
        val failure: String? = when {
            parity == null -> "the parity frames were not produced"
            parity.glesError != null -> "visual fixture: ${parity.glesError}"
            mix == null || sfx == null -> "the GLES audio cells were not run"
            mix.error != null -> "narration+music+sfx: ${mix.error}"
            sfx.error != null -> "sfx only: ${sfx.error}"
            else -> null
        }
        return GlesAcceptance.CheckMetrics(
            glesFailure = failure,
            mixOnGles = mix?.metrics,
            sfxOnlyOnGles = sfx?.metrics,
            canvasDurationSec = parity?.canvasDurationSec ?: Double.NaN,
            glesDurationSec = parity?.glesDurationSec ?: Double.NaN,
            frames = parity?.frames?.map { it.metrics } ?: emptyList()
        )
    }

    /** What the quick check would decide from these cells. INCONCLUSIVE when the Canvas reference is missing. */
    fun verdict(cells: List<AudioCell>, parity: ParityResult?): Verdict {
        if (parity == null) return Verdict(null, "the parity frames were not produced")
        if (parity.canvasError != null) return Verdict(null, "the Canvas reference export failed: ${parity.canvasError}")
        return Verdict(GlesAcceptance.evaluate(checkMetrics(cells, parity)), null)
    }

    // ------------------------------------------------------------------------------------------------ summary text

    private fun f(x: Double, digits: Int = 3): String =
        if (x.isNaN() || x.isInfinite()) "n/a" else String.format(Locale.ROOT, "%.${digits}f", x)

    /** The plain-text report: short enough to paste into a chat, complete enough to act on. */
    fun summary(report: Report): String {
        val sb = StringBuilder()
        sb.append("RigScript GLES self-test").append(if (report.complete) "" else "  (INCOMPLETE: the run stopped early)").append('\n')
        sb.append("Started: ").append(report.startedAt).append("   Run time: ").append(f(report.totalSec, 1)).append(" s\n")
        sb.append("App: ").append(report.app).append('\n')
        sb.append("Device: ").append(report.device).append('\n')
        sb.append("Stored GLES verdict before the run: ").append(report.storedVerdict).append('\n')
        sb.append("NOT DEVICE-VERIFIED by anyone but you: these numbers are what this phone measured just now.\n\n")

        val v = report.verdict
        if (report.complete) {
            sb.append("VERDICT (what the quick check would decide): ").append(v.word).append('\n')
            v.inconclusive?.let { sb.append("  - ").append(it).append('\n') }
            v.failures.forEach { sb.append("  - ").append(it).append('\n') }
            sb.append("The quick check (Re-run GLES check) is what stores the verdict that Auto uses; this run does not change it.\n\n")
        }

        sb.append("AUDIO MATRIX (every file should last ").append(f(DiagnosticsFixtures.AUDIO_EXPECTED_TOTAL_SEC, 1)).append(" s)\n")
        if (report.cells.isEmpty()) sb.append("  (no cell finished)\n")
        for (cell in report.cells) {
            sb.append("  ").append(if (cell.pass) "PASS " else "FAIL ").append(cell.label)
            sb.append("  (").append(cell.elapsedMs).append(" ms)\n")
            val m = cell.metrics
            if (m != null) {
                sb.append("       duration ").append(f(m.durationSec)).append(" s (audio track ").append(f(cell.trackDurationSec))
                    .append(" s, ").append(cell.sampleRate).append(" Hz, ").append(cell.channels).append(" ch)\n")
                sb.append("       440 Hz ").append(f(m.narrationAmplitude, 4)).append("   220 Hz ").append(f(m.musicAmplitude, 4))
                    .append("   1 kHz ").append(f(m.sfxAmplitude, 4)).append("   1 kHz at cues (min) ").append(f(m.sfxCueMin, 4))
                    .append("   1 kHz elsewhere ").append(f(m.sfxOutside, 4)).append('\n')
            }
            cell.failures.forEach { sb.append("       - ").append(it).append('\n') }
            cell.warnings.forEach { sb.append("       ! ").append(it).append('\n') }
        }
        sb.append('\n')

        sb.append("PARITY FRAMES (GLES against Canvas, visual fixture)\n")
        val p = report.parity
        if (p == null) {
            sb.append("  (not run)\n")
        } else {
            p.canvasError?.let { sb.append("  Canvas export failed: ").append(it).append('\n') }
            p.glesError?.let { sb.append("  GLES export failed: ").append(it).append('\n') }
            sb.append("  video duration: Canvas ").append(f(p.canvasDurationSec)).append(" s, GLES ").append(f(p.glesDurationSec)).append(" s\n")
            p.frames.forEachIndexed { i, e ->
                val m = e.metrics
                sb.append("  ").append(if (e.pass) "PASS " else "FAIL ").append("frame ").append(i + 1).append(" at ").append(f(e.timeSec, 1)).append(" s:")
                    .append(" offset ").append(f(m.offsetPx)).append(" px, mean diff ").append(f(m.meanAbsDiff, 2))
                    .append(", sharpness ").append(f(m.laplacianRatio, 2)).append(", anti-aliasing ").append(f(m.edgeAaRatio, 2))
                    .append(", mouth ink ").append(f(m.mouthInkRatio, 2)).append(" (reported only)")
                e.pngName?.let { sb.append("  [").append(it).append(']') }
                sb.append('\n')
            }
        }
        sb.append('\n')

        sb.append("THRESHOLDS (GlesAcceptance): offset <= ").append(f(GlesAcceptance.MAX_OFFSET_PX, 2)).append(" px, mean diff <= ")
            .append(f(GlesAcceptance.MAX_MEAN_ABS_DIFF, 1)).append(", sharpness ratio >= ").append(f(GlesAcceptance.MIN_LAPLACIAN_RATIO, 2))
            .append(", anti-aliasing ratio >= ").append(f(GlesAcceptance.MIN_EDGE_AA_RATIO, 2)).append(", durations within ")
            .append(f(GlesAcceptance.MAX_DURATION_DELTA_SEC, 3)).append(" s, present tone >= ").append(f(GlesAcceptance.MIN_PRESENT_AMPLITUDE, 3))
            .append(", absent tone <= ").append(f(GlesAcceptance.MAX_ABSENT_AMPLITUDE, 3)).append(", sfx cue/background >= ")
            .append(f(GlesAcceptance.MIN_SFX_CUE_RATIO, 1)).append('\n')

        if (report.notes.isNotEmpty()) {
            sb.append("\nNOTES\n")
            report.notes.forEach { sb.append("  ").append(it).append('\n') }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------------ JSON

    /** The report as a map tree (insertion order kept) for [toJson]. Numbers that are NaN or infinite become null there. */
    fun toTree(report: Report): Map<String, Any?> {
        val v = report.verdict
        fun metricsTree(m: AudioMetrics?): Map<String, Any?>? = m?.let {
            linkedMapOf(
                "hasAudioTrack" to it.hasAudioTrack,
                "durationSec" to it.durationSec,
                "expectedDurationSec" to it.expectedDurationSec,
                "narrationAmplitude440" to it.narrationAmplitude,
                "musicAmplitude220" to it.musicAmplitude,
                "sfxAmplitude1000" to it.sfxAmplitude,
                "sfxCueMin" to it.sfxCueMin,
                "sfxOutside" to it.sfxOutside
            )
        }
        val cells = report.cells.map { c ->
            linkedMapOf<String, Any?>(
                "label" to c.label,
                "backend" to c.backend,
                "sources" to Source.values().filter { it in c.sources }.map { it.name.lowercase() },
                "pass" to c.pass,
                "error" to c.error,
                "failures" to c.failures,
                "warnings" to c.warnings,
                "elapsedMs" to c.elapsedMs,
                "trackDurationSec" to c.trackDurationSec,
                "sampleRate" to c.sampleRate,
                "channels" to c.channels,
                "metrics" to metricsTree(c.metrics)
            )
        }
        val parity: Map<String, Any?>? = report.parity?.let { p ->
            linkedMapOf(
                "canvasError" to p.canvasError,
                "glesError" to p.glesError,
                "canvasDurationSec" to p.canvasDurationSec,
                "glesDurationSec" to p.glesDurationSec,
                "frames" to p.frames.map { e ->
                    linkedMapOf<String, Any?>(
                        "timeSec" to e.timeSec,
                        "pass" to e.pass,
                        "offsetPx" to e.metrics.offsetPx,
                        "meanAbsDiff" to e.metrics.meanAbsDiff,
                        "laplacianRatio" to e.metrics.laplacianRatio,
                        "edgeAaRatio" to e.metrics.edgeAaRatio,
                        "mouthInkRatio" to e.metrics.mouthInkRatio,
                        "png" to e.pngName
                    )
                }
            )
        }
        return linkedMapOf(
            "startedAt" to report.startedAt,
            "totalSec" to report.totalSec,
            "complete" to report.complete,
            "app" to report.app,
            "device" to report.device,
            "storedVerdictBefore" to report.storedVerdict,
            "verdict" to linkedMapOf(
                "result" to v.word,
                "pass" to v.pass,
                "inconclusive" to v.inconclusive,
                "failures" to v.failures
            ),
            "thresholds" to linkedMapOf(
                "maxOffsetPx" to GlesAcceptance.MAX_OFFSET_PX,
                "maxMeanAbsDiff" to GlesAcceptance.MAX_MEAN_ABS_DIFF,
                "minLaplacianRatio" to GlesAcceptance.MIN_LAPLACIAN_RATIO,
                "minEdgeAaRatio" to GlesAcceptance.MIN_EDGE_AA_RATIO,
                "maxDurationDeltaSec" to GlesAcceptance.MAX_DURATION_DELTA_SEC,
                "minPresentAmplitude" to GlesAcceptance.MIN_PRESENT_AMPLITUDE,
                "maxAbsentAmplitude" to GlesAcceptance.MAX_ABSENT_AMPLITUDE,
                "minSfxCueRatio" to GlesAcceptance.MIN_SFX_CUE_RATIO
            ),
            "audioMatrix" to cells,
            "parity" to parity,
            "notes" to report.notes
        )
    }

    fun toJson(report: Report): String = renderJson(toTree(report))

    /** A small JSON writer for maps, lists, strings, numbers, booleans and null (two-space indent). NaN and infinity become null. */
    fun renderJson(value: Any?): String {
        val sb = StringBuilder()
        writeJson(sb, value, 0)
        sb.append('\n')
        return sb.toString()
    }

    private fun writeJson(sb: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Int, is Long -> sb.append(value.toString())
            is Double -> sb.append(if (value.isNaN() || value.isInfinite()) "null" else value.toString())
            is Float -> sb.append(if (value.isNaN() || value.isInfinite()) "null" else value.toString())
            is String -> quote(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    var first = true
                    for ((k, v) in value) {
                        if (!first) sb.append(",\n")
                        first = false
                        indent(sb, depth + 1)
                        quote(sb, k.toString())
                        sb.append(": ")
                        writeJson(sb, v, depth + 1)
                    }
                    sb.append('\n')
                    indent(sb, depth)
                    sb.append('}')
                }
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                } else {
                    sb.append("[\n")
                    value.forEachIndexed { i, v ->
                        if (i > 0) sb.append(",\n")
                        indent(sb, depth + 1)
                        writeJson(sb, v, depth + 1)
                    }
                    sb.append('\n')
                    indent(sb, depth)
                    sb.append(']')
                }
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        for (i in 0 until depth) sb.append("  ")
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
