package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.data.AmplitudeSettings
import com.example.data.AnimScript
import com.example.data.AppJson
import com.example.data.ProjectDef
import com.example.engine.DiagnosticsReport.AudioCell
import com.example.engine.DiagnosticsReport.FrameEntry
import com.example.engine.DiagnosticsReport.ParityResult
import com.example.engine.GlesAcceptance.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the GLES self-test on the device (WP1.6c): it exports the fixtures of [DiagnosticsFixtures] on both backends,
 * analyses the files with [DiagnosticsMedia], and hands the numbers to [DiagnosticsReport] and [GlesAcceptance].
 * The decisions (what passes, how a report reads) are in those pure, tested objects; this one only sequences exports
 * and writes files.
 *
 * NOT DEVICE-TESTED. It compiles in CI and nothing more is known: every export here goes through the real
 * [VideoExporter] and [GlesExporter], so the first run on a phone is also the first run of this glue.
 *
 * Rules kept here: every export is bounded by [EXPORT_TIMEOUT_MS] (a hung encoder ends as a failed cell, not a frozen
 * screen); a failed export never throws out of a cell (it becomes the cell's error); cancellation always propagates;
 * the scratch directory (under the cache dir) is emptied at the start and removed at the end; nothing is written to
 * the gallery. Blocking work runs on [Dispatchers.Default].
 */
object DiagnosticsRunner {

    private const val TAG = "DiagnosticsRunner"

    /** The most one fixture export may take (they are 3 to 7 s long at 360p); a stuck encoder ends here. */
    const val EXPORT_TIMEOUT_MS = 90_000L

    /**
     * Serialises diagnostics: a self-test, a smoke test and a quick check never run together. Held by the caller
     * (the view model) for the whole run. The view model also refuses to start a real export while one is held.
     */
    val lock = Mutex()

    const val REPORT_JSON = "diagnostics_report.json"
    const val REPORT_TEXT = "diagnostics_summary.txt"

    /** What a finished run hands back for the screen. */
    class Outcome(val summary: String, val directory: String?)

    // ------------------------------------------------------------------------------------------------ helpers

    private fun workDir(context: Context): File = File(context.cacheDir, "diagnostics")

    /** Where the reports go: the app's external files dir (reachable from a file manager), else internal storage. */
    fun reportDir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    private fun freshWorkDir(context: Context): File {
        val dir = workDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun decode(json: String): AnimScript = AppJson.storage.decodeFromString<AnimScript>(json)

    private fun compile(script: AnimScript): List<BakedKeyframe> =
        TimelineCompiler.compile(script) { StickFigureRig.BUILT_IN_POSE_INDEX[it] }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    private fun appInfo(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} (code $code)"
    }.getOrDefault("unknown")

    private fun deviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}"

    /** One finished export, or why there is none. */
    class Exported(val path: String?, val error: String?, val elapsedMs: Long, val warnings: List<String>)

    /** Exports [project] on [backend] into [dir]. Never throws except for cancellation. */
    suspend fun exportFile(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        backend: Backend,
        dir: File
    ): Exported {
        val start = SystemClock.elapsedRealtime()
        fun since(): Long = SystemClock.elapsedRealtime() - start
        return try {
            val results = withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
                if (backend == Backend.GLES) {
                    GlesExporter.export(
                        context = context, project = project, keyframes = keyframes,
                        amplitudeSettings = AmplitudeSettings(), onProgress = { _, _ -> }, outputDir = dir
                    )
                } else {
                    VideoExporter.export(
                        context = context, project = project, keyframes = keyframes,
                        amplitudeSettings = AmplitudeSettings(), onProgress = { _, _ -> }, outputDir = dir
                    )
                }
            }
            when {
                results == null -> Exported(null, "timed out after ${EXPORT_TIMEOUT_MS / 1000} s", since(), emptyList())
                results.isEmpty() -> Exported(null, "the export wrote no file", since(), emptyList())
                else -> Exported(results.first().location, null, since(), results.flatMap { it.warnings }.distinct())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GlesExporter.GlesExportException) {
            Log.w(TAG, "GLES export failed at ${e.stage}: ${e.reason}", e)
            Exported(null, "${e.stage}: ${e.reason}", since(), emptyList())
        } catch (e: Throwable) {
            Log.w(TAG, "Export failed on $backend", e)
            Exported(null, e.message ?: e.javaClass.simpleName, since(), emptyList())
        }
    }

    /** Exports and analyses one cell of the audio matrix. The exported file is deleted afterwards. */
    suspend fun runAudioCell(
        context: Context,
        dir: File,
        files: DiagnosticsFixtures.Files,
        sources: Set<Source>,
        backend: Backend
    ): AudioCell {
        val script = decode(DiagnosticsFixtures.audioScriptJson(withSfx = Source.SFX in sources))
        val project = DiagnosticsFixtures.audioProject(sources, files, script, backend.name)
        val exported = exportFile(context, project, compile(script), backend, dir)
        val path = exported.path
            ?: return AudioCell.of(backend.name, sources, null, 0.0, 0, 0, exported.elapsedMs, exported.error, exported.warnings)
        val analysis = withContext(Dispatchers.Default) {
            val scope = this
            DiagnosticsMedia.analyzeAudio(
                path = path,
                expectedTotalSec = DiagnosticsFixtures.AUDIO_EXPECTED_TOTAL_SEC,
                cueCentersSec = DiagnosticsFixtures.sfxWindowCentersSec(),
                stillActive = { scope.isActive }
            )
        }
        runCatching { File(path).delete() }
        return AudioCell.of(
            backend.name, sources, analysis.metrics, analysis.trackDurationSec, analysis.sampleRate, analysis.channels,
            exported.elapsedMs, null, exported.warnings
        )
    }

    /** What [exportVisualPair] made: the two files (or why not) and their container durations. */
    class VisualPair(
        val canvas: Exported,
        val gles: Exported?,
        val canvasDurationSec: Double,
        val glesDurationSec: Double
    )

    /** Exports the visual fixture on Canvas, then on GLES (skipped when Canvas failed: there is nothing to compare to). */
    suspend fun exportVisualPair(context: Context, dir: File): VisualPair {
        val script = decode(DiagnosticsFixtures.visualScriptJson())
        val keyframes = compile(script)
        val canvas = exportFile(context, DiagnosticsFixtures.visualProject(script, "CANVAS"), keyframes, Backend.CANVAS, dir)
        if (canvas.path == null) return VisualPair(canvas, null, Double.NaN, Double.NaN)
        val gles = exportFile(context, DiagnosticsFixtures.visualProject(script, "GLES"), keyframes, Backend.GLES, dir)
        val canvasSec = withContext(Dispatchers.Default) { DiagnosticsMedia.containerDurationSec(canvas.path) }
        val glesSec = gles.path?.let { p -> withContext(Dispatchers.Default) { DiagnosticsMedia.containerDurationSec(p) } } ?: Double.NaN
        return VisualPair(canvas, gles, canvasSec, glesSec)
    }

    /** Compares the frames of a [VisualPair]; [pngDir] non-null also writes one side-by-side PNG per frame. */
    suspend fun compareVisualPair(pair: VisualPair, pngDir: File?): ParityResult {
        val canvasPath = pair.canvas.path
        val glesPath = pair.gles?.path
        if (canvasPath == null || glesPath == null) {
            return ParityResult(pair.canvas.error, pair.gles?.error, pair.canvasDurationSec, pair.glesDurationSec, emptyList())
        }
        val times = DiagnosticsFixtures.VISUAL_SAMPLE_TIMES_SEC
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val metrics = DiagnosticsMedia.compareVisual(canvasPath, glesPath, times)
            val pngNames = if (pngDir != null) savePairPngs(canvasPath, glesPath, times, pngDir) else List(times.size) { null }
            val frames = times.indices.map { FrameEntry(times[it], metrics[it], pngNames[it]) }
            ParityResult(null, null, pair.canvasDurationSec, pair.glesDurationSec, frames)
        }
    }

    // ------------------------------------------------------------------------------------------------ PNGs

    /** Canvas on the left, GLES on the right, one PNG per sampled time. Returns the file names (null where none). */
    private fun savePairPngs(canvasPath: String, glesPath: String, times: List<Double>, dir: File): List<String?> {
        dir.mkdirs()
        val canvasFrames = DiagnosticsMedia.extractFrames(canvasPath, times)
        val glesFrames = DiagnosticsMedia.extractFrames(glesPath, times)
        return times.indices.map { i ->
            val name = "diag_parity_${i + 1}_${(times[i] * 10).toInt()}.png"
            if (saveSideBySide(canvasFrames[i], glesFrames[i], File(dir, name))) name else null
        }
    }

    private fun saveSideBySide(canvas: DiagnosticsMedia.Frame?, gles: DiagnosticsMedia.Frame?, file: File): Boolean {
        val w = canvas?.width ?: gles?.width ?: return false
        val h = canvas?.height ?: gles?.height ?: return false
        return try {
            val gap = 8
            val bitmap = Bitmap.createBitmap(w * 2 + gap, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GRAY)
            if (canvas != null && canvas.width == w && canvas.height == h) bitmap.setPixels(canvas.pixels, 0, w, 0, 0, w, h)
            if (gles != null && gles.width == w && gles.height == h) bitmap.setPixels(gles.pixels, 0, w, w + gap, 0, w, h)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.w(TAG, "PNG not written: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------------------------------------ report files

    private fun writeReport(context: Context, report: DiagnosticsReport.Report): String? = try {
        val dir = reportDir(context)
        dir.mkdirs()
        File(dir, REPORT_TEXT).writeText(DiagnosticsReport.summary(report), Charsets.UTF_8)
        File(dir, REPORT_JSON).writeText(DiagnosticsReport.toJson(report), Charsets.UTF_8)
        dir.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "Report not written: ${e.message}")
        null
    }

    // ------------------------------------------------------------------------------------------------ the full self-test

    /**
     * The full self-test: the 14 cells of the audio matrix (7 source sets on Canvas and on GLES), then the parity
     * frames. [onLine] gets a short progress line before each step. A partial report is written after every cell, so
     * a native crash still leaves what had finished. Does NOT change the stored GLES verdict (only the quick check does).
     */
    suspend fun runSelfTest(context: Context, storedVerdict: String, onLine: (String) -> Unit): Outcome {
        val app = context.applicationContext
        val started = SystemClock.elapsedRealtime()
        val startedAt = now()
        val dir = freshWorkDir(app)
        val notes = ArrayList<String>()
        val cells = ArrayList<AudioCell>()
        fun report(parity: ParityResult?, complete: Boolean) = DiagnosticsReport.Report(
            startedAt, (SystemClock.elapsedRealtime() - started) / 1000.0, appInfo(app), deviceInfo(), storedVerdict,
            cells.toList(), parity, notes.toList(), complete
        )
        try {
            onLine("Writing the test tones")
            val files = withContext(Dispatchers.Default) { DiagnosticsFixtures.writeAudioFiles(dir) }
            val total = DiagnosticsFixtures.AUDIO_CASES.size * 2
            var done = 0
            for (backend in listOf(Backend.CANVAS, Backend.GLES)) {
                for (sources in DiagnosticsFixtures.AUDIO_CASES) {
                    currentCoroutineContext().ensureActive()
                    onLine("Audio ${done + 1} of $total: ${backend.name} ${DiagnosticsFixtures.caseLabel(sources)}")
                    cells += runAudioCell(app, dir, files, sources, backend)
                    done++
                    writeReport(app, report(null, complete = false))
                }
            }
            currentCoroutineContext().ensureActive()
            onLine("Parity frames: exporting the visual fixture on Canvas and GLES")
            val pair = exportVisualPair(app, dir)
            onLine("Parity frames: comparing")
            val parity = compareVisualPair(pair, reportDir(app))
            val where = writeReport(app, report(parity, complete = true))
            if (where == null) notes += "The report files could not be written; the summary below is all there is."
            return Outcome(DiagnosticsReport.summary(report(parity, complete = true)), where)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { dir.deleteRecursively() } }
        }
    }

    // ------------------------------------------------------------------------------------------------ the GLES smoke test

    /**
     * The "GLES smoke test (3 s)": the visual fixture exported on GLES only, then checked for a readable file of about
     * the right length with a decodable frame. It proves the encoder path runs; it says nothing about quality.
     */
    suspend fun runGlesSmoke(context: Context, onLine: (String) -> Unit): Outcome {
        val app = context.applicationContext
        val dir = freshWorkDir(app)
        try {
            onLine("Exporting the visual fixture on GLES")
            val script = decode(DiagnosticsFixtures.visualScriptJson())
            val exported = exportFile(app, DiagnosticsFixtures.visualProject(script, "GLES"), compile(script), Backend.GLES, dir)
            val path = exported.path
                ?: return Outcome("GLES smoke test FAILED after ${exported.elapsedMs} ms: ${exported.error}", null)
            val (seconds, frameOk) = withContext(Dispatchers.Default) {
                DiagnosticsMedia.containerDurationSec(path) to (DiagnosticsMedia.extractFrames(path, listOf(1.0)).firstOrNull() != null)
            }
            val expected = DiagnosticsFixtures.VISUAL_SCRIPT_END_SEC + 1.0
            val lengthOk = kotlin.math.abs(seconds - expected) <= GlesAcceptance.MAX_DURATION_DELTA_SEC
            val kb = File(path).length() / 1024
            val ok = lengthOk && frameOk
            return Outcome(
                "GLES smoke test ${if (ok) "OK" else "FAILED"}: ${"%.2f".format(Locale.ROOT, seconds)} s " +
                    "(expected ${"%.1f".format(Locale.ROOT, expected)} s), $kb KB, a frame ${if (frameOk) "decoded" else "did NOT decode"}, " +
                    "${exported.elapsedMs} ms." + exported.warnings.joinToString("") { "\n! $it" },
                null
            )
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { dir.deleteRecursively() } }
        }
    }
}
