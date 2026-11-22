package com.example.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import com.example.engine.GlesAcceptance.AudioMetrics
import java.nio.ByteOrder

/**
 * The part of the GLES self-test that touches Android media (WP1.6b): it reads what an export wrote and turns it into
 * the numbers [GlesAcceptance] judges. All the arithmetic is in [DiagnosticsDsp] and [DiagnosticsFrames] (pure, tested
 * on the JVM); this object only decodes audio, pulls frames out of a video and calls them.
 *
 * NOT DEVICE-TESTED: it compiles in CI but nothing here has run on a phone. The assumptions most likely to be wrong are
 * the ones about the platform's decoders (16-bit PCM output unless the output format says float, an output format that
 * may change after the first buffer) and about `MediaMetadataRetriever` returning the frame nearest a time.
 *
 * Every loop is bounded (a deadline, an idle limit, and the caller's `stillActive`), so a decoder that stops answering
 * ends the analysis with "no audio" rather than hanging the check. Blocking: call from a background dispatcher.
 */
object DiagnosticsMedia {

    private const val TAG = "DiagnosticsMedia"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** The most time the decode of one file may take (the fixtures are 7 s long; this only guards a hung codec). */
    private const val DECODE_DEADLINE_MS = 60_000L

    /** Consecutive output polls with nothing to read, after the input has ended, before the decode gives up (about 3 s). */
    private const val MAX_IDLE_POLLS = 300

    /** The most audio kept: far more than a fixture needs, so a surprising file cannot fill the memory. */
    private const val MAX_DECODED_SECONDS = 60.0

    /** Decoded audio, already mixed down to one channel. */
    class DecodedAudio(val mono: ShortArray, val sampleRate: Int, val channels: Int) {
        val durationSec: Double get() = if (sampleRate > 0) mono.size.toDouble() / sampleRate else 0.0
    }

    /** What [analyzeAudio] measured: the numbers [GlesAcceptance] judges, plus details for the report. */
    class AudioAnalysis(
        val metrics: AudioMetrics,
        /** Length of the audio track itself (decoded samples), which can differ from the container's length. */
        val trackDurationSec: Double,
        val sampleRate: Int,
        val channels: Int
    )

    /** One decoded video frame as ARGB pixels. */
    class Frame(val width: Int, val height: Int, val pixels: IntArray)

    // ------------------------------------------------------------------------------------------------ container

    /** The container's duration in seconds (video and audio, as `MediaMetadataRetriever` reports it), 0 if unreadable. */
    fun containerDurationSec(path: String): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (e: Exception) {
            Log.w(TAG, "Duration unreadable for $path: ${e.message}")
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun audioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** True if the file has an audio track. */
    fun hasAudioTrack(path: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            audioTrackIndex(extractor) >= 0
        } catch (e: Exception) {
            Log.w(TAG, "Tracks unreadable for $path: ${e.message}")
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------------------------------------ audio

    /** A growable `ShortArray`, so the decode does not box samples. */
    private class ShortSink {
        var data = ShortArray(1 shl 16)
        var size = 0

        fun add(source: ShortArray, count: Int) {
            if (size + count > data.size) {
                var capacity = data.size
                while (capacity < size + count) capacity *= 2
                data = data.copyOf(capacity)
            }
            System.arraycopy(source, 0, data, size, count)
            size += count
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    /**
     * Decodes the first audio track of [path] to 16-bit mono. Returns null if there is no audio track or the decode
     * failed or timed out. [stillActive] is checked on every pass so a cancelled check stops promptly.
     */
    fun decodeAudio(path: String, stillActive: () -> Boolean = { true }): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = audioTrackIndex(extractor)
            if (track < 0) return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sink = ShortSink()
            val info = MediaCodec.BufferInfo()
            var shorts = ShortArray(1 shl 14)
            var floats = FloatArray(1 shl 14)
            var inputDone = false
            var outputDone = false
            var idlePolls = 0
            val deadline = SystemClock.elapsedRealtime() + DECODE_DEADLINE_MS

            while (!outputDone && stillActive() && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        val read = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        if (read < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    idlePolls = 0
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        outBuffer.order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val view = outBuffer.asFloatBuffer()
                            val n = view.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            if (shorts.size < n) shorts = ShortArray(n)
                            view.get(floats, 0, n)
                            for (i in 0 until n) shorts[i] = (floats[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                            sink.add(shorts, n)
                        } else {
                            val view = outBuffer.asShortBuffer()
                            val n = view.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            view.get(shorts, 0, n)
                            sink.add(shorts, n)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idlePolls > MAX_IDLE_POLLS) break
                }

                if (sink.size.toDouble() >= MAX_DECODED_SECONDS * sampleRate * channels) break
            }

            if (sink.size == 0 || channels <= 0 || sampleRate <= 0) return null
            return DecodedAudio(DiagnosticsDsp.downmixToMono(sink.toArray(), channels), sampleRate, channels)
        } catch (e: Exception) {
            Log.w(TAG, "Audio decode failed for $path: ${e.message}")
            return null
        } finally {
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }
    }

    /**
     * Measures one exported file the way a cell of the audio matrix needs: is there an audio track, how long is the
     * file (container duration, falling back to the decoded track), and how strong are the three fixture tones over the
     * whole track and the 1 kHz burst inside and outside the cue windows. [cueCentersSec] are the burst centres
     * ([DiagnosticsFixtures.sfxWindowCentersSec]); an empty list gives zero windowed levels.
     *
     * A file with no audio, or whose audio cannot be decoded, still returns metrics (all levels 0), so the verdict
     * names the missing audio instead of the check crashing.
     */
    fun analyzeAudio(
        path: String,
        expectedTotalSec: Double,
        cueCentersSec: List<Double>,
        stillActive: () -> Boolean = { true }
    ): AudioAnalysis {
        val hasTrack = hasAudioTrack(path)
        val container = containerDurationSec(path)
        val decoded = if (hasTrack) decodeAudio(path, stillActive) else null
        if (decoded == null) {
            return AudioAnalysis(
                AudioMetrics(hasTrack, container, expectedTotalSec, 0.0, 0.0, 0.0, 0.0, 0.0),
                trackDurationSec = 0.0, sampleRate = 0, channels = 0
            )
        }
        val mono = decoded.mono
        val rate = decoded.sampleRate
        fun level(hz: Double): Double = DiagnosticsDsp.goertzelAmplitude(mono, 0, mono.size, rate, hz)
        val bursts = DiagnosticsDsp.toneInWindows(mono, rate, DiagnosticsFixtures.SFX_HZ, cueCentersSec)
        val metrics = AudioMetrics(
            hasAudioTrack = true,
            durationSec = if (container > 0.0) container else decoded.durationSec,
            expectedDurationSec = expectedTotalSec,
            narrationAmplitude = level(DiagnosticsFixtures.NARRATION_HZ),
            musicAmplitude = level(DiagnosticsFixtures.MUSIC_HZ),
            sfxAmplitude = level(DiagnosticsFixtures.SFX_HZ),
            sfxCueMin = bursts.insideMin,
            sfxOutside = bursts.outside
        )
        return AudioAnalysis(metrics, decoded.durationSec, rate, decoded.channels)
    }

    // ------------------------------------------------------------------------------------------------ frames

    /**
     * The frames of [path] nearest each of [timesSec] (`OPTION_CLOSEST`, so not the preceding key frame), in order;
     * null for a time whose frame could not be read. One retriever serves all of them.
     */
    fun extractFrames(path: String, timesSec: List<Double>): List<Frame?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
        } catch (e: Exception) {
            Log.w(TAG, "Video unreadable for $path: ${e.message}")
            runCatching { retriever.release() }
            return timesSec.map { null }
        }
        try {
            return timesSec.map { t ->
                try {
                    val bitmap = retriever.getFrameAtTime((t * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap == null) {
                        null
                    } else {
                        val w = bitmap.width
                        val h = bitmap.height
                        val pixels = IntArray(w * h)
                        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                        bitmap.recycle()
                        Frame(w, h, pixels)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame at $t s unreadable for $path: ${e.message}")
                    null
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun toGray(frame: Frame): GrayImage = GrayImage.fromArgb(frame.pixels, frame.width, frame.height)

    /**
     * Compares the Canvas and the GLES export of the visual fixture at [timesSec], one [FrameMetrics] per time. The
     * figure and text boxes are found in the Canvas frame by colour ([DiagnosticsFrames]) and applied to both. A frame
     * that cannot be read, or in which the figure or the text cannot be found, gives all-NaN metrics, which
     * [GlesAcceptance.evaluate] always fails (a missing measurement is never a pass).
     */
    fun compareVisual(canvasPath: String, glesPath: String, timesSec: List<Double>): List<FrameMetrics> {
        val canvasFrames = extractFrames(canvasPath, timesSec)
        val glesFrames = extractFrames(glesPath, timesSec)
        return timesSec.indices.map { i ->
            val canvas = canvasFrames[i]
            val gles = glesFrames[i]
            val figure = canvas?.let { DiagnosticsFrames.figureBox(it.pixels, it.width, it.height) }
            val text = canvas?.let { DiagnosticsFrames.textBox(it.pixels, it.width, it.height) }
            if (canvas == null || gles == null || figure == null || text == null) {
                FrameMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            } else {
                DiagnosticsDsp.compareFrames(
                    canvas = toGray(canvas),
                    gles = toGray(gles),
                    figureBox = figure,
                    textBox = text,
                    mouthBox = DiagnosticsFrames.mouthRegion(figure)
                )
            }
        }
    }
}
