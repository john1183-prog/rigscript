package com.example.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Pure audio helpers shared by the mixer and the preview player (WP1.2). No Android types, so every function is
 * unit-tested on the JVM; [AudioMixer] supplies the MediaCodec plumbing around them.
 */
object AudioMath {

    // ---- volume rule -------------------------------------------------------------------------------------------

    /**
     * Volume of one sound-effect cue: the clip's volume times the cue's multiplier, clamped to 0..1 (NaN counts as 0).
     * The preview player and the export mixer both use this, so what is heard in the preview is what is exported.
     */
    fun effectiveSfxVolume(clipVolume: Float, cueMultiplier: Float): Float {
        val v = clipVolume * cueMultiplier
        return if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
    }

    // ---- soft limiter ------------------------------------------------------------------------------------------

    private const val FULL_SCALE = 32768f

    /** Normalised level where the limiter starts to act; below it the signal passes unchanged. */
    const val LIMITER_KNEE = 0.89f

    /**
     * Soft limiter on a normalised sample (1.0 = full scale): identity up to [LIMITER_KNEE], above it a tanh shoulder
     * that starts with the same slope (so there is no corner), rises monotonically and stays at or below 1.0.
     * NaN becomes 0; infinities become +-1.
     */
    fun softLimit(x: Float): Float {
        if (x.isNaN()) return 0f
        val a = abs(x)
        if (a <= LIMITER_KNEE) return x
        val room = 1f - LIMITER_KNEE
        val limited = LIMITER_KNEE + room * tanh(((a - LIMITER_KNEE) / room).toDouble()).toFloat()
        return if (x < 0f) -limited else limited
    }

    /**
     * A mixed sample sum on the 16-bit scale (it may exceed it) to a 16-bit sample: exactly the sum up to the knee,
     * soft-limited above it. Normal mixes therefore keep their level bit for bit.
     */
    fun limitToShort(sum: Int): Short {
        val knee = (LIMITER_KNEE * FULL_SCALE).toInt()
        if (sum in -knee..knee) return sum.toShort()
        val y = softLimit(sum / FULL_SCALE)
        return (y * FULL_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    // ---- PCM conversion ----------------------------------------------------------------------------------------

    // The values of android.media.AudioFormat.ENCODING_PCM_*; named here so this file needs no Android import.
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_FLOAT = 4

    /** Bytes per sample of a PCM encoding, or 0 when it is not supported. */
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENCODING_PCM_16BIT -> 2
        ENCODING_PCM_8BIT -> 1
        ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * Converts [length] bytes of little-endian PCM starting at [offset] to 16-bit samples: 16-bit passes through,
     * unsigned 8-bit is centred and scaled, float is scaled, rounded and clamped (NaN becomes 0). A trailing partial
     * sample is ignored. Throws for an unsupported [encoding].
     */
    fun pcmToShorts(src: ByteArray, offset: Int, length: Int, encoding: Int): ShortArray {
        val bps = bytesPerSample(encoding)
        require(bps > 0) { "Unsupported PCM encoding $encoding" }
        val n = length / bps
        val out = ShortArray(n)
        var p = offset
        when (encoding) {
            ENCODING_PCM_16BIT -> for (i in 0 until n) {
                out[i] = ((src[p + 1].toInt() shl 8) or (src[p].toInt() and 0xFF)).toShort()
                p += 2
            }
            ENCODING_PCM_8BIT -> for (i in 0 until n) {
                out[i] = (((src[p].toInt() and 0xFF) - 128) shl 8).toShort()
                p += 1
            }
            else -> for (i in 0 until n) {
                val bits = (src[p + 3].toInt() shl 24) or ((src[p + 2].toInt() and 0xFF) shl 16) or
                    ((src[p + 1].toInt() and 0xFF) shl 8) or (src[p].toInt() and 0xFF)
                val f = Float.fromBits(bits)
                val v = if (f.isNaN()) 0f else f.coerceIn(-1f, 1f)
                out[i] = (v * FULL_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()
                p += 4
            }
        }
        return out
    }

    /** A growable list of 16-bit samples (no boxing). */
    class ShortSink(initialCapacity: Int = 1 shl 16) {
        private var data = ShortArray(maxOf(16, initialCapacity))
        var size = 0
            private set

        fun append(src: ShortArray, count: Int = src.size) {
            if (size + count > data.size) data = data.copyOf(maxOf(size + count, data.size * 2))
            System.arraycopy(src, 0, data, size, count)
            size += count
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    /**
     * Streams PCM bytes of one encoding into a [ShortSink]; a sample split across two calls is carried over, so the
     * data may be fed in any chunking. [encoding] may be changed when a decoder announces a new output format.
     */
    class PcmConverter(var encoding: Int) {
        private val carry = ByteArray(4)
        private var carryLen = 0

        fun convert(src: ByteArray, offset: Int, length: Int, sink: ShortSink) {
            val bps = bytesPerSample(encoding)
            require(bps > 0) { "Unsupported PCM encoding $encoding" }
            var pos = offset
            var remaining = length
            if (carryLen > 0) {
                while (carryLen < bps && remaining > 0) {
                    carry[carryLen++] = src[pos++]
                    remaining--
                }
                if (carryLen < bps) return
                sink.append(pcmToShorts(carry, 0, bps, encoding))
                carryLen = 0
            }
            val whole = remaining / bps * bps
            if (whole > 0) sink.append(pcmToShorts(src, pos, whole, encoding))
            val rest = remaining - whole
            for (k in 0 until rest) carry[k] = src[pos + whole + k]
            carryLen = rest
        }
    }

    // ---- watchdog ----------------------------------------------------------------------------------------------

    /** Reports `expired()` once nothing has progressed for [limitMillis]; the clock is injectable for tests. */
    class NoProgressWatchdog(
        private val limitMillis: Long,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ) {
        private var last = clock()
        fun progress() { last = clock() }
        fun expired(): Boolean = clock() - last >= limitMillis
    }

    // ---- mixing ------------------------------------------------------------------------------------------------

    /** A sound effect placed on the mix timeline; [startSample] counts interleaved samples from the start of the mix. */
    class PlacedEffect(val startSample: Int, val samples: ShortArray, val volume: Float)

    /**
     * Mixes narration, music and sound effects for the interleaved samples [chunkStartSample, chunkStartSample +
     * chunkSamples) into [out] (scratch is an Int buffer of at least [chunkSamples]). The sum is formed first and
     * limited once at the end ([limitToShort]), so a loud mix is shaped softly instead of being clipped twice.
     */
    fun mixChunk(
        out: ShortArray,
        scratch: IntArray,
        chunkStartSample: Int,
        chunkSamples: Int,
        narration: ShortArray?, narrationVol: Float,
        music: ShortArray?, musicVol: Float, loopMusic: Boolean,
        effects: List<PlacedEffect>
    ) {
        for (j in 0 until chunkSamples) {
            val i = chunkStartSample + j
            var sample = 0
            if (narration != null && i < narration.size) sample += (narration[i] * narrationVol).toInt()
            if (music != null && music.isNotEmpty()) {
                val idx = if (loopMusic) i % music.size else i
                if (idx < music.size) sample += (music[idx] * musicVol).toInt()
            }
            scratch[j] = sample
        }
        val end = chunkStartSample + chunkSamples
        for (fx in effects) {
            if (fx.startSample + fx.samples.size <= chunkStartSample || fx.startSample >= end) continue
            for (j in 0 until chunkSamples) {
                val clipIdx = chunkStartSample + j - fx.startSample
                if (clipIdx in fx.samples.indices) scratch[j] += (fx.samples[clipIdx] * fx.volume).toInt()
            }
        }
        for (j in 0 until chunkSamples) out[j] = limitToShort(scratch[j])
    }
}
