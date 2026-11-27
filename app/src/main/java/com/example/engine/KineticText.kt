package com.example.engine

/**
 * One text unit (a word, or a character) of a kinetic-text overlay at one instant (WP3.1).
 * [start]/[end] are the unit's character range in the layer's text (end exclusive). [opacity] and [scale] multiply
 * the layer's own; [offsetX]/[offsetY] are a displacement in fractions of the canvas (the same numbers the block
 * enter styles use for their slide). Produced by [OverlayResolver]; the renderers only draw it (I3).
 */
class TextUnitState(
    val start: Int,
    val end: Int,
    val opacity: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

/**
 * Where one text unit sits inside the wrapped text layout, in LAYOUT coordinates (before the layout's own
 * translation): the unit's centre, its width, the baseline relative to that centre and the font's ascent (negative)
 * and descent. Built by `RigRenderer.buildOverlayUnitLayout` for both renderers; a plain holder, no Android types.
 */
class OverlayUnitBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val baselineFromCenter: Float,
    val ascent: Float,
    val descent: Float
)

/** The shared layout of a kinetic text layer: final font size, the layout's translation, its height and one [OverlayUnitBox] per unit. */
class OverlayUnitLayout(
    val textSize: Float,
    val translateX: Float,
    val translateY: Float,
    val layoutHeight: Float,
    val boxes: Array<OverlayUnitBox>
)

/**
 * Pure rules of kinetic text (WP3.1, brief Phase 3): how a text is cut into units and how the per-unit enter is
 * staggered. No Android types; the Python twin is `tools/acting_proto/kinetic_proto.py`.
 *
 * Classification (A4): DIRECTION. The AI opts in per layer with `textMode`; nothing is ever split on the app's own
 * initiative. `textMode` "block" (the default) is the legacy single-bitmap text, bit-equal to before (I1).
 */
object KineticText {
    const val MODE_BLOCK = "block"
    const val MODE_WORD = "word"
    const val MODE_CHAR = "char"

    /** Stagger between units when a layer asks for a unit mode but does not say (seconds). */
    const val DEFAULT_STAGGER_SEC = 0.1f

    /** More units than this fall back one step (char to word, word to block): one texture per unit on GLES. */
    const val MAX_UNITS = 40

    fun isKnownMode(mode: String): Boolean = mode == MODE_BLOCK || mode == MODE_WORD || mode == MODE_CHAR

    /**
     * The unit ranges of [text] for [mode] as `[start0, end0, start1, end1, ...]` (end exclusive), or an empty array
     * when the layer is to be drawn as one block: mode "block", an unknown mode, fewer than 2 units, or too many
     * units even after falling back from "char" to "word". Words are runs of non-whitespace; characters are the
     * non-whitespace code points.
     */
    fun unitRanges(text: String, mode: String): IntArray {
        if (mode == MODE_CHAR) {
            val chars = charRanges(text)
            if (chars.size / 2 in 2..MAX_UNITS) return chars
            return wordRangesOrEmpty(text)
        }
        if (mode == MODE_WORD) return wordRangesOrEmpty(text)
        return EMPTY
    }

    private val EMPTY = IntArray(0)

    private fun wordRangesOrEmpty(text: String): IntArray {
        val words = wordRanges(text)
        return if (words.size / 2 in 2..MAX_UNITS) words else EMPTY
    }

    fun wordRanges(text: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && Character.isWhitespace(text[i])) i++
            if (i >= n) break
            val s = i
            while (i < n && !Character.isWhitespace(text[i])) i++
            out.add(s); out.add(i)
        }
        return out.toIntArray()
    }

    fun charRanges(text: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) { out.add(i); out.add(i + len) }
            i += len
        }
        return out.toIntArray()
    }

    /**
     * The stagger actually used: [stagger] squeezed so that the last of [unitCount] units has finished entering
     * before the layer's exit begins (`(n - 1) * stagger + enterDur <= window - exitDur`), never negative.
     */
    fun effectiveStagger(stagger: Float, unitCount: Int, enterDur: Float, exitDur: Float, windowLen: Float): Float {
        if (unitCount < 2) return 0f
        val room = (windowLen - exitDur - enterDur).coerceAtLeast(0f)
        return minOf(stagger, room / (unitCount - 1)).coerceAtLeast(0f)
    }
}
