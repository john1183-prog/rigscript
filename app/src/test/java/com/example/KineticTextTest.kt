package com.example

import com.example.data.OverlayLayer
import com.example.engine.KineticText
import com.example.engine.OverlayResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3.1 kinetic text: unit splitting, the stagger rule, the per-unit enter in the shared resolver (pinned to
 * `tools/acting_proto/kinetic_proto.py`), and that block text is untouched (I1). Layout on a device (word boxes from
 * StaticLayout) cannot run on the JVM; see TEST_CHECKLIST.md.
 */
class KineticTextTest {

    private fun near(label: String, expected: Float, actual: Float, tol: Float = 2e-5f) =
        assertTrue("$label: expected $expected got $actual", Math.abs(expected - actual) <= tol)

    private fun ranges(text: String, mode: String): List<Pair<Int, Int>> {
        val r = KineticText.unitRanges(text, mode)
        return (0 until r.size / 2).map { r[2 * it] to r[2 * it + 1] }
    }

    @Test
    fun wordsAreRunsOfNonWhitespace() {
        assertEquals(listOf(0 to 1, 2 to 4, 6 to 9, 10 to 11), ranges("a bb  ccc\nd", "word"))
        assertEquals(listOf(2 to 4, 6 to 8), ranges("  hi  yo  ", "word"))
    }

    @Test
    fun charsSkipWhitespaceAndKeepSurrogatePairsWhole() {
        assertEquals(listOf(0 to 1, 1 to 2, 3 to 4, 4 to 5), ranges("ab cd", "char"))
        val s = "a\uD83D\uDE00 b"   // a, one emoji (two UTF-16 units), b
        assertEquals(listOf(0 to 1, 1 to 3, 4 to 5), ranges(s, "char"))
    }

    @Test
    fun blockUnknownTooFewOrTooManyUnitsMeanBlock() {
        assertTrue(KineticText.unitRanges("one two three", "block").isEmpty())
        assertTrue(KineticText.unitRanges("one two three", "karaoke").isEmpty())
        assertTrue(KineticText.unitRanges("solo", "word").isEmpty())
        assertTrue(KineticText.unitRanges("x", "char").isEmpty())
        val many = (1..KineticText.MAX_UNITS + 1).joinToString(" ") { "w" }
        assertTrue(KineticText.unitRanges(many, "word").isEmpty())
        // char falls back to word when there are too many characters but few words
        val longWords = "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz"
        assertEquals(2, KineticText.unitRanges(longWords, "char").size / 2)
    }

    @Test
    fun theStaggerIsSqueezedSoTheLastUnitLandsBeforeTheExit() {
        near("room for it", 0.12f, KineticText.effectiveStagger(0.12f, 6, 0.35f, 0.35f, 4f))
        val eff = KineticText.effectiveStagger(1f, 6, 0.35f, 0.35f, 2f)
        near("squeezed", (2f - 0.7f) / 5f, eff, 1e-6f)
        near("last unit done at exit start", 2f - 0.35f, 5 * eff + 0.35f, 1e-5f)
        near("no room", 0f, KineticText.effectiveStagger(0.5f, 4, 1f, 1f, 2f))
        near("negative stagger", 0f, KineticText.effectiveStagger(-1f, 4, 0.2f, 0.2f, 5f))
        near("one unit", 0f, KineticText.effectiveStagger(0.5f, 1, 0.2f, 0.2f, 5f))
    }

    private fun layer(style: String, ease: String, stagger: Float, enter: Float, exit: Float, window: Float, n: Int, mode: String = "word") =
        OverlayLayer(
            id = "k", type = "text", text = (1..n).joinToString(" ") { "w$it" }, startSec = 0f, endSec = window,
            enterStyle = style, enterEase = ease, enterDuration = enter, exitDuration = exit,
            textMode = mode, stagger = stagger
        )

    @Test
    fun theResolverMatchesThePythonPrototype() {
        for (row in REFERENCE) {
            val f = row.split(",")
            val l = layer(f[0], f[1], f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toInt())
            val t = f[7].toFloat()
            val units = OverlayResolver.resolveTimeBased(listOf(l), t).single().textUnits
            assertTrue("units present: $row", units != null)
            val u = units!![f[8].toInt()]
            near("opacity $row", f[9].toFloat(), u.opacity)
            near("scale $row", f[10].toFloat(), u.scale)
            near("offsetY $row", f[11].toFloat(), u.offsetY)
        }
    }

    @Test
    fun theLayerSkipsItsOwnEnterInAUnitModeAndKeepsItsExit() {
        val l = layer("fade", "linear", 0.2f, 0.4f, 0.4f, 4f, 5)
        val early = OverlayResolver.resolveTimeBased(listOf(l), 0.1f).single()
        near("layer opacity does not fade in", 1f, early.opacity)
        assertTrue(early.textUnits!![0].opacity < 1f && early.textUnits!![4].opacity == 0f)
        val late = OverlayResolver.resolveTimeBased(listOf(l), 3.8f).single()
        assertTrue("exit fades the layer", late.opacity < 1f)
        assertTrue("units are the identity at the exit", late.textUnits!!.all { it.opacity == 1f && it.scale == 1f && it.offsetY == 0f })
    }

    @Test
    fun blockTextHasNoUnitsAndKeepsItsLegacyEnter() {
        val block = layer("fade", "linear", 0.2f, 0.4f, 0.4f, 4f, 5, mode = "block")
        val r = OverlayResolver.resolveTimeBased(listOf(block), 0.1f).single()
        assertTrue(r.textUnits == null)
        near("legacy block fade", 0.25f, r.opacity)
        val unknown = block.copy(textMode = "karaoke")
        assertTrue(OverlayResolver.resolveTimeBased(listOf(unknown), 0.1f).single().textUnits == null)
        val single = block.copy(textMode = "word", text = "solo")
        assertTrue(OverlayResolver.resolveTimeBased(listOf(single), 0.1f).single().textUnits == null)
        val shape = OverlayLayer(id = "s", type = "shape", startSec = 0f, endSec = 4f, textMode = "word")
        assertTrue(OverlayResolver.resolveTimeBased(listOf(shape), 0.1f).single().textUnits == null)
    }

    @Test
    fun noneStyleRevealsTheUnitsOneByOne() {
        val l = layer("none", "linear", 0.5f, 0.3f, 0.3f, 6f, 4)
        fun visible(t: Float) = OverlayResolver.resolveTimeBased(listOf(l), t).single().textUnits!!.count { it.opacity > 0.5f }
        assertEquals(1, visible(0.1f)); assertEquals(2, visible(0.6f)); assertEquals(3, visible(1.1f)); assertEquals(4, visible(1.6f))
    }

    @Test
    fun theUnitsAreBoundedDeterministicAndTheirRangesCoverTheWords() {
        val l = layer("pop", "back", 0.1f, 0.3f, 0.3f, 3f, 7)
        var t = 0f
        while (t < 3f) {
            val a = OverlayResolver.resolveTimeBased(listOf(l), t).single().textUnits!!
            val b = OverlayResolver.resolveTimeBased(listOf(l), t).single().textUnits!!
            for (i in a.indices) {
                val u = a[i]
                assertTrue("opacity in range at $t", u.opacity in 0f..1f && !u.scale.isNaN() && u.scale in 0f..1.5f)
                near("deterministic", u.opacity, b[i].opacity, 0f); near("deterministic scale", u.scale, b[i].scale, 0f)
                assertEquals(l.text!!.substring(u.start, u.end), "w${i + 1}")
            }
            t += 0.05f
        }
    }

    private companion object {
        val REFERENCE = listOf(
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.0,0,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.0,3,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.0,5,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.05,0,0.3702624,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.05,3,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.05,5,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.2,0,0.9212828,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.2,3,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.2,5,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.41,0,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.41,3,0.3702624,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.41,5,0.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.77,0,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.77,3,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,0.77,5,0.8639767,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,1.3,0,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,1.3,3,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,1.3,5,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,2.9,0,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,2.9,3,1.0000000,1.0000000,0.0000000",
        "fade,ease_out,0.12,0.35,0.35,4.0,6,2.9,5,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.0,0,0.0000000,0.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.0,2,0.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.0,4,0.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.05,0,0.6182384,0.6182384,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.05,2,0.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.05,4,0.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.2,0,1.0000000,1.0890059,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.2,2,0.0000000,0.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.2,4,0.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.41,0,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.41,2,1.0000000,1.0801995,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.41,4,0.1497048,0.1497048,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.77,0,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.77,2,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,0.77,4,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,1.3,0,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,1.3,2,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,1.3,4,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,2.9,0,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,2.9,2,1.0000000,1.0000000,0.0000000",
        "pop,back,0.1,0.3,0.3,3.0,5,2.9,4,1.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.0,0,0.0000000,0.4000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.0,3,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.0,6,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.05,0,0.3300781,0.5980469,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.05,3,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.05,6,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.2,0,0.8750000,0.9250000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.2,3,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.2,6,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.41,0,1.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.41,3,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.41,6,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.77,0,1.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.77,3,0.8098906,0.8859344,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,0.77,6,0.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,1.3,0,1.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,1.3,3,1.0000000,1.0000000,0.0000000",
        "zoom,ease_out,0.2,0.4,0.4,2.0,7,1.3,6,0.5781250,0.7468750,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.0,0,0.0000000,1.0000000,0.1500000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.0,2,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.0,3,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.05,0,0.1666667,1.0000000,0.1250000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.05,2,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.05,3,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.2,0,0.6666667,1.0000000,0.0500000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.2,2,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.2,3,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.41,0,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.41,2,0.3666667,1.0000000,0.0950000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.41,3,0.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.77,0,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.77,2,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,0.77,3,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,1.3,0,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,1.3,2,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,1.3,3,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,2.9,0,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,2.9,2,1.0000000,1.0000000,0.0000000",
        "slideup,linear,0.15,0.3,0.3,5.0,4,2.9,3,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.0,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.0,2,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.0,4,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.05,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.05,2,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.05,4,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.2,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.2,2,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.2,4,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.41,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.41,2,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.41,4,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.77,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.77,2,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,0.77,4,0.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,1.3,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,1.3,2,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,1.3,4,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,2.9,0,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,2.9,2,1.0000000,1.0000000,0.0000000",
        "none,linear,0.25,0.3,0.3,4.0,5,2.9,4,1.0000000,1.0000000,0.0000000",
        )
    }
}
