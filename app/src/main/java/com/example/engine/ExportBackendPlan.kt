package com.example.engine

import kotlin.math.ceil

/** Which video backend draws and encodes an export. */
enum class Backend { GLES, CANVAS }

/** The values of `ExportSettings.renderer`. An unknown value (hand-edited, from a newer app) behaves as AUTO (I4). */
object RendererSetting {
    const val AUTO = "AUTO"
    const val GLES = "GLES"
    const val CANVAS = "CANVAS"
    val ALL: List<String> = listOf(AUTO, GLES, CANVAS)

    fun normalize(value: String?): String {
        val v = value?.trim()?.uppercase()
        return if (v != null && v in ALL) v else AUTO
    }
}

/** The backend an export will use, and one plain sentence saying why (shown under the Renderer selector). */
data class RendererChoice(val backend: Backend, val setting: String, val explanation: String)

/**
 * Pure decisions around the GLES export backend (WP1.3): how `AUTO` resolves, what happens when GLES fails, and the
 * encoder parameter rules. No Android types, so all of it is unit-tested on the JVM; [GlesExporter] and
 * [ExportRouter] are the Android glue that follows these decisions.
 */
object ExportBackendPlan {

    /**
     * `AUTO` uses GLES only after the on-device check passed ([GlesState.Eligible]); with an unknown or failed
     * verdict it uses Canvas. `GLES` and `CANVAS` force the backend whatever the verdict.
     */
    fun resolve(setting: String?, state: GlesState): RendererChoice {
        val s = RendererSetting.normalize(setting)
        return when (s) {
            RendererSetting.CANVAS -> RendererChoice(Backend.CANVAS, s, "Canvas is selected.")
            RendererSetting.GLES -> RendererChoice(Backend.GLES, s, "GLES is selected." + when (state) {
                is GlesState.Eligible -> ""
                is GlesState.Ineligible -> " The GLES check failed on this device (${state.reason})."
                is GlesState.Unknown -> " GLES has not been checked on this device yet."
            })
            else -> when (state) {
                is GlesState.Eligible ->
                    RendererChoice(Backend.GLES, s, "Auto is using GLES: the GLES check passed on this device.")
                is GlesState.Ineligible ->
                    RendererChoice(Backend.CANVAS, s, "Auto is using Canvas: the GLES check failed (${state.reason}).")
                is GlesState.Unknown ->
                    RendererChoice(Backend.CANVAS, s, "Auto is using Canvas: GLES has not been checked on this device yet.")
            }
        }
    }

    // ── What to do when GLES fails ─────────────────────────────────────────────────────────────────────────────

    /** When in an export the failure happened. */
    enum class Stage {
        /** The capability probe (encoder and EGL config) before anything is created. */
        PROBE,

        /** Creating the encoder, the EGL context and surfaces, the shaders. */
        INIT,

        /** The first [FIRST_FRAMES_WINDOW_SEC] seconds of frames. */
        FIRST_FRAMES,

        /** Any later frame, the audio and the finish. */
        LATER
    }

    /** Frames drawn in the first this-many seconds count as "the first frames". */
    const val FIRST_FRAMES_WINDOW_SEC = 2f

    fun firstFramesWindow(fps: Int): Int = ceil(FIRST_FRAMES_WINDOW_SEC * fps.coerceAtLeast(1)).toInt()

    fun stageForFrame(frameIdx: Int, fps: Int): Stage =
        if (frameIdx < firstFramesWindow(fps)) Stage.FIRST_FRAMES else Stage.LATER

    data class FailureDecision(
        /** Run the export again on Canvas. */
        val runCanvasInstead: Boolean,
        /** Store `Ineligible(reason)` for this device, so `AUTO` stops choosing GLES. */
        val recordIneligible: Boolean,
        /** Show "Retry with Canvas" (an explicit GLES choice is never silently replaced). */
        val offerRetryWithCanvas: Boolean,
        /** The visible message. Empty when no GLES failure applies. */
        val message: String
    )

    /**
     * What to do when GLES failed at [stage] for [reason] (a one-line reason, see [ExportAudioPlan.reasonOf]).
     * Cancellation never comes here: the caller lets it propagate.
     *
     *  - `AUTO`: clean up and run Canvas instead, with a visible message. Failures before or in the first frames are
     *    GLES's fault, so the verdict becomes `Ineligible(reason)`. A failure later on (minutes in) is ambiguous (a full
     *    disk, a thermal shutdown), so Canvas still runs but the verdict is left alone.
     *  - `GLES` (forced): the error is reported and "Retry with Canvas" is offered; nothing is replaced silently.
     *    The same early-failure rule decides whether the verdict is stored.
     *  - `CANVAS`: GLES is not involved, so nothing applies.
     */
    fun onGlesFailure(setting: String?, stage: Stage, reason: String): FailureDecision {
        val early = stage != Stage.LATER
        return when (RendererSetting.normalize(setting)) {
            RendererSetting.CANVAS -> FailureDecision(false, false, false, "")
            RendererSetting.GLES -> FailureDecision(
                runCanvasInstead = false,
                recordIneligible = early,
                offerRetryWithCanvas = true,
                message = "GLES export failed: $reason"
            )
            else -> FailureDecision(
                runCanvasInstead = true,
                recordIneligible = early,
                offerRetryWithCanvas = false,
                message = if (early) "GLES failed: $reason; exported with Canvas."
                else "GLES failed part-way: $reason; exported with Canvas instead."
            )
        }
    }

    /** A reason when the probe found GLES export impossible here; null when it can proceed (or the probe could not tell). */
    fun probeProblem(hasAvcSurfaceEncoder: Boolean?, hasRecordableEglConfig: Boolean?): String? = when {
        hasAvcSurfaceEncoder == false -> "no H.264 encoder with surface input"
        hasRecordableEglConfig == false -> "no recordable EGL configuration"
        else -> null
    }
}

/** Pure rules for configuring the video encoder of the GLES backend (size alignment, bitrate). */
object EncoderPlan {

    data class Size(val width: Int, val height: Int)

    data class AlignedSize(val size: Size, val changed: Boolean)

    /**
     * Rounds [width] x [height] up to the encoder's alignment (alignments below 1 count as 1). The sizes from
     * `ExportSettings.dimensions` are already multiples of 16, so this is the identity for every usual encoder; it
     * only matters for an encoder that asks for 32 or 64.
     */
    fun alignedSize(width: Int, height: Int, widthAlignment: Int, heightAlignment: Int): AlignedSize {
        fun up(v: Int, a: Int): Int {
            val al = if (a < 1) 1 else a
            val x = if (v < 1) 1 else v
            return ((x + al - 1) / al) * al
        }
        val out = Size(up(width, widthAlignment), up(height, heightAlignment))
        return AlignedSize(out, changed = out.width != width || out.height != height)
    }

    fun sizeNote(requested: Size, actual: Size): String? =
        if (requested == actual) null
        else "The video encoder needs larger sizes, so the GLES video is ${actual.width}x${actual.height} instead of " +
            "${requested.width}x${requested.height}."

    /** A reason when the encoder cannot take this size at all; null when it can, or did not say. */
    fun sizeProblem(width: Int, height: Int, supported: Boolean?): String? =
        if (supported == false) "the video encoder does not support ${width}x$height" else null

    /** Highest bitrate accepted from the settings, in Mbit/s (keeps `bps` inside an Int). */
    const val MAX_MBPS = 200

    data class Bitrate(val bps: Int, val requestedBps: Int) {
        val clamped: Boolean get() = bps != requestedBps
    }

    /**
     * Bits per second to ask the encoder for: the setting in Mbit/s, kept inside the encoder's reported range
     * ([lowerBps]..[upperBps], either may be unknown). Canvas passes the setting through unchanged; the GLES path
     * clamps so a device with a narrow range does not fail at `configure`, and says so.
     */
    fun bitrate(requestedMbps: Int, lowerBps: Int?, upperBps: Int?): Bitrate {
        val requested = requestedMbps.coerceIn(1, MAX_MBPS) * 1_000_000
        var bps = requested
        if (lowerBps != null && lowerBps > 0 && bps < lowerBps) bps = lowerBps
        if (upperBps != null && upperBps > 0 && bps > upperBps) bps = upperBps
        return Bitrate(bps, requested)
    }

    fun bitrateNote(b: Bitrate): String? = when {
        !b.clamped -> null
        b.bps > b.requestedBps ->
            "The video encoder needs at least ${mbps(b.bps)} Mbit/s, so the bitrate was raised to ${mbps(b.bps)} " +
                "Mbit/s instead of ${mbps(b.requestedBps)}."
        else ->
            "The video encoder accepts at most ${mbps(b.bps)} Mbit/s, so the bitrate was lowered to ${mbps(b.bps)} " +
                "Mbit/s instead of ${mbps(b.requestedBps)}."
    }

    /** One decimal place, no locale involved. */
    private fun mbps(bps: Int): String {
        val tenths = (bps + 50_000) / 100_000
        return "${tenths / 10}.${tenths % 10}"
    }
}
