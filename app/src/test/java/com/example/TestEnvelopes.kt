package com.example

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthetic amplitude envelopes for engine tests (WP0.3). Same shape as the app's own envelope: one
 * Float per frame at [FPS] frames per second, values in 0..1. Deterministic: no clock and no
 * java.util.Random; the only noise source is a fixed xorshift32.
 */
object TestEnvelopes {
    const val FPS = 30

    fun frameCount(seconds: Float): Int = (seconds * FPS).toInt()

    /** All zeros: narration that is silent, or a project with no audio. */
    fun silence(seconds: Float): FloatArray = FloatArray(frameCount(seconds))

    /**
     * Steady speech: syllables at about 4.2 Hz (half-wave rectified sine, so each syllable rises and
     * falls) inside words that last 1.9 s, with an exactly silent 0.5 s pause after each word.
     */
    fun speechLike(seconds: Float): FloatArray {
        val n = frameCount(seconds)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / FPS
            val inWord = (t % 2.4f) < 1.9f
            if (!inWord) continue
            val syllable = max(0.0, sin(2.0 * PI * 4.2 * t)).pow(1.5)
            out[i] = (0.15 + 0.7 * syllable).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    /** Noisy: seeded xorshift32 noise with a mean of about 0.22 and no silent stretches. */
    fun noisy(seconds: Float, seed: Int = 12345): FloatArray {
        val n = frameCount(seconds)
        val out = FloatArray(n)
        var state = if (seed == 0) 1 else seed
        for (i in 0 until n) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val u = (state ushr 8) / 16777216f
            out[i] = (0.02f + 0.6f * u * u).coerceIn(0f, 1f)
        }
        return out
    }
}
