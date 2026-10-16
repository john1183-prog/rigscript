package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.example.data.AmplitudeSettings
import com.example.data.ExportSettings
import com.example.data.ProjectDef
import com.example.data.ReferenceOverlay
import com.example.engine.VideoExporter.OutputTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The GLES export backend (WP1.3): the same project, timeline, audio and output as [VideoExporter.export], drawn by
 * [GlesFrameRenderer] into the encoder's input surface instead of through a software Canvas.
 *
 * What it shares with the Canvas path (so the two cannot drift): the timeline resolution ([PlaybackEngine], once per
 * frame whatever the number of targets), the length (`ExportAudioPlan.totalSec`), the sizes (`ExportSettings.dimensions`),
 * the audio (`ExportAudio`: narration passthrough, a mix, or nothing, with warnings), the output files (`OutputTarget`),
 * progress and ETA, and cancellation. Differences that are on purpose: the encoder takes a surface (no NV12 conversion),
 * the colour standard is tagged BT.601 limited range so a hardware conversion matches the software one, and the bitrate is
 * clamped into the encoder's reported range (and the person is told).
 *
 * Dual aspect: ONE EGL context with one window surface per encoder input surface (see [GlesFrameRenderer]); each frame
 * resolves the timeline once, then builds and draws one [GlesFigureFrame] per target.
 *
 * Failures that belong to GLES are thrown as [GlesExportException] carrying the stage they happened at, so
 * [ExportRouter] can apply [ExportBackendPlan.onGlesFailure]. Cancellation is never wrapped. Failures that are not
 * GLES's (creating the output file, reading the project) propagate unchanged: Canvas would fail the same way.
 *
 * NOT device-tested: compiled by CI only. Every step that talks to MediaCodec or EGL is bounded (idle watchdog on the end
 * of stream wait, cancellation checks) and released in a `finally` that also runs after cancellation.
 */
object GlesExporter {

    private const val TAG = "GlesExporter"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** One poll of the encoder's output while waiting for the end of the stream. */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 3000 empty polls of 10 ms: 30 seconds without a single output buffer (the same limit as the Canvas path). */
    private const val MAX_IDLE_POLLS = 3000

    /** A failure of the GLES backend itself, and when in the export it happened. */
    class GlesExportException(
        val stage: ExportBackendPlan.Stage,
        val reason: String,
        cause: Throwable? = null
    ) : Exception("GLES export failed at $stage: $reason", cause)

    /** Everything needed to open one encoder for one aspect, decided before anything is created. */
    private class TargetPlan(
        val aspect: String,
        val width: Int,
        val height: Int,
        val codecName: String,
        val format: MediaFormat,
        val notes: List<String>
    )

    /** All per-output state of one aspect, mirroring the Canvas path's `ExportTarget`. */
    private class Target(
        val aspect: String,
        val width: Int,
        val height: Int,
        val encoder: MediaCodec,
        val inputSurface: Surface
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        /** The figure is sized from the shorter side in portrait and from a 9:16 box in landscape (as in RigRenderer). */
        val figureScaleDim: Float = if (width < height) width.toFloat() else height * (9f / 16f)
        var output: OutputTarget? = null
        var muxer: MediaMuxer? = null
        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var muxerStarted = false
        var encoderReleased = false
        var muxerReleased = false
    }

    /**
     * Exports [project] through GLES and returns one [ExportResult] per aspect.
     *
     * [limitSec] renders only that many seconds (the debug test); `null` renders the full length like Canvas.
     * [singleAspect] ignores `dualAspectExport` (the debug test). [nameSuffix] is appended to the file name and
     * [aspectLabel], when given, replaces the aspect text in the result.
     */
    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        amplitudeSettings: AmplitudeSettings = AmplitudeSettings(),
        onProgress: (progress: Float, etaSec: Float?) -> Unit,
        limitSec: Float? = null,
        singleAspect: Boolean = false,
        nameSuffix: String = "",
        aspectLabel: String? = null
    ): List<ExportResult> = withContext(Dispatchers.Default) {

        val settings = project.exportSettings
        val fps = settings.fps.coerceAtLeast(1)
        val appearance = project.appearance
        val aspects: List<String> =
            if (settings.dualAspectExport && !singleAspect) listOf("9:16", "16:9") else listOf(settings.aspectRatio)

        // Same length rule as the Canvas path: the longer of the script and the audio, plus a one second tail.
        val lastKf = keyframes.lastOrNull()
        val scriptEnd = if (lastKf != null) lastKf.timeSec + lastKf.duration else 0f
        val totalSec = limitSec ?: ExportAudioPlan.totalSec(scriptEnd, project.audioDurationSec)
        val totalFrames = (totalSec * fps).toInt().coerceAtLeast(1)

        @Suppress("DEPRECATION")
        val envelope: FloatArray = EnvelopeStore.readAmplitudeWithFallback(
            project.amplitudeEnvelopePath, project.amplitudeEnvelope)
        @Suppress("DEPRECATION")
        val mouthEnvelope: IntArray = EnvelopeStore.readMouthShapesWithFallback(
            project.mouthShapeEnvelopePath, project.mouthShapeEnvelope)
        val envFps = AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS

        val engine = PlaybackEngine().also {
            it.loadTimeline(keyframes)
            it.amplitudeSettings = amplitudeSettings
            it.loadBlinkSchedule(project.script.blinkEvents, totalSec)
            it.loadFidgetSchedule(envelope, envFps)
            it.loadCaptions(TimelineCompiler.extractCaptions(project.script))
            it.loadOverlayLayers(TimelineCompiler.extractOverlayLayers(project.script))
        }

        // Decoded once, shared by every target: I/O never happens inside the per-frame loop.
        val overlay = project.referenceOverlay
        val overlayBitmap: Bitmap? =
            if (overlay.type == ReferenceOverlay.OverlayType.IMAGE && overlay.imagePath != null)
                runCatching { BitmapFactory.decodeFile(overlay.imagePath) }.getOrNull()
            else null

        val targets = ArrayList<Target>()
        var renderer: GlesFrameRenderer? = null
        var success = false
        try {
            // ── PROBE: can this device encode these sizes from a surface at all? Nothing is created yet. ──────
            val plans: List<TargetPlan> = guard(ExportBackendPlan.Stage.PROBE) { aspects.map { planTarget(settings, it, fps) } }

            // ── INIT: encoders with input surfaces, then ONE EGL context with a surface per encoder. ──────────
            guard(ExportBackendPlan.Stage.INIT) {
                for (p in plans) {
                    val encoder = MediaCodec.createByCodecName(p.codecName)
                    val surface: Surface = try {
                        encoder.configure(p.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        val created = encoder.createInputSurface()
                        try {
                            encoder.start()
                        } catch (e: Throwable) {
                            runCatching { created.release() }
                            throw e
                        }
                        created
                    } catch (e: Throwable) {
                        runCatching { encoder.stop() }
                        runCatching { encoder.release() }
                        throw e
                    }
                    targets += Target(p.aspect, p.width, p.height, encoder, surface)
                }
                val r = GlesFrameRenderer(targets.map { it.inputSurface })
                renderer = r          // assigned before init(), so a failing init is still released below
                r.init()
            }
            val glRenderer = renderer!!

            // Audio is prepared once and written into every target (the Canvas path's single-pass rule). It is not
            // GLES's business: a problem here is reported by ExportAudio as warnings, not as a GLES failure.
            val audio = ExportAudio.prepare(
                embedAudio    = settings.embedAudio,
                narrationPath = project.audioFilePath,
                music         = project.backgroundMusic,
                cues          = TimelineCompiler.extractSoundEffectCues(project.script),
                library       = project.soundEffects,
                totalSec      = totalSec,
                stillActive   = { isActive }
            )

            // Output files and muxers. Not a GLES failure either (a full disk fails Canvas the same way).
            for (t in targets) {
                val labelSuffix = if (aspects.size > 1) "_${t.aspect.replace(":", "x")}" else ""
                val out = OutputTarget.create(context, project.projectName + labelSuffix + nameSuffix)
                t.output = out
                t.muxer = out.openMuxer()
            }

            val job: Job? = currentCoroutineContext()[Job]
            val audioWriteWarnings = ArrayList<String>()

            // ── FRAMES ──────────────────────────────────────────────────────────────────────────────────────────
            val matrices = Array(StickFigureRig.BONE_COUNT) { android.graphics.Matrix() }
            val showJoints = appearance.showJointsOnExport
            val exportStartMs = System.currentTimeMillis()

            // The whole loop runs inside ONE withContext(dispatcher): every EGL/GL call must run on the thread the
            // context is current on (see GlesFrameRenderer.drawFigureFrame). Draining the encoder does not touch GL,
            // so it can run there too.
            withContext(glRenderer.dispatcher) {
                for (frameIdx in 0 until totalFrames) {
                    // Cancellation is cooperative: without a check in this tight loop the cancel button would only
                    // take effect when the loop ends.
                    if (frameIdx % 10 == 0) currentCoroutineContext().ensureActive()
                    try {
                        val timeSec = frameIdx.toFloat() / fps
                        val envIdx = if (envelope.isNotEmpty())
                            (timeSec * envFps).toInt().coerceIn(0, envelope.size - 1) else -1
                        val rawAmp = if (envIdx >= 0) envelope[envIdx] else 0f
                        val mouth = if (envIdx >= 0 && mouthEnvelope.isNotEmpty())
                            mouthEnvelope[envIdx.coerceAtMost(mouthEnvelope.size - 1)] else MouthShape.CLOSED

                        // The expensive timeline resolution happens ONCE per frame, not once per target.
                        engine.seekToWithAmplitude(timeSec, rawAmp, mouth)
                        val overrides = engine.currentFigureOverrides
                        // Time-resolved overlays, read once per frame: `currentOverlays` re-walks every layer on each
                        // read, and parenting (which depends on the canvas size) happens per target inside
                        // fromFkMatrices, so one read serves both aspects.
                        val timeResolvedOverlays = engine.currentOverlays
                        val presentationTimeNs = frameIdx.toLong() * 1_000_000_000L / fps

                        for ((targetIndex, t) in targets.withIndex()) {
                            val scale = t.figureScaleDim * (overrides.scale ?: appearance.characterScale)
                            val rootX = t.width * (overrides.x ?: appearance.rootAnchorX)
                            val rootY = t.height * (overrides.y ?: appearance.rootAnchorY) + engine.currentHipBobOffset * scale

                            RigRenderer.computeFkMatrices(engine.currentAngles, rootX, rootY, scale, matrices)
                            val frame = GlesFigureFrame.fromFkMatrices(
                                matrices, appearance, overrides, t.width, t.height, scale, showJoints,
                                mouthShape             = engine.currentMouthShape,
                                mouthOpenness          = engine.currentAmplitude,
                                eyeOpenness            = engine.currentEyeOpenness,
                                expression             = engine.currentExpression,
                                skyColor               = engine.currentSkyColor,
                                groundColor            = engine.currentGroundColor,
                                horizonY               = engine.currentHorizonY,
                                sceneShape             = engine.currentSceneShape,
                                sceneAtmosphere        = engine.currentSceneAtmosphere,
                                timeSec                = timeSec,
                                cameraZoom             = engine.currentCameraZoom,
                                cameraPanX             = engine.currentCameraPanX,
                                cameraPanY             = engine.currentCameraPanY,
                                cameraShakeIntensity   = engine.currentShakeIntensity,
                                overlays               = timeResolvedOverlays,
                                captionText            = engine.currentCaption,
                                referenceOverlay       = overlay,
                                referenceOverlayBitmap = overlayBitmap
                            )
                            glRenderer.drawFigureFrame(frame, presentationTimeNs, targetIndex)
                            drain(t, audio, untilEos = false, job = job)
                        }
                        onProgress(frameIdx.toFloat() / totalFrames * 0.90f, VideoExporter.computeEtaSec(frameIdx + 1, totalFrames, exportStartMs))
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        throw GlesExportException(ExportBackendPlan.stageForFrame(frameIdx, fps), ExportAudioPlan.reasonOf(e), e)
                    }
                }
            }

            // ── FINISH: end of stream, audio, release, publish the files. ───────────────────────────────────────
            guard(ExportBackendPlan.Stage.LATER) {
                for (t in targets) t.encoder.signalEndOfInputStream()
                for (t in targets) {
                    drain(t, audio, untilEos = true, job = job)
                    check(t.muxerStarted) { "The encoder produced no video" }
                    if (t.audioTrackIdx >= 0) {
                        audioWriteWarnings += audio.write(t.muxer!!, t.audioTrackIdx, (totalSec * 1_000_000L).toLong())
                    }
                }
                onProgress(1f, 0f)

                for (t in targets) {
                    t.encoder.stop(); t.encoder.release(); t.encoderReleased = true
                    t.muxer!!.stop(); t.muxer!!.release(); t.muxerReleased = true
                    t.output!!.finish(context)
                }
            }
            success = true

            Log.i(TAG, "GLES export: $totalFrames frames x ${targets.size} target(s) -> " +
                targets.joinToString { it.output!!.location })

            val warnings = (plans.flatMap { it.notes } + audio.warnings + audioWriteWarnings).distinct()
            targets.map { t ->
                ExportResult(t.output!!.uri, t.output!!.location, aspectLabel ?: t.aspect, warnings)
            }
        } finally {
            // After a cancellation withContext() refuses to start, which would skip the GL teardown and leak the
            // render thread; NonCancellable lets the clean-up run to the end.
            withContext(NonCancellable) {
                renderer?.let { r -> runCatching { r.release() } }
                for (t in targets) {
                    if (!t.encoderReleased) {
                        runCatching { t.encoder.stop() }
                        runCatching { t.encoder.release() }
                    }
                    runCatching { t.inputSurface.release() }
                    val m = t.muxer
                    if (m != null && !t.muxerReleased) {
                        if (t.muxerStarted) runCatching { m.stop() }
                        runCatching { m.release() }
                    }
                    if (!success) t.output?.let { o -> runCatching { o.abort(context) } }
                }
            }
        }
    }

    /**
     * Runs only the PROBE stage for [settings]'s sizes and fps: `null` when an H.264 encoder that takes a surface exists
     * for them, else the reason (one line). Creates nothing. This is what "Re-run GLES check" runs until WP1.6 adds
     * the full on-device check.
     */
    fun probe(settings: ExportSettings): String? = try {
        val fps = settings.fps.coerceAtLeast(1)
        val aspects = if (settings.dualAspectExport) listOf("9:16", "16:9") else listOf(settings.aspectRatio)
        for (aspect in aspects) planTarget(settings, aspect, fps)
        null
    } catch (e: GlesExportException) {
        e.reason
    } catch (e: Throwable) {
        ExportAudioPlan.reasonOf(e)
    }

    /** Runs [block]; anything it throws (except cancellation) becomes a [GlesExportException] for [stage]. */
    private inline fun <T> guard(stage: ExportBackendPlan.Stage, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e is CancellationException || e is GlesExportException) throw e
        throw GlesExportException(stage, ExportAudioPlan.reasonOf(e), e)
    }

    /**
     * Decides sizes, bitrate and encoder for one aspect from what the device reports. Throws [GlesExportException]
     * (stage PROBE) when GLES export is impossible here.
     */
    private fun planTarget(settings: ExportSettings, aspect: String, fps: Int): TargetPlan {
        val (requestedW, requestedH) = settings.dimensions(aspect)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        fun format(w: Int, h: Int, bitrateBps: Int): MediaFormat = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Same 5 s keyframe interval as the Canvas path.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            // Explicit, not left to the driver: BT.601 limited range matches the coefficients the software path's
            // NV12 conversion uses, so a GLES export is not shifted in tint or contrast.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

        val requestedBps = EncoderPlan.bitrate(settings.bitrateMbps, null, null).bps
        val codecName = codecList.findEncoderForFormat(format(requestedW, requestedH, requestedBps))
        ExportBackendPlan.probeProblem(hasAvcSurfaceEncoder = codecName != null, hasRecordableEglConfig = null)
            ?.let { throw GlesExportException(ExportBackendPlan.Stage.PROBE, it) }

        val caps = runCatching {
            codecList.codecInfos.first { it.name == codecName }.getCapabilitiesForType(MIME)
        }.getOrNull()
        val video = caps?.videoCapabilities

        val aligned = EncoderPlan.alignedSize(requestedW, requestedH, video?.widthAlignment ?: 1, video?.heightAlignment ?: 1)
        val w = aligned.size.width
        val h = aligned.size.height
        EncoderPlan.sizeProblem(w, h, runCatching { video?.isSizeSupported(w, h) }.getOrNull())
            ?.let { throw GlesExportException(ExportBackendPlan.Stage.PROBE, it) }

        val range = runCatching { video?.bitrateRange }.getOrNull()
        val bitrate = EncoderPlan.bitrate(settings.bitrateMbps, range?.lower, range?.upper)
        val mediaFormat = format(w, h, bitrate.bps)

        // VBR when the encoder supports it, exactly like the Canvas path.
        val supportsVbr = runCatching {
            caps?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }.getOrNull() ?: false
        if (supportsVbr) {
            mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        val notes = listOfNotNull(
            EncoderPlan.sizeNote(EncoderPlan.Size(requestedW, requestedH), aligned.size),
            EncoderPlan.bitrateNote(bitrate)
        )
        return TargetPlan(aspect, w, h, codecName!!, mediaFormat, notes)
    }

    /** Adds both tracks and starts the muxer once the encoder has reported its output format. */
    private fun startMuxerIfNeeded(t: Target, audio: ExportAudio.PreparedAudio) {
        if (t.muxerStarted) return
        val m = t.muxer!!
        t.videoTrackIdx = m.addTrack(t.encoder.outputFormat)
        t.audioTrackIdx = audio.addTrack(m)
        m.start()
        t.muxerStarted = true
    }

    /**
     * Moves encoded video from [t]'s encoder into its muxer. With [untilEos] false it takes what is ready and returns
     * (called once per frame per target); with true it waits for the end of the stream, giving up after
     * [MAX_IDLE_POLLS] polls without any output and honouring cancellation on every poll.
     */
    private fun drain(t: Target, audio: ExportAudio.PreparedAudio, untilEos: Boolean, job: Job?) {
        var idle = 0
        while (true) {
            val outIdx = t.encoder.dequeueOutputBuffer(t.bufferInfo, if (untilEos) DEQUEUE_TIMEOUT_US else 0L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    job?.ensureActive()
                    check(++idle < MAX_IDLE_POLLS) { "Encoder did not signal end of stream within 30 seconds" }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idle = 0
                    startMuxerIfNeeded(t, audio)
                }
                outIdx >= 0 -> {
                    idle = 0
                    val isConfig = t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && t.muxerStarted && t.bufferInfo.size > 0) {
                        t.muxer!!.writeSampleData(t.videoTrackIdx, t.encoder.getOutputBuffer(outIdx)!!, t.bufferInfo)
                    }
                    t.encoder.releaseOutputBuffer(outIdx, false)
                    if (t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // Any other negative value (a deprecated "buffers changed" notice) needs no action.
            }
        }
    }
}
