package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.ProjectDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Result of one completed export output file.
 *
 * [uri]         A `content://` URI suitable for `ACTION_VIEW` / `ACTION_SEND` — works
 *               for both the MediaStore path (API 29+) and the FileProvider-wrapped
 *               legacy path (API 26-28).
 * [location]    Human-readable location, for display in the UI.
 * [aspectLabel] Which aspect ratio this file is ("9:16" or "16:9") — [export]
 *               always returns a list (one entry normally, two when
 *               [com.example.data.ExportSettings.dualAspectExport] is set),
 *               and the UI needs to label which file is which.
 */
data class ExportResult(val uri: Uri, val location: String, val aspectLabel: String)

/**
 * Encodes the animation to an H.264/MP4 file (or, with
 * [com.example.data.ExportSettings.dualAspectExport], two files at once —
 * one per aspect ratio) and optionally muxes the source audio track
 * verbatim (no re-encoding, lossless, fast) or a mixed background-music/
 * sound-effect track (see [AudioMixer]).
 *
 * Each frame is drawn onto an off-screen [Bitmap], converted to NV12
 * (YUV420SemiPlanar) in software, then queued to [MediaCodec] as a byte-buffer
 * input. This works on all API 26+ devices without EGL.
 *
 * Dual-aspect export is a genuine single-pass optimization, not two
 * sequential exports: [PlaybackEngine.seekToWithAmplitude] — the actual
 * timeline/pose/expression/camera/scene resolution — runs exactly once per
 * frame regardless of target count, and audio (verbatim copy or the mixed
 * track) is computed once and written into both outputs. Only the
 * per-target draw+YUV-convert+encode genuinely repeats, since two different
 * pixel grids are unavoidably two different encode jobs — see
 * [ExportTarget] and the loop in [export].
 *
 * Output location: API 29+ writes to the public `Movies/RigScript/` collection
 * via [MediaStore] (shows up in Gallery/Files immediately, no permission needed).
 * API 26-28 writes to the legacy public Movies directory and requires
 * `WRITE_EXTERNAL_STORAGE` to be granted by the caller first — see
 * `EditorScreen`'s permission-request flow.
 */
object VideoExporter {

    private const val TAG  = "VideoExporter"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT = 10_000L
    /** Frames measured before reporting an ETA — throughput from the first
     *  couple of frames (JIT warm-up, first-allocation overhead) tends to
     *  undershoot steady-state, so a too-early estimate would read as overly
     *  pessimistic and then visibly jump. */
    private const val MIN_FRAMES_FOR_ETA = 10

    /**
     * All per-output-file mutable state for one aspect ratio's encode job.
     * [export] creates one of these per target (one normally, two for dual
     * export) and drives them together from a single shared frame loop.
     */
    private class ExportTarget(
        val aspectLabel: String,
        val width: Int,
        val height: Int,
        val output: OutputTarget,
        val encoder: MediaCodec,
        val muxer: MediaMuxer
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)
        val nv12   = ByteArray(width * height * 3 / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        var videoTrackIdx      = -1
        var audioTrackIdx      = -1
        var mixedAudioTrackIdx = -1
        var muxerStarted       = false
        var encoderReleased    = false
        var muxerReleased      = false
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        amplitudeSettings: com.example.data.AmplitudeSettings = com.example.data.AmplitudeSettings(),
        onProgress: (progress: Float, etaSec: Float?) -> Unit
    ): List<ExportResult> = withContext(Dispatchers.Default) {

        val settings   = project.exportSettings
        val fps        = settings.fps
        val bitrate    = settings.bitrateMbps * 1_000_000
        val appearance = project.appearance

        val aspectsToExport: List<String> =
            if (settings.dualAspectExport) listOf("9:16", "16:9") else listOf(settings.aspectRatio)

        // Duration = max(script length, actual audio length) + 1s tail. If the
        // audio runs longer than the scripted pose changes, the figure holds its
        // final pose (with idle/talk motion still active) for the remainder
        // instead of the export being truncated.
        val lastKf    = keyframes.lastOrNull()
        val scriptEnd = if (lastKf != null) lastKf.timeSec + lastKf.duration else 0f
        val totalSec  = maxOf(scriptEnd, project.audioDurationSec) + 1f
        val totalFrames = (totalSec * fps).toInt().coerceAtLeast(1)

        // Pre-baked amplitude envelope, indexed at AMPLITUDE_ANALYSIS_FPS (always 30fps,
        // independent of the export FPS so the envelope lookup is always correct).
        // V2: read from the binary EnvelopeStore files first; fall back to the
        // deprecated inline JSON lists for a project saved before the migration
        // to disk-backed envelopes (see ProjectDef's doc comment on this pair).
        @Suppress("DEPRECATION")
        val envelope: FloatArray = EnvelopeStore.readAmplitudeWithFallback(
            project.amplitudeEnvelopePath, project.amplitudeEnvelope)
        @Suppress("DEPRECATION")
        val mouthEnvelope: IntArray = EnvelopeStore.readMouthShapesWithFallback(
            project.mouthShapeEnvelopePath, project.mouthShapeEnvelope)
        val envFps        = AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS

        val engine = PlaybackEngine().also {
            it.loadTimeline(keyframes)
            it.amplitudeSettings = amplitudeSettings
            it.loadBlinkSchedule(project.script.blinkEvents, totalSec)
            it.loadFidgetSchedule(envelope, envFps)
            it.loadCaptions(TimelineCompiler.extractCaptions(project.script))
            it.loadOverlayLayers(TimelineCompiler.extractOverlayLayers(project.script))
        }
        // One shared renderer instance across all targets — safe because targets
        // are drawn sequentially within a single thread here, not concurrently.
        // (The "never a shared singleton" rule on RigRenderer is specifically
        // about preview and export running on DIFFERENT threads at the same
        // time; that doesn't apply between targets within one export call.)
        val renderer = RigRenderer()

        // V2 — reference overlay image decoded ONCE up front, shared by every
        // target — I/O + allocation must never happen inside the per-frame loop.
        val overlay = project.referenceOverlay
        val overlayBitmap: Bitmap? =
            if (overlay.type == com.example.data.ReferenceOverlay.OverlayType.IMAGE && overlay.imagePath != null)
                runCatching { BitmapFactory.decodeFile(overlay.imagePath) }.getOrNull()
            else null

        // V2 — background music + sound effects. Computed ONCE and reused for
        // every target below — see the class doc comment's "single pass"
        // explanation. Only actually decodes/mixes/re-encodes when either is
        // configured; every other export keeps using the fast verbatim-copy
        // audio path, untouched.
        val music = project.backgroundMusic
        val soundEffectsById = project.soundEffects.associateBy { it.id }
        val soundEffectTriggers: List<AudioMixer.SoundEffectTrigger> =
            TimelineCompiler.extractSoundEffectCues(project.script).mapNotNull { cue ->
                val clip = soundEffectsById[cue.clipId] ?: return@mapNotNull null
                AudioMixer.SoundEffectTrigger(cue.timeSec, clip.filePath, clip.volume * cue.volumeMultiplier)
            }
        val useMixer = settings.embedAudio && (music.musicFilePath != null || soundEffectTriggers.isNotEmpty())
        val mixedTrack: AudioMixer.EncodedAudioTrack? =
            if (useMixer) {
                runCatching {
                    AudioMixer.buildMixedTrack(
                        narrationPath   = project.audioFilePath,
                        musicPath       = music.musicFilePath,
                        narrationVolume = music.narrationVolume,
                        musicVolume     = music.volume,
                        loopMusic       = music.loop,
                        totalSec        = totalSec,
                        soundEffects    = soundEffectTriggers
                    )
                }.onFailure { Log.e(TAG, "Background music mix failed, falling back to narration-only", it) }
                    .getOrNull()
            } else null

        // ── Build one ExportTarget per aspect ratio ───────────────────────────
        val targets = aspectsToExport.map { aspect ->
            val (w, h) = settings.dimensions(aspect)
            val videoFormat = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MIME)
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val labelSuffix = if (aspectsToExport.size > 1) "_${aspect.replace(":", "x")}" else ""
            val output = OutputTarget.create(context, project.projectName + labelSuffix)
            val muxer  = output.openMuxer()

            ExportTarget(aspect, w, h, output, encoder, muxer)
        }

        fun startMuxerIfNeeded(t: ExportTarget) {
            if (!t.muxerStarted) {
                t.videoTrackIdx = t.muxer.addTrack(t.encoder.outputFormat)
                if (mixedTrack != null) {
                    t.mixedAudioTrackIdx = t.muxer.addTrack(mixedTrack.format)
                } else if (settings.embedAudio && project.audioFilePath != null) {
                    t.audioTrackIdx = addAudioTrack(t.muxer, project.audioFilePath)
                }
                t.muxer.start(); t.muxerStarted = true
            }
        }

        /** Non-blocking drain — called once per rendered frame per target to keep each encoder's output queue from backing up. */
        fun drainAvailable(t: ExportTarget): Boolean {
            while (true) {
                val outIdx = t.encoder.dequeueOutputBuffer(t.bufferInfo, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxerIfNeeded(t)
                    outIdx >= 0 -> {
                        val isConfig = t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && t.muxerStarted && t.bufferInfo.size > 0) {
                            t.muxer.writeSampleData(t.videoTrackIdx, t.encoder.getOutputBuffer(outIdx)!!, t.bufferInfo)
                        }
                        t.encoder.releaseOutputBuffer(outIdx, false)
                        if (t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        /** Blocking variant used only for the final drain, where we must wait for EOS. */
        fun drainUntilEndOfStream(t: ExportTarget) {
            while (true) {
                val outIdx = t.encoder.dequeueOutputBuffer(t.bufferInfo, TIMEOUT)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxerIfNeeded(t)
                    outIdx >= 0 -> {
                        val isConfig = t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && t.muxerStarted && t.bufferInfo.size > 0) {
                            t.muxer.writeSampleData(t.videoTrackIdx, t.encoder.getOutputBuffer(outIdx)!!, t.bufferInfo)
                        }
                        t.encoder.releaseOutputBuffer(outIdx, false)
                        if (t.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /**
         * Queues one frame of NV12 data to [t]'s encoder. EOS must be signalled
         * via [MediaCodec.BUFFER_FLAG_END_OF_STREAM] on the last buffer —
         * signalEndOfInputStream() is only valid for Surface-input mode.
         */
        fun queueFrame(t: ExportTarget, data: ByteArray, presentationTimeUs: Long, isEos: Boolean) {
            var inIdx = -1; var attempts = 0
            while (inIdx < 0) {
                inIdx = t.encoder.dequeueInputBuffer(TIMEOUT)
                if (inIdx < 0) {
                    drainAvailable(t)
                    check(++attempts < 500) { "Encoder stalled: no input buffer became available" }
                }
            }
            t.encoder.getInputBuffer(inIdx)!!.let { it.clear(); it.put(data) }
            t.encoder.queueInputBuffer(inIdx, 0, data.size, presentationTimeUs,
                if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        }

        /** Writes the shared mixed/verbatim audio into [t]'s muxer — same source data, once per target, no re-decode/re-mix. */
        fun writeAudio(t: ExportTarget) {
            if (!t.muxerStarted) return
            if (t.mixedAudioTrackIdx >= 0 && mixedTrack != null) {
                val audioInfo = MediaCodec.BufferInfo()
                for (s in mixedTrack.samples) {
                    val buf = ByteBuffer.wrap(s.data)
                    audioInfo.offset = 0
                    audioInfo.size = s.data.size
                    audioInfo.presentationTimeUs = s.presentationTimeUs
                    audioInfo.flags = s.flags
                    t.muxer.writeSampleData(t.mixedAudioTrackIdx, buf, audioInfo)
                }
            } else if (t.audioTrackIdx >= 0 && project.audioFilePath != null) {
                copyAudioTrack(project.audioFilePath, t.muxer, t.audioTrackIdx, (totalSec * 1_000_000L).toLong())
            }
        }

        var overallSuccess = false
        try {
            // ── Render + encode loop — ONE pass shared across all targets ─────
            // ensureActive() every 10 frames is the key fix for real cancellation.
            // Coroutine cancellation is cooperative — without a suspension/check
            // point inside this tight CPU loop, cancelExport() sets the flag but
            // the loop keeps running to completion. ensureActive() throws
            // CancellationException as soon as the flag is seen, propagating to
            // the finally block which releases every target's encoder/muxer and
            // aborts every target's output.
            val exportStartMs = System.currentTimeMillis()
            for (frameIdx in 0 until totalFrames) {
                if (frameIdx % 10 == 0) currentCoroutineContext().ensureActive()

                val timeSec = frameIdx.toFloat() / fps
                val envIdx  = if (envelope.isNotEmpty())
                    (timeSec * envFps).toInt().coerceIn(0, envelope.size - 1) else -1
                val rawAmp  = if (envIdx >= 0) envelope[envIdx] else 0f
                val mouth   = if (envIdx >= 0 && mouthEnvelope.isNotEmpty())
                    mouthEnvelope[envIdx.coerceAtMost(mouthEnvelope.size - 1)] else MouthShape.CLOSED

                // Timeline resolution — the expensive per-frame animation
                // computation — happens exactly ONCE here, not once per target.
                engine.seekToWithAmplitude(timeSec, rawAmp, mouth)
                val presentationTimeUs = frameIdx.toLong() * 1_000_000L / fps
                val isEos = frameIdx == totalFrames - 1
                // Resolved ONCE per frame, same reasoning as everything else
                // this comment block already covers — currentOverlays is a
                // computed property (re-walks every layer on each read), so
                // reading it once and reusing across targets avoids doing
                // that walk twice for dual-aspect export.
                // TIME-RESOLVED only, shared across every dual-aspect
                // target below — parenting (bone/group attachment) is
                // canvas-size-dependent and happens per-target inside
                // RigRenderer.draw itself. See OverlayResolver's doc comment.
                val timeResolvedOverlays = engine.currentOverlays
                // Also hoisted for the same reason as timeResolvedOverlays
                // above — a plain stored-field read (like currentAngles),
                // not per-target-dependent, so no reason to redo it per
                // dual-aspect target.
                val figureOverrides = engine.currentFigureOverrides

                for (t in targets) {
                    renderer.draw(t.canvas, engine.currentAngles, appearance, t.width, t.height,
                        forExport              = true,
                        mouthShape             = engine.currentMouthShape,
                        mouthOpenness          = engine.currentAmplitude,
                        expression             = engine.currentExpression,
                        eyeOpenness            = engine.currentEyeOpenness,
                        cameraZoom             = engine.currentCameraZoom,
                        cameraPanX             = engine.currentCameraPanX,
                        cameraPanY             = engine.currentCameraPanY,
                        cameraShakeIntensity   = engine.currentShakeIntensity,
                        skyColor               = engine.currentSkyColor,
                        groundColor            = engine.currentGroundColor,
                        horizonY               = engine.currentHorizonY,
                        sceneShape             = engine.currentSceneShape,
                        sceneAtmosphere        = engine.currentSceneAtmosphere,
                        currentTimeSec         = timeSec,
                        captionText            = engine.currentCaption,
                        referenceOverlay       = overlay,
                        referenceOverlayBitmap = overlayBitmap,
                        overlays               = timeResolvedOverlays,
                        overrides              = figureOverrides)

                    t.bitmap.getPixels(t.pixels, 0, t.width, 0, 0, t.width, t.height)
                    argbToNV12(t.pixels, t.width, t.height, t.nv12)
                    queueFrame(t, t.nv12, presentationTimeUs, isEos)
                    drainAvailable(t)
                }

                onProgress(frameIdx.toFloat() / totalFrames * 0.90f, computeEtaSec(frameIdx + 1, totalFrames, exportStartMs))
            }

            // ── Drain + audio + finish, per target ─────────────────────────────
            for (t in targets) {
                drainUntilEndOfStream(t)
                writeAudio(t)
            }
            onProgress(1f, 0f)

            for (t in targets) {
                t.encoder.stop(); t.encoder.release(); t.encoderReleased = true
                if (t.muxerStarted) { t.muxer.stop(); t.muxer.release(); t.muxerReleased = true }
                t.output.finish(context)
            }
            overallSuccess = true

            Log.i(TAG, "Exported $totalFrames frames x ${targets.size} target(s) -> " +
                targets.joinToString { it.output.location })

            targets.map { ExportResult(it.output.uri, it.output.location, it.aspectLabel) }

        } finally {
            for (t in targets) {
                t.bitmap.recycle()
                // Release hardware resources if the success path didn't get there —
                // covers both cancellation (CancellationException) and unexpected errors.
                if (!t.encoderReleased) { runCatching { t.encoder.stop() }; runCatching { t.encoder.release() } }
                if (t.muxerStarted && !t.muxerReleased) { runCatching { t.muxer.stop() }; runCatching { t.muxer.release() } }
                if (!overallSuccess) t.output.abort(context)
            }
        }

    } // end withContext(Dispatchers.Default)

    /**
     * Encapsulates where/how the encoded file is written, isolating the
     * API-29+ (MediaStore, scoped storage) vs API 26-28 (legacy public dir +
     * runtime permission + manual media-scan) code paths.
     */
    private sealed class OutputTarget {
        abstract val uri: Uri
        abstract val location: String
        abstract fun openMuxer(): MediaMuxer
        abstract fun finish(context: Context)
        /** Called on cancellation or error — deletes the partial output cleanly. */
        abstract fun abort(context: Context)

        companion object {
            fun create(context: Context, projectName: String): OutputTarget {
                val safe = projectName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                val name = "${safe}_${System.currentTimeMillis()}.mp4"
                return if (Build.VERSION.SDK_INT >= 29) MediaStoreOutput(context, name)
                       else LegacyFileOutput(context, name)
            }
        }
    }

    /** API 29+: writes into the public Movies/RigScript collection via MediaStore. */
    private class MediaStoreOutput(context: Context, name: String) : OutputTarget() {
        override val uri: Uri
        override val location: String = "Movies/RigScript/$name"
        private val pfd: ParcelFileDescriptor

        init {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RigScript")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open output descriptor")
        }

        override fun openMuxer(): MediaMuxer =
            MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        override fun finish(context: Context) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            pfd.close()
        }

        override fun abort(context: Context) {
            runCatching { pfd.close() }
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * API 26-28: writes directly to the legacy public Movies directory.
     * Requires `WRITE_EXTERNAL_STORAGE` (declared with maxSdkVersion=28) granted
     * at runtime — the caller is responsible for requesting it before exporting.
     */
    private class LegacyFileOutput(context: Context, name: String) : OutputTarget() {
        private val file: File
        override val uri: Uri
        override val location: String

        init {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "RigScript")
            dir.mkdirs()
            file = File(dir, name)
            location = file.absolutePath
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        override fun openMuxer(): MediaMuxer =
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        override fun finish(context: Context) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }

        override fun abort(context: Context) {
            runCatching { file.delete() }
        }
    }

    // ── YUV conversion ────────────────────────────────────────────────────────

    /**
     * Converts a bulk-read ARGB pixel array to NV12 (YUV420SemiPlanar).
     * [pixels] must be filled with [Bitmap.getPixels] before calling.
     * Using getPixels() once per frame instead of getPixel() per pixel
     * eliminates ~400M JNI calls for a 7-minute 1080p export.
     */
    /**
     * Estimated seconds remaining, from ACTUAL measured throughput so far
     * (frames done / wall-clock elapsed) rather than a separate benchmark
     * pass — a dedicated pre-flight benchmark would either add its own time
     * to every export by re-rendering warm-up frames, or use a different code
     * path than the real render+encode loop and risk not matching its speed.
     * Returns null before [MIN_FRAMES_FOR_ETA] frames — see that constant's
     * doc comment for why.
     */
    private fun computeEtaSec(framesDone: Int, totalFrames: Int, startMs: Long): Float? {
        if (framesDone < MIN_FRAMES_FOR_ETA) return null
        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val msPerFrame = elapsedMs.toFloat() / framesDone
        val framesLeft = (totalFrames - framesDone).coerceAtLeast(0)
        return (framesLeft * msPerFrame) / 1000f
    }

    private fun argbToNV12(pixels: IntArray, w: Int, h: Int, out: ByteArray) {
        var yOff  = 0
        var uvOff = w * h
        for (row in 0 until h) {
            val rowBase = row * w
            for (col in 0 until w) {
                val p = pixels[rowBase + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr  8) and 0xFF
                val b =  p         and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yOff++] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvOff++] = u.coerceIn(0, 255).toByte()
                    out[uvOff++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    // ── Audio helpers ─────────────────────────────────────────────────────────

    private fun addAudioTrack(muxer: MediaMuxer, audioPath: String): Int =
        runCatching {
            val ex = MediaExtractor().also { it.setDataSource(audioPath) }
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val idx = muxer.addTrack(fmt)
                    ex.release()
                    return idx
                }
            }
            ex.release(); -1
        }.getOrElse { Log.e(TAG, "addAudioTrack failed", it); -1 }

    private fun copyAudioTrack(
        audioPath: String, muxer: MediaMuxer, trackIdx: Int, maxPtsUs: Long
    ) = runCatching {
        val ex = MediaExtractor().also { it.setDataSource(audioPath) }
        var src = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true) { src = i; break }
        }
        if (src < 0) { ex.release(); return@runCatching }
        ex.selectTrack(src)

        val buf  = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val sz = ex.readSampleData(buf, 0)
            if (sz < 0 || ex.sampleTime > maxPtsUs) break
            info.offset = 0; info.size = sz
            info.presentationTimeUs = ex.sampleTime
            info.flags = ex.sampleFlags
            muxer.writeSampleData(trackIdx, buf, info)
            ex.advance()
        }
        ex.release()
    }.onFailure { Log.e(TAG, "copyAudioTrack failed", it) }
}
