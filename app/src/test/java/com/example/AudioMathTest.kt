package com.example

import com.example.engine.AudioMath
import com.example.engine.AudioMath.PlacedEffect
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.2: the pure parts of the audio hardening. */
class AudioMathTest {

    @Test
    fun sfxVolumeIsTheProductClampedToUnitRange() {
        assertEquals(0.25f, AudioMath.effectiveSfxVolume(0.5f, 0.5f), 1e-6f)
        assertEquals(1f, AudioMath.effectiveSfxVolume(1f, 1.5f), 0f)
        assertEquals(1f, AudioMath.effectiveSfxVolume(2f, 3f), 0f)
        assertEquals(0f, AudioMath.effectiveSfxVolume(0.8f, -1f), 0f)
        assertEquals(0f, AudioMath.effectiveSfxVolume(Float.NaN, 1f), 0f)
        assertEquals(0f, AudioMath.effectiveSfxVolume(0f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, AudioMath.effectiveSfxVolume(1f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun limiterIsTheIdentityBelowTheKnee() {
        var x = -AudioMath.LIMITER_KNEE
        while (x <= AudioMath.LIMITER_KNEE) {
            assertEquals(x, AudioMath.softLimit(x), 0f)
            x += 0.01f
        }
        for (sum in -29163..29163 step 7) assertEquals(sum, AudioMath.limitToShort(sum).toInt())
    }

    @Test
    fun limiterIsBoundedMonotonicSymmetricAndContinuous() {
        var prev = AudioMath.softLimit(-6f)
        var x = -6f
        while (x <= 6f) {
            val y = AudioMath.softLimit(x)
            assertTrue("x=$x y=$y", abs(y) <= 1f)
            assertTrue("monotonic at $x: $prev then $y", y >= prev)
            assertEquals("odd symmetry at $x", -y, AudioMath.softLimit(-x), 1e-6f)
            prev = y
            x += 0.001f
        }
        val k = AudioMath.LIMITER_KNEE
        assertEquals("no step at the knee", k, AudioMath.softLimit(k + 1e-4f), 2e-4f)
        // The shoulder starts with slope 1, like the identity below it.
        assertEquals(1f, (AudioMath.softLimit(k + 2e-3f) - AudioMath.softLimit(k + 1e-3f)) / 1e-3f, 0.02f)
        assertTrue(AudioMath.softLimit(1.5f) in 0.99f..1f)
    }

    @Test
    fun limiterNeverProducesNaNOrLeavesTheShortRange() {
        assertEquals(0f, AudioMath.softLimit(Float.NaN), 0f)
        assertEquals(1f, AudioMath.softLimit(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-1f, AudioMath.softLimit(Float.NEGATIVE_INFINITY), 0f)
        var prev = AudioMath.limitToShort(-200_000).toInt()
        for (sum in -200_000..200_000 step 13) {
            val s = AudioMath.limitToShort(sum).toInt()
            assertTrue("short range at $sum", s in -32768..32767)
            assertTrue("monotonic at $sum", s >= prev)
            prev = s
        }
        assertEquals(32767, AudioMath.limitToShort(10_000_000).toInt())
        assertEquals(-32768, AudioMath.limitToShort(-10_000_000).toInt())
    }

    @Test
    fun sixteenBitPcmPassesThroughLittleEndian() {
        val bytes = byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        assertArrayEquals(shortArrayOf(0x1234, -1, -32768, 32767), AudioMath.pcmToShorts(bytes, 0, 8, 2))
        // offset and an odd trailing byte
        assertArrayEquals(shortArrayOf(-1), AudioMath.pcmToShorts(bytes, 2, 3, 2))
    }

    @Test
    fun eightBitPcmIsUnsignedCentredAndScaled() {
        val bytes = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0xC0.toByte())
        assertArrayEquals(shortArrayOf(-32768, 0, 32512, 16384), AudioMath.pcmToShorts(bytes, 0, 4, 3))
    }

    private fun floatBytes(vararg v: Float): ByteArray {
        val out = ByteArray(v.size * 4)
        for ((i, f) in v.withIndex()) {
            val b = f.toRawBits()
            for (k in 0 until 4) out[i * 4 + k] = (b ushr (8 * k)).toByte()
        }
        return out
    }

    @Test
    fun floatPcmIsScaledRoundedAndClamped() {
        val bytes = floatBytes(0f, 0.5f, -0.5f, 1f, -1f, 2f, -3f, Float.NaN)
        assertArrayEquals(
            shortArrayOf(0, 16384, -16384, 32767, -32768, 32767, -32768, 0),
            AudioMath.pcmToShorts(bytes, 0, bytes.size, 4)
        )
    }

    @Test
    fun anUnsupportedEncodingIsRejected() {
        var threw = false
        try { AudioMath.pcmToShorts(ByteArray(8), 0, 8, 99) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        assertEquals(0, AudioMath.bytesPerSample(99))
    }

    @Test
    fun theConverterGivesTheSameResultForAnyChunking() {
        for (encoding in listOf(2, 3, 4)) {
            val bps = AudioMath.bytesPerSample(encoding)
            val data = ByteArray(bps * 41) { ((it * 37 + 11) % 251).toByte() }
            val whole = AudioMath.ShortSink().also { AudioMath.PcmConverter(encoding).convert(data, 0, data.size, it) }.toArray()
            for (chunk in listOf(1, 2, 3, 5, 7, 16)) {
                val sink = AudioMath.ShortSink(16)
                val c = AudioMath.PcmConverter(encoding)
                var p = 0
                while (p < data.size) {
                    val n = minOf(chunk, data.size - p)
                    c.convert(data, p, n, sink)
                    p += n
                }
                assertArrayEquals("encoding $encoding chunk $chunk", whole, sink.toArray())
            }
        }
    }

    @Test
    fun theSinkGrowsAndKeepsOrder() {
        val sink = AudioMath.ShortSink(16)
        for (round in 0 until 50) sink.append(ShortArray(100) { (round * 100 + it).toShort() })
        assertEquals(5000, sink.size)
        assertEquals(4999.toShort(), sink.toArray()[4999])
        assertEquals(1234.toShort(), sink.toArray()[1234])
    }

    @Test
    fun theWatchdogExpiresOnlyAfterTheLimitWithoutProgress() {
        var now = 0L
        val wd = AudioMath.NoProgressWatchdog(5_000L) { now }
        assertFalse(wd.expired())
        now = 4_999L
        assertFalse(wd.expired())
        wd.progress()
        now = 9_000L
        assertFalse("progress at 4999 restarts the clock", wd.expired())
        now = 9_999L
        assertTrue(wd.expired())
    }

    /** The mix as the mixer computed it before WP1.2 (hard clip twice), valid whenever nothing clips. */
    private fun referenceMix(
        i: Int, narration: ShortArray?, nv: Float, music: ShortArray?, mv: Float, loop: Boolean, fx: List<PlacedEffect>
    ): Int {
        var sample = 0
        if (narration != null && i < narration.size) sample += (narration[i] * nv).toInt()
        if (music != null && music.isNotEmpty()) {
            val idx = if (loop) i % music.size else i
            if (idx < music.size) sample += (music[idx] * mv).toInt()
        }
        var v = sample.coerceIn(-32768, 32767)
        for (e in fx) {
            val k = i - e.startSample
            if (k in e.samples.indices) v = (v + (e.samples[k] * e.volume).toInt()).coerceIn(-32768, 32767)
        }
        return v
    }

    @Test
    fun normalMixesKeepTheirLevelBitForBit() {
        val narration = ShortArray(3000) { ((it * 31) % 9000 - 4500).toShort() }
        val music = ShortArray(700) { ((it * 17) % 6000 - 3000).toShort() }
        val fx = listOf(
            PlacedEffect(500, ShortArray(900) { ((it * 13) % 5000 - 2500).toShort() }, 0.8f),
            PlacedEffect(1800, ShortArray(1500) { ((it * 7) % 4000 - 2000).toShort() }, 1f)
        )
        val out = ShortArray(1024)
        val scratch = IntArray(1024)
        var worst = 0
        for (start in 0 until 3200 step 1024) {
            val n = minOf(1024, 3200 - start)
            AudioMath.mixChunk(out, scratch, start, n, narration, 1f, music, 0.35f, true, fx)
            for (j in 0 until n) {
                val ref = referenceMix(start + j, narration, 1f, music, 0.35f, true, fx)
                assertTrue("peak stays under the knee at ${start + j}: $ref", abs(ref) <= 29163)
                worst = maxOf(worst, abs(out[j] - ref))
            }
        }
        assertEquals("identical to the old mix", 0, worst)
    }

    @Test
    fun aLoudMixIsLimitedSoftlyInsteadOfClipped() {
        // Narration plus music summing to 36000 (1.10 of full scale): a hard clip would give 32767, the limiter
        // shapes it to about 32610 and keeps the sign.
        val a = ShortArray(2000) { if (it % 40 < 20) 18000 else -18000 }
        val out = ShortArray(2000)
        AudioMath.mixChunk(out, IntArray(2000), 0, 2000, a, 1f, a, 1f, true, emptyList())
        assertTrue("shaped below the hard clip: ${out.maxOf { abs(it.toInt()) }}", out.all { abs(it.toInt()) in 32000..32700 })
        assertTrue("sign is kept", (0 until 2000).all { (out[it] > 0) == (a[it] > 0) })

        // Far above full scale the output sits at the ceiling and never wraps around.
        val b = ShortArray(2000) { if (it % 40 < 20) 30000 else -30000 }
        AudioMath.mixChunk(out, IntArray(2000), 0, 2000, b, 1f, b, 1f, true, emptyList())
        assertTrue("at the ceiling", out.all { abs(it.toInt()) in 32700..32768 })
        assertTrue("sign is kept", (0 until 2000).all { (out[it] > 0) == (b[it] > 0) })
    }
}
