package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP0.4: the comparator itself works, and the CI transport of snapshots round-trips. */
class GoldenCompareTest {

    private val sample = "golden 1 fixture=x\ncp t=1.0000\nang 1.2000 -3.5000 0.0000\n" +
        "st mouth=1 expr=0 eye=1.0000\ncaption Hello 2.5 world\n"

    @Test
    fun identicalTextHasNoMismatches() {
        assertTrue(GoldenCompare.compare(sample, sample).isEmpty())
    }

    @Test
    fun numbersWithinTheToleranceMatch() {
        val near = sample.replace("1.2000", "1.2005")
        assertTrue(GoldenCompare.compare(sample, near).isEmpty())
    }

    @Test
    fun numbersBeyondTheToleranceAreReported() {
        val far = sample.replace("1.2000", "1.2020")
        val diffs = GoldenCompare.compare(sample, far)
        assertEquals(diffs.toString(), 1, diffs.size)
        assertTrue(diffs[0], diffs[0].contains("line 3"))
    }

    @Test
    fun changedTextAndIntegersAreReported() {
        assertEquals(1, GoldenCompare.compare(sample, sample.replace("expr=0", "expr=1")).size)
        assertEquals(1, GoldenCompare.compare(sample, sample.replace("Hello", "Howdy")).size)
    }

    @Test
    fun aDifferentLineCountIsReported() {
        assertFalse(GoldenCompare.compare(sample, sample + "extra 1.0000\n").isEmpty())
        assertFalse(GoldenCompare.compare(sample + "extra 1.0000\n", sample).isEmpty())
    }

    @Test
    fun aMissingOrNaNNumberIsReported() {
        assertFalse(GoldenCompare.compare(sample, sample.replace("-3.5000", "NaN")).isEmpty())
        assertFalse(GoldenCompare.compare(sample, sample.replace("-3.5000", "-")).isEmpty())
    }

    @Test
    fun aPerturbedCopyOfARealSnapshotIsCaughtBeyondTheToleranceOnly() {
        val real = GoldenSnapshot.build("01_minimal")
        assertTrue(GoldenCompare.compare(real, real).isEmpty())
        val lines = real.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("ang ") }
        assertTrue("an ang line exists", at >= 0)
        fun shifted(by: Double): String {
            val parts = lines[at].split(' ').toMutableList()
            parts[1] = GoldenSnapshot.num(parts[1].toDouble() + by)
            val copy = lines.toMutableList()
            copy[at] = parts.joinToString(" ")
            return copy.joinToString("\n")
        }
        assertTrue("0.0005 is within the tolerance", GoldenCompare.compare(real, shifted(0.0005)).isEmpty())
        assertEquals("0.01 is beyond the tolerance", 1, GoldenCompare.compare(real, shifted(0.01)).size)
    }

    @Test
    fun captureLinesRoundTripThroughGzipAndBase64() {
        val text = (0 until 6000).joinToString("\n") { i -> "line $i value=${GoldenSnapshot.num(i * 0.37)} id=abc_$i" }
        val lines = GoldenSnapshot.captureLines("sample", text)
        assertTrue(lines.all { it.startsWith("CAPTURE|golden:sample:") && it.length < 20100 })
        assertEquals(text, GoldenSnapshot.decodeCapture(lines))
        var x = 12345L
        val big = (0 until 40000).joinToString("") {
            x = x * 6364136223846793005L + 1442695040888963407L
            (((x ushr 33) % 94) + 33).toInt().toChar().toString()
        }
        val bigLines = GoldenSnapshot.captureLines("big", big)
        assertTrue("big text needs more than one chunk: ${bigLines.size}", bigLines.size > 1)
        assertEquals(big, GoldenSnapshot.decodeCapture(bigLines))
    }
}
