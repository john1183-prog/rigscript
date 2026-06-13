package com.example.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Analyses an audio file entirely offline using [MediaExtractor] + [MediaCodec].
 *
 * Produces a normalised RMS amplitude envelope at [targetFps] samples per second.
 * Call [analyze] once when audio is imported; store the result in [ProjectDef]
 * so it never needs to be re-computed.
 *
 * No RECORD_AUDIO permission required — this reads from a file, not the microphone.
 */
object AmplitudeAnalyzer {

    private const val TAG = "AmplitudeAnalyzer"
    private const val TIMEOUT_US = 10_000L

    /**
     * Suspending entry-point. Returns a [FloatArray] of length ≈ audioDuration × targetFps,
     * each value normalised to [0, 1] relative to the peak RMS in the file.
     *
     * Returns an empty array on any error so callers can degrade gracefully.
     */
    suspend fun analyze(audioFilePath: String, targetFps: Int = 30): FloatArray =
        withContext(Dispatchers.Default) {
            runCatching { analyzeInternal(audioFilePath, targetFps) }
                .getOrElse { e ->
                    Log.e(TAG, "Amplitude analysis failed", e)
                    FloatArray(0)
                }
        }

    private fun analyzeInternal(path: String, targetFps: Int): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        // ── Find first audio track ─────────────────────────────────────────────
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
            return FloatArray(0)
        }
        extractor.selectTrack(audioTrack)

        val sampleRate    = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels      = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs    = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        val durationSec   = durationUs / 1_000_000f
        val totalFrames   = ((durationSec + 1f) * targetFps).toInt().coerceAtLeast(1)
        val samplesPerFrame = (sampleRate / targetFps).coerceAtLeast(1)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val amplitudes   = FloatArray(totalFrames)
        val bufferInfo   = MediaCodec.BufferInfo()
        var pcmAccum     = 0.0   // sum of squares for current frame window
        var pcmSamples   = 0     // mono samples accumulated
        var frameIdx     = 0
        var inputDone    = false
        var outputDone   = false

        while (!outputDone) {
            // ── Feed compressed data to decoder ───────────────────────────────
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

            // ── Drain decoded PCM ─────────────────────────────────────────────
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
                        while (shorts.hasRemaining() && frameIdx < totalFrames) {
                            // Average all channels into a mono sample
                            var mono = 0.0
                            for (c in 0 until channels) {
                                mono += if (shorts.hasRemaining()) shorts.get() / 32768.0 else 0.0
                            }
                            mono /= channels
                            pcmAccum += mono * mono
                            pcmSamples++
                            if (pcmSamples >= samplesPerFrame) {
                                amplitudes[frameIdx++] = sqrt(pcmAccum / pcmSamples).toFloat()
                                pcmAccum  = 0.0
                                pcmSamples = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        }

        codec.stop(); codec.release(); extractor.release()

        // Flush any remaining partial window
        if (pcmSamples > 0 && frameIdx < totalFrames) {
            amplitudes[frameIdx] = sqrt(pcmAccum / pcmSamples).toFloat()
        }

        // Normalise to [0, 1]
        val peak = amplitudes.maxOrNull() ?: 1f
        if (peak > 0f) for (i in amplitudes.indices) amplitudes[i] /= peak

        return amplitudes
    }
}
