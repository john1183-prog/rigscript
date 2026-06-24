package com.example.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Coarse mouth-shape buckets derived from per-frame audio features.
 * No transcript or phoneme alignment required.
 *
 *  CLOSED : amplitude below silence threshold — lips together.
 *  NARROW : high zero-crossing rate — sibilants / fricatives (s, f, sh, th).
 *  WIDE   : low ZCR + high amplitude — open vowels (ah, oh).
 *  MID    : everything else — the default talking shape.
 */
object MouthShape {
    const val CLOSED = 0
    const val MID    = 1
    const val WIDE   = 2
    const val NARROW = 3
}

/**
 * Result of offline audio analysis. Both arrays share the same length and
 * sampling rate ([AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS]).
 *
 * [envelope]    Normalised (0..1) perceptual amplitude per frame. Processed
 *               through 95th-percentile normalisation → 3-frame temporal
 *               smoothing → sqrt perceptual curve so quiet speech still drives
 *               visible motion even when a louder transient exists in the file.
 * [mouthShapes] Coarse [MouthShape] per frame, classified from the linearly-
 *               normalised (pre-sqrt) smoothed amplitude + zero-crossing rate.
 * [durationSec] Decoded duration in seconds — derived from envelope.size /
 *               AMPLITUDE_ANALYSIS_FPS, NOT from container metadata which is
 *               absent or zero for some files.
 */
data class AmplitudeAnalysisResult(
    val envelope: FloatArray,
    val mouthShapes: IntArray,
    val durationSec: Float
)

object AmplitudeAnalyzer {

    private const val TAG        = "AmplitudeAnalyzer"
    private const val TIMEOUT_US = 10_000L

    /** Fixed sampling rate for both envelopes — decoupled from export FPS. */
    const val AMPLITUDE_ANALYSIS_FPS = 30

    // ── Mouth-shape thresholds (applied to linearly-normalised values) ─────────
    private const val SILENCE_THRESHOLD        = 0.06f
    private const val ZCR_SIBILANT_THRESHOLD   = 0.15f
    private const val WIDE_AMPLITUDE_THRESHOLD = 0.35f

    suspend fun analyze(audioFilePath: String): AmplitudeAnalysisResult =
        withContext(Dispatchers.Default) {
            runCatching { analyzeInternal(audioFilePath, AMPLITUDE_ANALYSIS_FPS) }
                .getOrElse { e ->
                    Log.e(TAG, "Amplitude analysis failed", e)
                    AmplitudeAnalysisResult(FloatArray(0), IntArray(0), 0f)
                }
        }

    private fun analyzeInternal(path: String, fps: Int): AmplitudeAnalysisResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
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
                Log.w(TAG, "No audio track found in $path")
                return AmplitudeAnalysisResult(FloatArray(0), IntArray(0), 0f)
            }
            extractor.selectTrack(audioTrack)

            val sampleRate      = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels        = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val samplesPerFrame = (sampleRate / fps).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // Growable lists — NOT pre-sized from container duration.
            val amplitudes = ArrayList<Float>()
            val zcrs       = ArrayList<Float>()

            val bufferInfo = MediaCodec.BufferInfo()
            var pcmAccum   = 0.0
            var pcmSamples = 0
            var zcrCount   = 0
            var prevSign   = 0
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
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && bufferInfo.size > 0) {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val shorts = buf.asShortBuffer()
                            while (shorts.hasRemaining()) {
                                var mono = 0.0
                                for (c in 0 until channels) {
                                    mono += if (shorts.hasRemaining()) shorts.get() / 32768.0 else 0.0
                                }
                                mono /= channels
                                pcmAccum += mono * mono

                                // Zero-crossing rate: sign changes per sample
                                val sign = when { mono > 0.0 -> 1; mono < 0.0 -> -1; else -> 0 }
                                if (prevSign != 0 && sign != 0 && sign != prevSign) zcrCount++
                                if (sign != 0) prevSign = sign

                                pcmSamples++
                                if (pcmSamples >= samplesPerFrame) {
                                    amplitudes.add(sqrt(pcmAccum / pcmSamples).toFloat())
                                    zcrs.add(zcrCount.toFloat() / pcmSamples)
                                    pcmAccum = 0.0; pcmSamples = 0; zcrCount = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            // Flush partial trailing window
            if (pcmSamples > 0) {
                amplitudes.add(sqrt(pcmAccum / pcmSamples).toFloat())
                zcrs.add(zcrCount.toFloat() / pcmSamples)
            }

            val n = amplitudes.size
            if (n == 0) return AmplitudeAnalysisResult(FloatArray(0), IntArray(0), 0f)

            val arr  = amplitudes.toFloatArray()
            val zArr = zcrs.toFloatArray()

            // 1. 95th-percentile normalisation — prevents one loud plosive from
            //    compressing all quiet speech toward zero.
            val sorted     = arr.copyOf().also { it.sort() }
            val nonZeroIdx = sorted.indexOfFirst { it > 0f }
            val peak = when {
                nonZeroIdx < 0      -> 1f
                n - nonZeroIdx < 20 -> sorted.last()
                else -> sorted[nonZeroIdx + ((n - nonZeroIdx) * 0.95f).toInt()
                    .coerceAtMost(n - 1)].coerceAtLeast(1e-6f)
            }
            for (i in 0 until n) arr[i] = (arr[i] / peak).coerceIn(0f, 1f)

            // 2. 3-frame temporal smoothing (±33ms at 30fps) — suppresses
            //    single-frame plosive spikes without meaningful lag.
            val smoothed = FloatArray(n)
            for (i in 0 until n) {
                val lo = (i - 1).coerceAtLeast(0)
                val hi = (i + 1).coerceAtMost(n - 1)
                smoothed[i] = (arr[lo] + arr[i] + arr[hi]) / 3f
            }

            // 3. Mouth-shape classification on linear smoothed values.
            val shapes = IntArray(n)
            for (i in 0 until n) {
                shapes[i] = when {
                    smoothed[i] < SILENCE_THRESHOLD          -> MouthShape.CLOSED
                    zArr[i]     >= ZCR_SIBILANT_THRESHOLD    -> MouthShape.NARROW
                    smoothed[i] >= WIDE_AMPLITUDE_THRESHOLD  -> MouthShape.WIDE
                    else                                     -> MouthShape.MID
                }
            }

            // 4. Sqrt perceptual curve on the motion envelope ONLY — applied
            //    after classification so thresholds remain calibrated.
            //    Maps 0.25 linear → 0.50 perceptual: quiet speech gets the
            //    motion weight it deserves.
            for (i in 0 until n) smoothed[i] = sqrt(smoothed[i])

            return AmplitudeAnalysisResult(smoothed, shapes, n / fps.toFloat())

        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }
}
