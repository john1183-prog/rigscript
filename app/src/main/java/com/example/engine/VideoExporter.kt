package com.example.engine

import android.content.Context
import android.graphics.*
import android.media.*
import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.data.ProjectDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes the animation to an H.264/MP4 file and optionally muxes the source
 * audio track verbatim (no re-encoding, lossless, fast).
 *
 * Each frame is drawn onto an off-screen [Bitmap], converted to NV12
 * (YUV420SemiPlanar) in software, then queued to [MediaCodec] as a byte-buffer
 * input. This approach works on all API 26+ devices without EGL.
 */
object VideoExporter {

    private const val TAG    = "VideoExporter"
    private const val MIME   = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT = 10_000L

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun export(
        context: Context,
        project: ProjectDef,
        keyframes: List<BakedKeyframe>,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.Default) {

        val settings         = project.exportSettings
        val (width, height)  = settings.dimensions()
        val fps              = settings.fps
        val bitrate          = settings.bitrateMbps * 1_000_000
        val appearance       = project.appearance
        val bgColor          = appearance.exportBgColor.toInt()

        // Duration = last keyframe end + 1s breathing room
        val lastKf      = keyframes.lastOrNull()
        val totalSec    = if (lastKf != null) lastKf.timeSec + lastKf.duration + 1f else 10f
        val totalFrames = (totalSec * fps).toInt().coerceAtLeast(1)

        // Offline render engine
        val engine = PlaybackEngine().also { it.loadTimeline(keyframes) }

        // Off-screen rendering
        val bitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fCanvas = Canvas(bitmap)
        val nv12    = ByteArray(width * height * 3 / 2)   // reused per frame

        // Encoder
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

        // Output file + muxer
        val outputFile = resolveOutputFile(context, project.projectName)
        outputFile.parentFile?.mkdirs()
        val muxer      = MediaMuxer(outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrackIdx  = -1
        var audioTrackIdx  = -1
        var muxerStarted   = false
        val bufferInfo     = MediaCodec.BufferInfo()

        // ── Render + encode loop ──────────────────────────────────────────────
        for (frameIdx in 0 until totalFrames) {
            // 1. Render frame
            fCanvas.drawColor(bgColor)
            engine.seekTo(frameIdx.toFloat() / fps)
            RigRenderer.draw(fCanvas, engine.currentAngles, appearance, width, height,
                forExport = true)

            // 2. ARGB → NV12
            argbToNV12(bitmap, width, height, nv12)

            // 3. Feed to encoder
            val inIdx = encoder.dequeueInputBuffer(TIMEOUT)
            if (inIdx >= 0) {
                encoder.getInputBuffer(inIdx)!!.let { buf -> buf.clear(); buf.put(nv12) }
                encoder.queueInputBuffer(inIdx, 0, nv12.size,
                    frameIdx.toLong() * 1_000_000L / fps, 0)
            }

            // 4. Drain encoder output
            drain@ while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drain

                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackIdx = muxer.addTrack(encoder.outputFormat)
                            if (settings.embedAudio && project.audioFilePath != null)
                                audioTrackIdx = addAudioTrack(muxer, project.audioFilePath)
                            muxer.start()
                            muxerStarted = true
                        }
                    }

                    outIdx >= 0 -> {
                        val isConfig = bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && muxerStarted && bufferInfo.size > 0) {
                            muxer.writeSampleData(videoTrackIdx,
                                encoder.getOutputBuffer(outIdx)!!, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            break@drain
                    }
                }
            }

            onProgress(frameIdx.toFloat() / totalFrames * 0.90f)
        }

        // ── Signal EOS and drain remaining ────────────────────────────────────
        encoder.signalEndOfInputStream()
        eos@ while (true) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIdx = muxer.addTrack(encoder.outputFormat)
                        if (settings.embedAudio && project.audioFilePath != null)
                            audioTrackIdx = addAudioTrack(muxer, project.audioFilePath)
                        muxer.start(); muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && muxerStarted && bufferInfo.size > 0) {
                        muxer.writeSampleData(videoTrackIdx,
                            encoder.getOutputBuffer(outIdx)!!, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        break@eos
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break@eos
            }
        }

        // ── Mux audio (copy compressed samples, no re-encode) ─────────────────
        if (muxerStarted && audioTrackIdx >= 0 && project.audioFilePath != null) {
            copyAudioTrack(project.audioFilePath, muxer, audioTrackIdx,
                (totalSec * 1_000_000L).toLong())
        }

        onProgress(1f)
        bitmap.recycle()
        encoder.stop();  encoder.release()
        muxer.stop();    muxer.release()

        Log.i(TAG, "Export done — $totalFrames frames → ${outputFile.absolutePath}")
        outputFile.absolutePath
    }

    // ── YUV conversion ────────────────────────────────────────────────────────

    /**
     * Converts an ARGB [Bitmap] to NV12 (YUV420SemiPlanar) into [out].
     * Layout: Y plane (w×h) then interleaved UV pairs (w×h/2).
     * Uses BT.601 limited-range coefficients.
     */
    private fun argbToNV12(src: Bitmap, w: Int, h: Int, out: ByteArray) {
        val yBase = 0
        val uvBase = w * h
        var yOff = yBase
        var uvOff = uvBase

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

    // ── Output path ───────────────────────────────────────────────────────────

    private fun resolveOutputFile(context: Context, projectName: String): File {
        val safe = projectName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val name = "${safe}_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= 29)
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
        else
            File(context.cacheDir, "exports/$name").also { it.parentFile?.mkdirs() }
    }
}
