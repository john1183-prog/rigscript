package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes narration + background music to raw PCM, mixes them at their
 * configured volumes, and re-encodes the result to AAC as a single track
 * ready to hand to [android.media.MediaMuxer].
 *
 * This is deliberately ONLY invoked by [VideoExporter] when
 * [com.example.data.BackgroundMusicSettings.musicFilePath] is actually set —
 * see that class's doc comment for why every other export keeps using the
 * fast verbatim-copy path instead of going through here.
 *
 * Everything here is synchronous/blocking (same convention as
 * [VideoExporter]'s video encoder loop) and holds the full mixed PCM buffer
 * in memory rather than streaming it — acceptable for this app's target
 * length (short-form narrated content, minutes not hours), but a real
 * memory-usage constraint worth knowing about if it's ever pointed at much
 * longer input: at 44.1kHz stereo 16-bit, roughly 176KB/second of audio.
 * NOT yet verified against an actual on-device build — this is the
 * highest-risk unverified piece added so far because it's the first code
 * path exercising the audio decode/encode side of MediaCodec at all
 * (everything before this only used MediaCodec for video).
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

    /**
     * Full pipeline: decode both inputs (either may be null/missing), convert
     * both to [MIX_SAMPLE_RATE]/[MIX_CHANNELS], mix at the given volumes over
     * [totalSec], and AAC-encode the result. Returns null if there's nothing
     * to mix (both inputs missing/undecodable) — caller should fall back to
     * the existing narration-only path in that case.
     */
    fun buildMixedTrack(
        narrationPath: String?,
        musicPath: String?,
        narrationVolume: Float,
        musicVolume: Float,
        loopMusic: Boolean,
        totalSec: Float
    ): EncodedAudioTrack? {
        val narrationPcm = narrationPath?.let { runCatching { decodeToPcm(it) }.getOrElse {
            Log.e(TAG, "Failed to decode narration for mixing: $it"); null
        } }
        val musicPcm = musicPath?.let { runCatching { decodeToPcm(it) }.getOrElse {
            Log.e(TAG, "Failed to decode background music: $it"); null
        } }
        if (narrationPcm == null && musicPcm == null) return null

        val narrationConverted = narrationPcm?.let { convert(it, MIX_SAMPLE_RATE, MIX_CHANNELS) }
        val musicConverted = musicPcm?.let { convert(it, MIX_SAMPLE_RATE, MIX_CHANNELS) }

        val totalFrames = (totalSec * MIX_SAMPLE_RATE).toInt().coerceAtLeast(1)
        val mixed = mix(
            narration = narrationConverted, narrationVol = narrationVolume,
            music = musicConverted, musicVol = musicVolume,
            loopMusic = loopMusic, totalFrames = totalFrames, channels = MIX_CHANNELS
        )
        return encodeAac(mixed, MIX_SAMPLE_RATE, MIX_CHANNELS)
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    /**
     * Decodes any audio file MediaExtractor can open to raw 16-bit PCM,
     * interleaved by channel. WAV/raw-PCM sources ("audio/raw" track mime)
     * are read directly with no codec involved; anything compressed
     * (MP3/AAC/etc) goes through a synchronous [MediaCodec] decode loop.
     */
    private fun decodeToPcm(path: String): PcmAudio {
        val extractor = MediaExtractor()
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

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime       = format.getString(MediaFormat.KEY_MIME)!!

        val pcmBytes = ByteArrayOutputStream()

        if (mime == "audio/raw") {
            val buf = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buf.clear()
                val sz = extractor.readSampleData(buf, 0)
                if (sz < 0) break
                val arr = ByteArray(sz)
                buf.get(arr, 0, sz)
                pcmBytes.write(arr)
                extractor.advance()
            }
        } else {
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
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
                if (outIdx >= 0) {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val arr = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(arr)
                        pcmBytes.write(arr)
                    }
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (isEos) outputDone = true
                }
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED: just loop again.
            }
            decoder.stop()
            decoder.release()
        }
        extractor.release()

        val bytes = pcmBytes.toByteArray()
        val shortBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuf.remaining())
        shortBuf.get(samples)
        return PcmAudio(samples, sampleRate, channels)
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

    // ── Mix ──────────────────────────────────────────────────────────────────

    /**
     * Sample-wise mix: `narration * narrationVol + music * musicVol`, clamped
     * to the 16-bit range. Neither input is normalized against the other —
     * see [com.example.data.BackgroundMusicSettings.volume]'s doc comment for
     * why that's a deliberate choice, not an oversight.
     */
    private fun mix(
        narration: ShortArray?, narrationVol: Float,
        music: ShortArray?, musicVol: Float,
        loopMusic: Boolean, totalFrames: Int, channels: Int
    ): ShortArray {
        val totalSamples = totalFrames * channels
        val out = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            var sample = 0
            if (narration != null && i < narration.size) {
                sample += (narration[i] * narrationVol).toInt()
            }
            if (music != null && music.isNotEmpty()) {
                val idx = if (loopMusic) i % music.size else i
                if (idx < music.size) sample += (music[idx] * musicVol).toInt()
            }
            out[i] = sample.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ── Encode ───────────────────────────────────────────────────────────────

    /** Synchronous AAC-LC encode of interleaved 16-bit PCM. Mirrors [VideoExporter]'s dequeue/drain pattern for its video encoder. */
    private fun encodeAac(pcm: ShortArray, sampleRate: Int, channels: Int): EncodedAudioTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val bytesPerFrame = 2 * channels
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)

        val chunkFrames = 4096
        val chunkBytes = chunkFrames * bytesPerFrame
        val usPerFrame = 1_000_000.0 / sampleRate

        val outSamples = ArrayList<EncodedSample>()
        var outputFormat = format
        val info = MediaCodec.BufferInfo()

        fun drain(blockForEos: Boolean) {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(info, if (blockForEos) 10_000L else 0L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                    outIdx >= 0 -> {
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && info.size > 0) {
                            val buf = encoder.getOutputBuffer(outIdx)!!
                            val arr = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.get(arr)
                            // EOS flag stripped here — it only has meaning between this
                            // encoder and its own MediaCodec queue, not to MediaMuxer,
                            // which VideoExporter writes these samples into later.
                            outSamples += EncodedSample(arr, info.presentationTimeUs,
                                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) return
                    }
                    else -> return
                }
            }
        }

        var offset = 0
        var presentationTimeUs = 0L
        while (offset < bytes.size) {
            val inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val size = minOf(chunkBytes, bytes.size - offset)
                val inBuf = encoder.getInputBuffer(inIdx)!!
                inBuf.clear()
                inBuf.put(bytes, offset, size)
                encoder.queueInputBuffer(inIdx, 0, size, presentationTimeUs, 0)
                presentationTimeUs += ((size / bytesPerFrame) * usPerFrame).toLong()
                offset += size
            }
            drain(false)
        }
        var eosQueued = false
        while (!eosQueued) {
            val inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                encoder.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosQueued = true
            }
            drain(false)
        }
        drain(true)

        encoder.stop()
        encoder.release()
        return EncodedAudioTrack(outputFormat, outSamples)
    }
}
