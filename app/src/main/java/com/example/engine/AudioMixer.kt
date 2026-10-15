package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException

/**
 * Decodes narration, background music and sound effects to raw PCM, mixes them at their configured volumes, and
 * re-encodes the result to AAC as a single track ready for [android.media.MediaMuxer].
 *
 * [ExportAudio] calls this when the export has background music and/or sound-effect cues (see
 * [ExportAudioPlan.decideMode]); a narration-only export keeps the verbatim-copy path instead.
 *
 * Everything is synchronous and blocking, like [VideoExporter]'s video encoder loop, and bounded (WP1.2):
 *  - every MediaCodec loop has a no-progress watchdog (about 5 s) and a cancellation check, so a stalled codec
 *    fails the mix, which [ExportAudio] turns into a warning and a fallback to the narration, instead of hanging the
 *    export;
 *  - decoding follows the decoder's actual output format (sample rate, channels and PCM encoding: 16-bit, 8-bit or
 *    float), not the input track's header, and an input that fails to decode becomes a warning in [MixResult]
 *    instead of a silent gap;
 *  - the final sum is soft-limited by [AudioMath.limitToShort]: bit-identical to a plain sum up to 0.89 of full
 *    scale, shaped instead of hard-clipped above it.
 *
 * Memory: each input's full PCM is held once (about 176 KB per second at 44.1 kHz stereo 16-bit); the mixed output is
 * produced and encoded in small chunks, so it never exists in full.
 *
 * The MediaCodec plumbing here is compiled by CI only and has not been verified on a device. The decisions and the
 * arithmetic it relies on ([AudioMath], [ExportAudioPlan]) are unit-tested on the JVM.
 */
object AudioMixer {

    private const val TAG = "AudioMixer"

    /** Target format every input is converted to before mixing. AAC encoders reliably support this combination. */
    private const val MIX_SAMPLE_RATE = 44100
    private const val MIX_CHANNELS = 2
    private const val AAC_BITRATE = 128_000

    data class PcmAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int)
    data class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)
    data class EncodedAudioTrack(val format: MediaFormat, val samples: List<EncodedSample>)

    /** The mixed track (null when nothing could be mixed) and what was left out, in plain words (see [ExportAudio]). */
    data class MixResult(val track: EncodedAudioTrack?, val warnings: List<String>)

    /** No progress at all from a MediaCodec for this long means it is stalled. */
    private const val WATCHDOG_MS = 5_000L

    private fun checkActive(stillActive: () -> Boolean) {
        if (!stillActive()) throw CancellationException("Audio mixing cancelled")
    }

    private fun pcmEncodingOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioMath.ENCODING_PCM_16BIT

    /** A single sound-effect trigger resolved to an actual file, ready to mix. Timestamps beyond [buildMixedTrack]'s totalSec are simply clipped, same as any input running past the video's length. */
    data class SoundEffectTrigger(val timeSec: Float, val filePath: String, val volume: Float)

    /**
     * Full pipeline: decode the inputs (any may be null or undecodable), convert them to
     * [MIX_SAMPLE_RATE]/[MIX_CHANNELS], mix at the given volumes over [totalSec], and AAC-encode the result.
     * The track is null when nothing could be decoded; [MixResult.warnings] names every input that was left out.
     * [stillActive] is polled in every loop; when it turns false a [CancellationException] is thrown.
     */
    fun buildMixedTrack(
        narrationPath: String?,
        musicPath: String?,
        narrationVolume: Float,
        musicVolume: Float,
        loopMusic: Boolean,
        totalSec: Float,
        soundEffects: List<SoundEffectTrigger> = emptyList(),
        stillActive: () -> Boolean = { true }
    ): MixResult {
        val warnings = ArrayList<String>()

        fun decodeOrWarn(what: String, path: String): PcmAudio? {
            checkActive(stillActive)
            return try {
                decodeToPcm(path, stillActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode $what for mixing: $path", e)
                warnings += "The $what could not be decoded (${ExportAudioPlan.reasonOf(e)}) and was left out of the mix."
                null
            }
        }

        val narrationPcm = narrationPath?.let { decodeOrWarn("narration", it) }
        val musicPcm = musicPath?.let { decodeOrWarn("background music", it) }

        val narrationConverted = narrationPcm?.let { convert(it, MIX_SAMPLE_RATE, MIX_CHANNELS) }
        val musicConverted = musicPcm?.let { convert(it, MIX_SAMPLE_RATE, MIX_CHANNELS) }

        // Sound-effect clips are short; each distinct file is decoded and converted once (a clip used by many cues
        // used to be decoded once per cue), and a file that fails is reported once.
        val decodedClips = HashMap<String, ShortArray?>()
        val convertedEffects = ArrayList<Pair<SoundEffectTrigger, ShortArray>>()
        for (trigger in soundEffects) {
            val clip = if (decodedClips.containsKey(trigger.filePath)) {
                decodedClips[trigger.filePath]
            } else {
                val name = trigger.filePath.substringAfterLast('/')
                val converted = decodeOrWarn("sound effect '$name'", trigger.filePath)
                    ?.let { convert(it, MIX_SAMPLE_RATE, MIX_CHANNELS) }
                decodedClips[trigger.filePath] = converted
                converted
            }
            if (clip != null) convertedEffects += trigger to clip
        }

        if (narrationConverted == null && musicConverted == null && convertedEffects.isEmpty()) {
            return MixResult(null, warnings)
        }

        val totalFrames = (totalSec * MIX_SAMPLE_RATE).toInt().coerceAtLeast(1)
        val track = encodeMixedStreaming(
            narration = narrationConverted, narrationVol = narrationVolume,
            music = musicConverted, musicVol = musicVolume,
            loopMusic = loopMusic, totalFrames = totalFrames, channels = MIX_CHANNELS,
            soundEffects = convertedEffects, stillActive = stillActive
        )
        return MixResult(track, warnings)
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    /**
     * Decodes any audio file MediaExtractor can open to raw 16-bit PCM, interleaved by channel. WAV/raw-PCM
     * sources ("audio/raw" track mime) are read directly with no codec involved; anything compressed goes through
     * a synchronous [MediaCodec] decode loop. The sample rate, channel count and PCM encoding are taken from the
     * decoder's OUTPUT format once it announces it (HE-AAC can double the rate, a decoder may output float), and
     * 8-bit and float PCM are converted to 16-bit by [AudioMath.PcmConverter].
     */
    private fun decodeToPcm(path: String, stillActive: () -> Boolean): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; format = f; break
                }
            }
            require(trackIdx >= 0 && format != null) { "No audio track found in $path" }
            extractor.selectTrack(trackIdx)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime       = format.getString(MediaFormat.KEY_MIME)!!

            val sink = AudioMath.ShortSink()
            val converter = AudioMath.PcmConverter(pcmEncodingOf(format))

            if (mime == "audio/raw") {
                require(AudioMath.bytesPerSample(converter.encoding) > 0) {
                    "Unsupported PCM encoding ${converter.encoding} in $path"
                }
                val buf = ByteBuffer.allocate(64 * 1024)
                val arr = ByteArray(64 * 1024)
                while (true) {
                    checkActive(stillActive)
                    buf.clear()
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) break
                    buf.get(arr, 0, sz)
                    converter.convert(arr, 0, sz, sink)
                    extractor.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    val info = MediaCodec.BufferInfo()
                    val watchdog = AudioMath.NoProgressWatchdog(WATCHDOG_MS)
                    var scratch = ByteArray(64 * 1024)
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone) {
                        checkActive(stillActive)
                        var progressed = false
                        if (!inputDone) {
                            val inIdx = decoder.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                progressed = true
                                val inBuf = decoder.getInputBuffer(inIdx)!!
                                val sz = extractor.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                progressed = true
                                val out = decoder.outputFormat
                                sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                converter.encoding = pcmEncodingOf(out)
                            }
                            outIdx >= 0 -> {
                                progressed = true
                                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                                    if (scratch.size < info.size) scratch = ByteArray(info.size)
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.get(scratch, 0, info.size)
                                    converter.convert(scratch, 0, info.size, sink)
                                }
                                val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                decoder.releaseOutputBuffer(outIdx, false)
                                if (isEos) outputDone = true
                            }
                            // INFO_TRY_AGAIN_LATER: nothing yet, loop again (the watchdog below bounds the wait).
                        }
                        if (progressed) {
                            watchdog.progress()
                        } else {
                            check(!watchdog.expired()) { "Audio decoder stalled: no progress for ${WATCHDOG_MS / 1000} s" }
                        }
                    }
                    decoder.stop()
                } finally {
                    runCatching { decoder.release() }
                }
            }
            return PcmAudio(sink.toArray(), sampleRate, channels)
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ── Resample / channel conversion ───────────────────────────────────────

    private fun convert(pcm: PcmAudio, dstRate: Int, dstChannels: Int): ShortArray {
        val channelConverted = convertChannels(pcm.samples, pcm.channels, dstChannels)
        return if (pcm.sampleRate == dstRate) channelConverted
        else resampleLinear(channelConverted, pcm.sampleRate, dstRate, dstChannels)
    }

    /** Mono↔stereo covers the realistic input space for this app; anything else falls back to a generic channel-index wrap. */
    private fun convertChannels(src: ShortArray, srcCh: Int, dstCh: Int): ShortArray {
        if (srcCh == dstCh || srcCh <= 0) return src
        val frames = src.size / srcCh
        val out = ShortArray(frames * dstCh)
        for (f in 0 until frames) {
            when {
                srcCh == 1 && dstCh == 2 -> {
                    val v = src[f]
                    out[f * 2] = v; out[f * 2 + 1] = v
                }
                srcCh == 2 && dstCh == 1 -> {
                    val l = src[f * 2].toInt(); val r = src[f * 2 + 1].toInt()
                    out[f] = ((l + r) / 2).toShort()
                }
                else -> for (c in 0 until dstCh) out[f * dstCh + c] = src[f * srcCh + (c % srcCh)]
            }
        }
        return out
    }

    /** Simple linear-interpolation resampler — adequate for background music/narration mixing; not intended as a high-fidelity DSP resampler. */
    private fun resampleLinear(src: ShortArray, srcRate: Int, dstRate: Int, channels: Int): ShortArray {
        if (srcRate == dstRate || channels <= 0) return src
        val srcFrames = src.size / channels
        if (srcFrames == 0) return ShortArray(0)
        val dstFrames = (srcFrames.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(dstFrames * channels)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until dstFrames) {
            val srcPosF = i * ratio
            val srcIdx = srcPosF.toInt().coerceIn(0, srcFrames - 1)
            val nextIdx = (srcIdx + 1).coerceAtMost(srcFrames - 1)
            val frac = (srcPosF - srcIdx).toFloat()
            for (c in 0 until channels) {
                val a = src[srcIdx * channels + c]
                val b = src[nextIdx * channels + c]
                out[i * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }

    // ── Streaming mix + overlay + encode ────────────────────────────────────

    /**
     * Fuses mix + sound-effect overlay + AAC encode into ONE chunked pass, so the full mixed output and an encoder
     * copy of it never exist (see the class doc comment for the memory picture). [narration] and [music] are held
     * fully in memory, read-only and sample-indexed. Below the soft limiter's knee the output is bit-identical to a
     * plain sum of the inputs (AudioMathTest.normalMixesKeepTheirLevelBitForBit); above it the sum is shaped, not clipped.
     *
     * Every wait on the encoder is bounded by a no-progress watchdog and checks [stillActive]; the encoder is always
     * released, also on failure.
     */
    private fun encodeMixedStreaming(
        narration: ShortArray?, narrationVol: Float,
        music: ShortArray?, musicVol: Float,
        loopMusic: Boolean, totalFrames: Int, channels: Int,
        soundEffects: List<Pair<SoundEffectTrigger, ShortArray>>,
        stillActive: () -> Boolean,
        sampleRate: Int = MIX_SAMPLE_RATE
    ): EncodedAudioTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Each effect's absolute start SAMPLE (not frame), resolved once.
            val effects = soundEffects.map { (trigger, clip) ->
                val startSample = (trigger.timeSec * sampleRate).toInt().coerceAtLeast(0) * channels
                AudioMath.PlacedEffect(startSample, clip, trigger.volume)
            }

            val chunkFrames = 4096
            val bytesPerFrame = 2 * channels
            val usPerFrame = 1_000_000.0 / sampleRate

            val outSamples = ArrayList<EncodedSample>()
            var outputFormat = format
            val info = MediaCodec.BufferInfo()

            fun drain(blockForEos: Boolean) {
                val drainWatchdog = AudioMath.NoProgressWatchdog(WATCHDOG_MS)
                while (true) {
                    checkActive(stillActive)
                    val outIdx = encoder.dequeueOutputBuffer(info, if (blockForEos) 10_000L else 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!blockForEos) return
                            // Finishing: keep waiting for the end-of-stream buffer, but not forever.
                            check(!drainWatchdog.expired()) {
                                "Audio encoder gave no output for ${WATCHDOG_MS / 1000} s while finishing"
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = encoder.outputFormat
                            drainWatchdog.progress()
                        }
                        outIdx >= 0 -> {
                            drainWatchdog.progress()
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig && info.size > 0) {
                                val buf = encoder.getOutputBuffer(outIdx)!!
                                val arr = ByteArray(info.size)
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                buf.get(arr)
                                // EOS flag stripped here: it only has meaning between this encoder and its own
                                // MediaCodec queue, not to MediaMuxer, which receives these samples later.
                                outSamples += EncodedSample(arr, info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                            }
                            val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (isEos) return
                        }
                        else -> {
                            if (!blockForEos) return
                            check(!drainWatchdog.expired()) {
                                "Audio encoder gave no output for ${WATCHDOG_MS / 1000} s while finishing"
                            }
                        }
                    }
                }
            }

            // Reused across chunks: allocated once, not once per chunk.
            val chunkShorts = ShortArray(chunkFrames * channels)
            val scratch = IntArray(chunkFrames * channels)
            val chunkBytes = ByteArray(chunkFrames * channels * 2)
            val inputWatchdog = AudioMath.NoProgressWatchdog(WATCHDOG_MS)

            var frame = 0
            var presentationTimeUs = 0L
            while (frame < totalFrames) {
                checkActive(stillActive)
                val thisChunkFrames = minOf(chunkFrames, totalFrames - frame)
                val chunkStartSample = frame * channels
                val chunkSamples = thisChunkFrames * channels

                AudioMath.mixChunk(
                    chunkShorts, scratch, chunkStartSample, chunkSamples,
                    narration, narrationVol, music, musicVol, loopMusic, effects
                )

                val chunkByteSize = chunkSamples * 2
                ByteBuffer.wrap(chunkBytes, 0, chunkByteSize).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(chunkShorts, 0, chunkSamples)

                var offset = 0
                while (offset < chunkByteSize) {
                    checkActive(stillActive)
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val size = minOf(16384, chunkByteSize - offset) // KEY_MAX_INPUT_SIZE cap
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.put(chunkBytes, offset, size)
                        encoder.queueInputBuffer(inIdx, 0, size, presentationTimeUs, 0)
                        presentationTimeUs += ((size / bytesPerFrame) * usPerFrame).toLong()
                        offset += size
                        inputWatchdog.progress()
                    } else {
                        check(!inputWatchdog.expired()) { "Audio encoder accepted no input for ${WATCHDOG_MS / 1000} s" }
                    }
                    drain(false)
                }

                frame += thisChunkFrames
            }

            var eosQueued = false
            inputWatchdog.progress()
            while (!eosQueued) {
                checkActive(stillActive)
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    encoder.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    check(!inputWatchdog.expired()) { "Audio encoder accepted no input for ${WATCHDOG_MS / 1000} s" }
                }
                drain(false)
            }
            drain(true)

            encoder.stop()
            return EncodedAudioTrack(outputFormat, outSamples)
        } finally {
            runCatching { encoder.release() }
        }
    }
}
