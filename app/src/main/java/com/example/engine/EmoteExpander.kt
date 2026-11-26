package com.example.engine

import com.example.data.AnimScript
import com.example.data.OverlayAnimKeyframe
import com.example.data.OverlayLayer
import com.example.engine.acting.ActVocabulary

/**
 * WP2.5 emotes: each `emote` on an event expands into at most four ordinary overlay layers parented to the head bone
 * (Direction, A4: the script says what mark appears). They are plain [OverlayLayer]s, so the shared resolver, the Canvas
 * renderer and the GLES renderer draw them with no new code (I3) and seeking anywhere reproduces them (static data, I2).
 * [TimelineCompiler.extractOverlayLayers] merges them with the script's own layers; a script without emotes gets exactly its
 * own sorted layers back.
 *
 * Sizes and offsets are canvas fractions chosen for the 9:16 frame (the figure is the same height in both aspects, but a
 * fraction of the WIDTH is 1.78 times larger on a 16:9 canvas); on 16:9 the marks therefore sit a little farther from the head
 * than on 9:16. A device check decides whether per-aspect scaling is needed (TEST_CHECKLIST).
 */
object EmoteExpander {

    /** One element of an emote: where it sits relative to the head centre and how it looks. */
    private class Part(
        val type: String, val shape: String = "rect", val text: String? = null,
        val dx: Float, val dy: Float, val drift: Float = 0f,
        val size: Float = 0f, val w: Float? = null, val h: Float? = null, val rot: Float = 0f,
        val color: Long, val glow: Boolean = false,
        val delay: Float = 0f, val life: Float = 1.3f, val pulse: Boolean = false
    )

    private const val RED = 0xFFE53935L
    private const val BLUE = 0xFF1E88E5L
    private const val CYAN = 0xFF4FC3F7L
    private const val YELLOW = 0xFFFFD54FL
    private const val PINK = 0xFFEC407AL
    private const val BULB = 0xFFFFEB3BL
    private const val GREY = 0xFF9E9E9EL
    private const val BLUEGREY = 0xFF90A4AEL
    private const val DOTGREY = 0xFF757575L

    private fun text(t: String, dx: Float, dy: Float, size: Float, color: Long, drift: Float = -0.012f, delay: Float = 0f, life: Float = 1.3f) =
        Part("text", text = t, dx = dx, dy = dy, drift = drift, size = size, color = color, delay = delay, life = life)

    private fun dot(r: Float, dx: Float, dy: Float, color: Long, glow: Boolean = false, drift: Float = 0f, delay: Float = 0f, life: Float = 1.3f) =
        Part("shape", "circle", dx = dx, dy = dy, drift = drift, size = r, color = color, glow = glow, delay = delay, life = life)

    private val SPECS: Map<String, List<Part>> = mapOf(
        "exclaim" to listOf(text("!", 0.035f, -0.05f, 0.06f, RED)),
        "question" to listOf(text("?", 0.035f, -0.05f, 0.06f, BLUE)),
        "sweat" to listOf(dot(0.010f, 0.045f, -0.025f, CYAN, drift = 0.03f, life = 1.4f)),
        "sparkle" to listOf(
            dot(0.008f, 0.050f, -0.045f, YELLOW, glow = true, life = 1.0f),
            dot(0.006f, -0.045f, -0.040f, YELLOW, glow = true, delay = 0.12f, life = 1.0f),
            dot(0.007f, 0.020f, -0.065f, YELLOW, glow = true, delay = 0.24f, life = 1.0f)
        ),
        "heart" to listOf(text("\u2665", 0.035f, -0.05f, 0.06f, PINK, drift = -0.015f, life = 1.4f)),
        "idea" to listOf(
            dot(0.014f, 0.0f, -0.060f, BULB, glow = true, life = 1.5f),
            Part("shape", "rect", dx = 0.0f, dy = -0.0455f, w = 0.016f, h = 0.006f, color = GREY, life = 1.5f)
        ),
        "anger" to listOf(Part("shape", "cross", dx = -0.04f, dy = -0.05f, w = 0.008f, h = 0.03f, rot = 45f, color = RED, pulse = true)),
        "zzz" to listOf(
            text("z", 0.030f, -0.040f, 0.035f, BLUEGREY, life = 1.2f),
            text("Z", 0.045f, -0.060f, 0.045f, BLUEGREY, delay = 0.35f, life = 1.2f),
            text("Z", 0.060f, -0.082f, 0.055f, BLUEGREY, delay = 0.70f, life = 1.2f)
        ),
        "dots" to listOf(
            dot(0.006f, 0.025f, -0.050f, DOTGREY, life = 1.2f),
            dot(0.006f, 0.045f, -0.050f, DOTGREY, delay = 0.25f, life = 1.2f),
            dot(0.006f, 0.065f, -0.050f, DOTGREY, delay = 0.50f, life = 1.2f)
        )
    )

    /** How many layers the emote [name] expands to (0 for an unknown one). */
    fun partCount(name: String?): Int = SPECS[name ?: ""]?.size ?: 0

    /**
     * The layers of every emote in [script], with ids `emote_<event>_<part>`. The mark's opacity follows the figure's: the
     * `figureOpacity` carried forward to the event (1 when the script never sets it).
     */
    fun expand(script: AnimScript): List<OverlayLayer> {
        if (script.events.none { it.emote != null }) return emptyList()
        val out = ArrayList<OverlayLayer>()
        var opacity = 1f
        var n = 0
        for (e in script.events.sortedBy { it.timeSec }) {
            e.figureOpacity?.let { opacity = it }
            val parts = SPECS[e.emote ?: ""] ?: continue
            for ((i, p) in parts.withIndex()) out += layer("emote_${n}_$i", e.timeSec, opacity.coerceIn(0f, 1f), p)
            n++
        }
        return out
    }

    private fun layer(id: String, t: Float, opacity: Float, p: Part): OverlayLayer {
        val start = t + p.delay
        val anim = if (p.drift != 0f) listOf(
            OverlayAnimKeyframe(t = 0f, y = p.dy), OverlayAnimKeyframe(t = 1f, y = p.dy + p.drift, ease = "linear")
        ) else if (p.pulse) listOf(
            OverlayAnimKeyframe(t = 0f, scale = 1f), OverlayAnimKeyframe(t = 0.5f, scale = 1.25f, ease = "ease_in_out"),
            OverlayAnimKeyframe(t = 1f, scale = 1f, ease = "ease_in_out")
        ) else null
        return OverlayLayer(
            id = id, type = p.type, shape = p.shape, startSec = start, endSec = start + p.life,
            x = p.dx, y = p.dy, width = p.w, height = p.h, radius = if (p.shape == "circle") p.size else null,
            rotationDeg = p.rot, text = p.text, fontSize = if (p.type == "text") p.size else 0.08f, bold = true,
            color = p.color, glow = p.glow, glowColor = if (p.glow) p.color else null, glowRadius = 0.012f,
            enterStyle = "pop", enterDuration = 0.15f, enterEase = "ease_out", exitStyle = "fade", exitDuration = 0.35f, exitEase = "ease_in",
            opacity = opacity, parentBone = "head", inFrontOfFigure = true, anim = anim
        )
    }
}
