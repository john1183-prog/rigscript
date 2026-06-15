package com.example.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Result of offline audio analysis.
 *
 * [envelope]    Normalised (0..1) RMS amplitude, one value per
 *               [AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS] of audio.
 * [durationSec] Actual decoded duration, derived from `envelope.size`. This is
 *               intentionally NOT read from container metadata (`KEY_DURATION`),
 *               which is absent or zero for some files and would otherwise
 *               silently truncate the envelope to ~1 second.
 */
data class AmplitudeAnalysisResult(val envelope: FloatArray, val durationSec: Float)

/**
 * Analyses an audio file entirely offline using [MediaExtractor] + [MediaCodec].
 * No RECORD_AUDIO permission required — this reads from a file, not the microphone.
 */
object AmplitudeAnalyzer {

    private const val TAG = "AmplitudeAnalyzer"
    private const val TIMEOUT_US = 10_000L

    /**
     * Fixed sampling rate for the amplitude envelope. Deliberately decoupled from
     * [com.example.data.ExportSettings.fps] — if the user changes export FPS after
     * importing audio, the envelope (analysed once at import time) would otherwise
     * be indexed at the wrong rate and drift out of sync.
     */
    const val AMPLITUDE_ANALYSIS_FPS = 30

    suspend fun analyze(audioFilePath: String): AmplitudeAnalysisResult =
        withContext(Dispatchers.Default) {
            runCatching { analyzeInternal(audioFilePath, AMPLITUDE_ANALYSIS_FPS) }
                .getOrElse { e ->
                    Log.e(TAG, "Amplitude analysis failed", e)
                    AmplitudeAnalysisResult(FloatArray(0), 0f)
                }
        }

    private fun analyzeInternal(path: String, fps: Int): AmplitudeAnalysisResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i; format = f; break
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release()
            Log.w(TAG, "No audio track found in $path")
            return AmplitudeAnalysisResult(FloatArray(0), 0f)
        }
        extractor.selectTrack(audioTrack)

        val sampleRate      = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels        = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val samplesPerFrame = (sampleRate / fps).coerceAtLeast(1)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        // Growable list — NOT pre-sized from container duration. Some containers
        // report KEY_DURATION as 0/absent; pre-sizing from that would silently
        // truncate the envelope to ~1 second of audio.
        val amplitudes = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var pcmAccum   = 0.0
        var pcmSamples = 0
        var inputDone  = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf  = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* no-op */ }
                outIdx >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0) {
                        val shorts = buf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            var mono = 0.0
                            for (c in 0 until channels) {
                                mono += if (shorts.hasRemaining()) shorts.get() / 32768.0 else 0.0
                            }
                            mono /= channels
                            pcmAccum += mono * mono
                            pcmSamples++
                            if (pcmSamples >= samplesPerFrame) {
                                amplitudes.add(sqrt(pcmAccum / pcmSamples).toFloat())
                                pcmAccum   = 0.0
                                pcmSamples = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                // INFO_TRY_AGAIN_LATER -> loop again; dequeueOutputBuffer's own
                // TIMEOUT_US prevents a busy-spin.
            }
        }

        codec.stop(); codec.release(); extractor.release()

        // Flush any partial trailing window
        if (pcmSamples > 0) {
            amplitudes.add(sqrt(pcmAccum / pcmSamples).toFloat())
        }

        val arr  = amplitudes.toFloatArray()
        val peak = arr.maxOrNull() ?: 1f
        if (peak > 0f) for (i in arr.indices) arr[i] /= peak

        return AmplitudeAnalysisResult(arr, arr.size / fps.toFloat())
    }
}
