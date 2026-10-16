package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.AmplitudeSettings
import com.example.data.ProjectDef
import kotlinx.coroutines.CancellationException

/**
 * Chooses the export backend for one export and applies the fallback rules (WP1.3). The decisions themselves are the
 * pure, tested ones in [ExportBackendPlan]; this object only runs them:
 *
 *  - the renderer setting and the stored GLES verdict pick Canvas or GLES ([ExportBackendPlan.resolve]);
 *  - a GLES failure is classified by stage and answered as [ExportBackendPlan.onGlesFailure] says: `AUTO` runs Canvas
 *    instead (the result carries a visible warning) and may store `INELIGIBLE(reason)`; a forced `GLES` throws
 *    [GlesFailure] so the caller can show the error and offer "Retry with Canvas".
 *
 * Cancellation always propagates; nothing here swallows it.
 */
object ExportRouter {

    private const val TAG = "ExportRouter"

    /** What an export produced and which backend actually produced it. */
    class Outcome(val results: List<ExportResult>, val backend: Backend)

    /** A forced-GLES export failed; [decision] says what to show and whether to offer a Canvas retry. */
    class GlesFailure(val decision: ExportBackendPlan.FailureDecision, cause: Throwable?) :
        Exception(decision.message, cause)

    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        amplitudeSettings: AmplitudeSettings,
        store: GlesEligibilityStore,
        onProgress: (progress: Float, etaSec: Float?) -> Unit
    ): Outcome {
        val setting = project.exportSettings.renderer
        val choice = ExportBackendPlan.resolve(setting, store.state())
        Log.i(TAG, "Export renderer: ${choice.backend} (${choice.explanation})")

        if (choice.backend == Backend.CANVAS) {
            return Outcome(
                VideoExporter.export(context, project, keyframes, amplitudeSettings, onProgress),
                Backend.CANVAS
            )
        }

        try {
            return Outcome(
                GlesExporter.export(context, project, keyframes, amplitudeSettings, onProgress),
                Backend.GLES
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GlesExporter.GlesExportException) {
            val decision = ExportBackendPlan.onGlesFailure(setting, e.stage, e.reason)
            Log.w(TAG, "GLES export failed at ${e.stage}: ${e.reason}", e)
            if (decision.recordIneligible) store.record(GlesState.Ineligible(e.reason))
            if (!decision.runCanvasInstead) throw GlesFailure(decision, e)

            // AUTO: the person still gets a video, and is told why it took the Canvas path.
            onProgress(0f, null)
            val results = VideoExporter.export(context, project, keyframes, amplitudeSettings, onProgress)
            return Outcome(
                results.map { it.copy(warnings = (listOf(decision.message) + it.warnings).distinct()) },
                Backend.CANVAS
            )
        }
    }
}
