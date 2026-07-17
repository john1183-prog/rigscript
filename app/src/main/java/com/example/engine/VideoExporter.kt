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
 * Result of a completed export.
 *
 * [uri]      A `content://` URI suitable for `ACTION_VIEW` / `ACTION_SEND` — works
 *            for both the MediaStore path (API 29+) and the FileProvider-wrapped
 *            legacy path (API 26-28).
 * [location] Human-readable location, for display in the UI.
 */
data class ExportResult(val uri: Uri, val location: String)

/**
 * Encodes the animation to an H.264/MP4 file and optionally muxes the source
 * audio track verbatim (no re-encoding, lossless, fast).
 *
 * Each frame is drawn onto an off-screen [Bitmap], converted to NV12
 * (YUV420SemiPlanar) in software, then queued to [MediaCodec] as a byte-buffer
 * input. This works on all API 26+ devices without EGL.
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

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        amplitudeSettings: com.example.data.AmplitudeSettings = com.example.data.AmplitudeSettings(),
        onProgress: (progress: Float, etaSec: Float?) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {

        val settings        = project.exportSettings
        val (width, height) = settings.dimensions()
        val fps             = settings.fps
        val bitrate         = settings.bitrateMbps * 1_000_000
        val appearance      = project.appearance

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
        }
        // Own private renderer instance — see the class doc on RigRenderer for
        // why this must never be a shared singleton with the live preview.
        val renderer = RigRenderer()

        // V2 — reference overlay image decoded ONCE up front (I/O + allocation
        // must never happen inside the per-frame render loop below).
        val overlay = project.referenceOverlay
        val overlayBitmap: Bitmap? =
            if (overlay.type == com.example.data.ReferenceOverlay.OverlayType.IMAGE && overlay.imagePath != null)
                runCatching { BitmapFactory.decodeFile(overlay.imagePath) }.getOrNull()
            else null

        val bitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fCanvas = Canvas(bitmap)
        val pixels  = IntArray(width * height)   // reused every frame — one bulk read replaces w*h getPixel() calls
        val nv12    = ByteArray(width * height * 3 / 2)

        // V2 — background music + sound effects. Only actually decodes/mixes/
        // re-encodes when either is configured; every other export keeps
        // using the fast verbatim-copy audio path below, untouched — see
        // BackgroundMusicSettings and AudioMixer's doc comments for why this
        // is gated rather than always running through the mixer.
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

        val videoFormat = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MIME)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val output = OutputTarget.create(context, project.projectName)
        val muxer  = output.openMuxer()

        var videoTrackIdx    = -1
        var audioTrackIdx    = -1
        var mixedAudioTrackIdx = -1
        var muxerStarted     = false
        var encoderReleased  = false
        var muxerReleased    = false
        var success          = false
        val bufferInfo       = MediaCodec.BufferInfo()

        fun startMuxerIfNeeded() {
            if (!muxerStarted) {
                videoTrackIdx = muxer.addTrack(encoder.outputFormat)
                if (mixedTrack != null) {
                    mixedAudioTrackIdx = muxer.addTrack(mixedTrack.format)
                } else if (settings.embedAudio && project.audioFilePath != null) {
                    audioTrackIdx = addAudioTrack(muxer, project.audioFilePath)
                }
                muxer.start(); muxerStarted = true
            }
        }

        /**
         * Drains whatever output is immediately available (timeout = 0, i.e.
         * non-blocking). Called once per rendered frame to keep the encoder's
         * output queue from backing up. Returns true once the end-of-stream
         * buffer has been observed and released.
         */
        fun drainAvailable(): Boolean {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxerIfNeeded()
                    outIdx >= 0 -> {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && muxerStarted && bufferInfo.size > 0) {
                            muxer.writeSampleData(videoTrackIdx, encoder.getOutputBuffer(outIdx)!!, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        /** Blocking variant used only for the final drain, where we must wait for EOS. */
        fun drainUntilEndOfStream() {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxerIfNeeded()
                    outIdx >= 0 -> {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && muxerStarted && bufferInfo.size > 0) {
                            muxer.writeSampleData(videoTrackIdx, encoder.getOutputBuffer(outIdx)!!, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /**
         * Queues one frame of NV12 data to the encoder. EOS must be signalled
         * via [MediaCodec.BUFFER_FLAG_END_OF_STREAM] on the last buffer —
         * signalEndOfInputStream() is only valid for Surface-input mode.
         */
        fun queueFrame(data: ByteArray, presentationTimeUs: Long, isEos: Boolean) {
            var inIdx = -1; var attempts = 0
            while (inIdx < 0) {
                inIdx = encoder.dequeueInputBuffer(TIMEOUT)
                if (inIdx < 0) {
                    drainAvailable()
                    check(++attempts < 500) { "Encoder stalled: no input buffer became available" }
                }
            }
            encoder.getInputBuffer(inIdx)!!.let { it.clear(); it.put(data) }
            encoder.queueInputBuffer(inIdx, 0, data.size, presentationTimeUs,
                if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        }

        try {
            // ── Render + encode loop ──────────────────────────────────────────
            // ensureActive() every 10 frames is the key fix for real cancellation.
            // Coroutine cancellation is cooperative — without a suspension/check
            // point inside this tight CPU loop, cancelExport() sets the flag but
            // the loop keeps running to completion. ensureActive() throws
            // CancellationException as soon as the flag is seen, propagating to
            // the finally block which releases encoder/muxer and aborts the output.
            val exportStartMs = System.currentTimeMillis()
            for (frameIdx in 0 until totalFrames) {
                if (frameIdx % 10 == 0) currentCoroutineContext().ensureActive()

                val timeSec = frameIdx.toFloat() / fps
                val envIdx  = if (envelope.isNotEmpty())
                    (timeSec * envFps).toInt().coerceIn(0, envelope.size - 1) else -1
                val rawAmp  = if (envIdx >= 0) envelope[envIdx] else 0f
                val mouth   = if (envIdx >= 0 && mouthEnvelope.isNotEmpty())
                    mouthEnvelope[envIdx.coerceAtMost(mouthEnvelope.size - 1)] else MouthShape.CLOSED

                engine.seekToWithAmplitude(timeSec, rawAmp, mouth)
                renderer.draw(fCanvas, engine.currentAngles, appearance, width, height,
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
                    referenceOverlayBitmap = overlayBitmap)

                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                argbToNV12(pixels, width, height, nv12)

                val presentationTimeUs = frameIdx.toLong() * 1_000_000L / fps
                queueFrame(nv12, presentationTimeUs, isEos = frameIdx == totalFrames - 1)
                drainAvailable()

                onProgress(frameIdx.toFloat() / totalFrames * 0.90f, computeEtaSec(frameIdx + 1, totalFrames, exportStartMs))
            }

            // ── Drain + audio + finish ────────────────────────────────────────
            drainUntilEndOfStream()
            if (muxerStarted) {
                if (mixedAudioTrackIdx >= 0 && mixedTrack != null) {
                    val audioInfo = MediaCodec.BufferInfo()
                    for (s in mixedTrack.samples) {
                        val buf = ByteBuffer.wrap(s.data)
                        audioInfo.offset = 0
                        audioInfo.size = s.data.size
                        audioInfo.presentationTimeUs = s.presentationTimeUs
                        audioInfo.flags = s.flags
                        muxer.writeSampleData(mixedAudioTrackIdx, buf, audioInfo)
                    }
                } else if (audioTrackIdx >= 0 && project.audioFilePath != null) {
                    copyAudioTrack(project.audioFilePath, muxer, audioTrackIdx, (totalSec * 1_000_000L).toLong())
                }
            }
            onProgress(1f, 0f)

            encoder.stop();  encoder.release();  encoderReleased = true
            if (muxerStarted) { muxer.stop(); muxer.release(); muxerReleased = true }
            output.finish(context)
            success = true

            Log.i(TAG, "Exported $totalFrames frames -> ${output.location}")
            ExportResult(output.uri, output.location)

        } finally {
            bitmap.recycle()
            // Release hardware resources if the success path didn't get there —
            // covers both cancellation (CancellationException) and unexpected errors.
            if (!encoderReleased) { runCatching { encoder.stop() }; runCatching { encoder.release() } }
            if (muxerStarted && !muxerReleased) { runCatching { muxer.stop() }; runCatching { muxer.release() } }
            if (!success) output.abort(context)
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
