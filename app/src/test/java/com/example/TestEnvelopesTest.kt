package com.example

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP0.3: the synthetic envelopes are deterministic, bounded and shaped as documented. */
class TestEnvelopesTest {

    @Test
    fun lengthsFollowTheFrameRate() {
        assertEquals(300, TestEnvelopes.silence(10f).size)
        assertEquals(300, TestEnvelopes.speechLike(10f).size)
        assertEquals(300, TestEnvelopes.noisy(10f).size)
    }

    @Test
    fun silenceIsAllZeros() {
        assertTrue(TestEnvelopes.silence(5f).all { it == 0f })
    }

    @Test
    fun everyEnvelopeStaysInRange() {
        val all = listOf(TestEnvelopes.silence(20f), TestEnvelopes.speechLike(20f), TestEnvelopes.noisy(20f))
        for (env in all) {
            assertTrue(env.all { it >= 0f && it <= 1f })
        }
    }

    @Test
    fun speechHasPausesAndLoudSyllables() {
        val env = TestEnvelopes.speechLike(10f)
        assertTrue("exact-zero pause frames: ${env.count { it == 0f }}", env.count { it == 0f } > 30)
        assertTrue("peak: ${env.max()}", env.max() > 0.6f)
    }

    @Test
    fun noisyIsDeterministicAndDependsOnTheSeed() {
        assertArrayEquals(TestEnvelopes.noisy(5f, 1), TestEnvelopes.noisy(5f, 1), 0f)
        assertFalse(TestEnvelopes.noisy(5f, 1).contentEquals(TestEnvelopes.noisy(5f, 2)))
    }

    @Test
    fun noisyVariesAndNeverFallsSilent() {
        val env = TestEnvelopes.noisy(10f)
        val mean = env.average()
        val variance = env.map { (it - mean) * (it - mean) }.average()
        assertTrue("variance $variance", variance > 0.005)
        assertTrue(env.all { it > 0f })
    }
}
