package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        amplitudeSettings: com.example.data.AmplitudeSettings = com.example.data.AmplitudeSettings(),
        onProgress: (Float) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {

        val settings        = project.exportSettings
        val (width, height) = settings.dimensions()
        val fps             = settings.fps
        val bitrate         = settings.bitrateMbps * 1_000_000
        val appearance      = project.appearance
        val bgColor         = appearance.exportBgColor.toInt()

        // Duration = max(script length, actual audio length) + 1s tail. If the
        // audio runs longer than the scripted pose changes, the figure holds its
        // final pose (with idle/talk motion still active) for the remainder
        // instead of the export being truncated.
        val lastKf    = keyframes.lastOrNull()
        val scriptEnd = if (lastKf != null) lastKf.timeSec + lastKf.duration else 0f
        val totalSec  = maxOf(scriptEnd, project.audioDurationSec) + 1f
        val totalFrames = (totalSec * fps).toInt().coerceAtLeast(1)

        val engine = PlaybackEngine().also {
            it.loadTimeline(keyframes)
            it.amplitudeSettings = amplitudeSettings
        }

        // Pre-baked amplitude envelope, indexed at AMPLITUDE_ANALYSIS_FPS (always 30fps,
        // independent of the export FPS so the envelope lookup is always correct).
        val envelope      = project.amplitudeEnvelope
        val mouthEnvelope = project.mouthShapeEnvelope
        val envFps        = AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS
        // Own private renderer instance — see the class doc on RigRenderer for
        // why this must never be a shared singleton with the live preview.
        val renderer = RigRenderer()

        val bitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fCanvas = Canvas(bitmap)
        val nv12    = ByteArray(width * height * 3 / 2)

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

        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var muxerStarted  = false
        val bufferInfo    = MediaCodec.BufferInfo()

        fun startMuxerIfNeeded() {
            if (!muxerStarted) {
                videoTrackIdx = muxer.addTrack(encoder.outputFormat)
                if (settings.embedAudio && project.audioFilePath != null)
                    audioTrackIdx = addAudioTrack(muxer, project.audioFilePath)
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
                    // INFO_TRY_AGAIN_LATER: dequeueOutputBuffer already blocked up to
                    // TIMEOUT µs, so looping here isn't a busy-spin — just retry.
                }
            }
        }

        /**
         * Queues one frame of NV12 data to the encoder, retrying (and draining
         * available output in between) if no input buffer is free yet.
         *
         * This codec was configured in byte-buffer input mode (no Surface passed
         * to `configure`/no `createInputSurface()` call), so end-of-stream MUST be
         * signalled via [MediaCodec.BUFFER_FLAG_END_OF_STREAM] on the last queued
         * buffer. Calling `encoder.signalEndOfInputStream()` — which is only valid
         * for Surface-input mode — throws `IllegalStateException: ... called
         * without an input surface set`, which is what previously crashed export.
         */
        fun queueFrame(data: ByteArray, presentationTimeUs: Long, isEos: Boolean) {
            var inIdx = -1
            var attempts = 0
            while (inIdx < 0) {
                inIdx = encoder.dequeueInputBuffer(TIMEOUT)
                if (inIdx < 0) {
                    drainAvailable()
                    check(++attempts < 500) { "Encoder stalled: no input buffer became available" }
                }
            }
            encoder.getInputBuffer(inIdx)!!.let { it.clear(); it.put(data) }
            val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inIdx, 0, data.size, presentationTimeUs, flags)
        }

        // ── Render + encode loop ──────────────────────────────────────────────
        for (frameIdx in 0 until totalFrames) {
            fCanvas.drawColor(bgColor)
            val timeSec = frameIdx.toFloat() / fps
            val rawAmp  = if (envelope.isNotEmpty()) {
                val envIdx = (timeSec * envFps).toInt().coerceIn(0, envelope.size - 1)
                envelope[envIdx]
            } else 0f
            val rawMouth = if (mouthEnvelope.isNotEmpty()) {
                mouthEnvelope[(timeSec * envFps).toInt().coerceIn(0, mouthEnvelope.size - 1)]
            } else MouthShape.CLOSED
            engine.seekToWithAmplitude(timeSec, rawAmp, rawMouth)
            renderer.draw(fCanvas, engine.currentAngles, appearance, width, height,
                forExport     = true,
                mouthShape    = engine.currentMouthShape,
                mouthOpenness = engine.currentAmplitude)

            argbToNV12(bitmap, width, height, nv12)

            val presentationTimeUs = frameIdx.toLong() * 1_000_000L / fps
            queueFrame(nv12, presentationTimeUs, isEos = frameIdx == totalFrames - 1)
            drainAvailable()

            onProgress(frameIdx.toFloat() / totalFrames * 0.90f)
        }

        // ── Drain remaining output until the EOS buffer comes through ─────────
        drainUntilEndOfStream()

        // ── Mux audio (copy compressed samples, no re-encode) ─────────────────
        if (muxerStarted && audioTrackIdx >= 0 && project.audioFilePath != null) {
            copyAudioTrack(project.audioFilePath, muxer, audioTrackIdx, (totalSec * 1_000_000L).toLong())
        }

        onProgress(1f)
        bitmap.recycle()
        encoder.stop();  encoder.release()
        muxer.stop();    muxer.release()
        output.finish(context)

        Log.i(TAG, "Exported $totalFrames frames -> ${output.location}")
        ExportResult(output.uri, output.location)
    }

    // ── Output targets ───────────────────────────────────────────────────────

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
            // Make the file show up in Gallery/file managers immediately.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
    }

    // ── YUV conversion ────────────────────────────────────────────────────────

    /**
     * Converts an ARGB [Bitmap] to NV12 (YUV420SemiPlanar) into [out].
     * Layout: Y plane (w×h) then interleaved UV pairs (w×h/2).
     * Uses BT.601 limited-range coefficients.
     */
    private fun argbToNV12(src: Bitmap, w: Int, h: Int, out: ByteArray) {
        var yOff  = 0
        var uvOff = w * h
        for (row in 0 until h) {
            for (col in 0 until w) {
                val p = src.getPixel(col, row)
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
