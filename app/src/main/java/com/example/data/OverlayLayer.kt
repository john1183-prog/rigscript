package com.example.data

import kotlinx.serialization.Serializable

/**
 * A single AI-authored motion-graphics overlay layer — text bursts,
 * wordmarks, and simple shapes (rects/circles/lines) composited on top of
 * the stick-figure animation. This is the JSON-schema half of the "GMS"
 * (motion graphics) feature ported from a standalone web reference tool —
 * see V2_DECISIONS.md's "Motion graphics overlay layers" section for the
 * full rationale, including why this became new fields on the EXISTING
 * script format rather than a second DSL/parser.
 *
 * Deliberately BOUNDED-WINDOW ONLY (every layer has an explicit
 * [startSec]/[endSec]) — unlike [ScriptEvent]'s carry-forward fields,
 * there is no "holds until changed" mode for an overlay layer. This is a
 * direct fix for a real bug class found in the reference tool: a
 * persistent text layer with no exit keyframe silently stayed on screen
 * forever, so a LATER layer placed at the same slot/position visually
 * clashed with it. Requiring both ends up front means that bug class
 * can't occur here by construction, not just by prompt guidance — same
 * "no prompt-only safety" philosophy as [AppearanceSettings]'s scene-color
 * clamping.
 *
 * Position/size fields are ALL fractional (0..1 of canvas width/height,
 * same convention [RigRenderer] already uses everywhere else), not
 * absolute pixels — this is what makes dual-aspect export "just work" for
 * these layers with no special-casing, the same reason it already works
 * for the figure/camera/scene composition (see V2_DECISIONS.md's
 * "Dual-aspect export" section).
 *
 * [id]             Optional human-readable label, purely for
 *                  [com.example.engine.ScriptValidator]'s warning messages
 *                  — never read by the renderer.
 * [type]           "text" | "shape". See [com.example.engine.OverlayResolver].
 * [shape]          Only used when [type] == "shape": "rect" | "circle" | "line".
 * [startSec]       When this layer's enter animation begins.
 * [endSec]         When this layer is fully gone. Must be > [startSec].
 * [x]/[y]          Center position, fraction of canvas width/height.
 *                  Ignored for the y-axis if [slot] is set.
 * [slot]           Optional convenience shorthand for [y]: "upper" | "center"
 *                  | "lower". Overrides [y] when set; [x] is unaffected.
 * [width]/[height] Shape size for "rect", fraction of canvas width/height.
 * [radius]         Shape size for "circle", fraction of min(canvasW, canvasH).
 * [rotationDeg]    Static rotation in degrees. Not itself animated in this
 *                  phase (see V2_DECISIONS.md's Phase-1 scope note).
 * [scale]          Static scale multiplier on top of the enter/exit
 *                  animation's own scale.
 * [text]           Required when [type] == "text".
 * [fontSize]       Fraction of canvas HEIGHT — same convention the
 *                  reference tool used, chosen specifically so text reads
 *                  at a consistent relative size across both dual-aspect
 *                  export resolutions rather than looking tiny on one and
 *                  huge on the other.
 * [bold]           Text weight.
 * [align]          "left" | "center" | "right", horizontal text alignment
 *                  relative to [x].
 * [color]          ARGB Long, same convention as [AppearanceSettings].
 * [gradientColor]  If set, fills the shape/text with a top-to-bottom linear
 *                  gradient from [color] to this, instead of a flat fill.
 * [glow]           Whether to draw a soft glow behind this layer.
 * [glowColor]      Defaults to [color] when null.
 * [glowRadius]     Fraction of min(canvasW, canvasH).
 * [enterStyle]     "fade" | "pop" | "zoom" | "slideup" | "slidedown" | "none".
 * [enterDuration]  Seconds, measured from [startSec].
 * [enterEase]      One of [com.example.engine.EasingMath]'s ids.
 * [exitStyle]      Same vocabulary as [enterStyle], played in reverse.
 * [exitDuration]   Seconds, measured backward from [endSec].
 * [exitEase]       One of [com.example.engine.EasingMath]'s ids.
 * [opacity]        Ceiling alpha (0..1) once fully "in" — lets a layer be
 *                  deliberately translucent throughout, independent of the
 *                  enter/exit fade multiplier.
 */
@Serializable
data class OverlayLayer(
    val id: String = "",
    val type: String,
    val shape: String = "rect",
    val startSec: Float,
    val endSec: Float,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val slot: String? = null,
    val width: Float? = null,
    val height: Float? = null,
    val radius: Float? = null,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
    val text: String? = null,
    val fontSize: Float = 0.08f,
    val bold: Boolean = true,
    val align: String = "center",
    val color: Long = 0xFFFFFFFFL,
    val gradientColor: Long? = null,
    val glow: Boolean = false,
    val glowColor: Long? = null,
    val glowRadius: Float = 0.02f,
    val enterStyle: String = "fade",
    val enterDuration: Float = 0.35f,
    val enterEase: String = "ease_out",
    val exitStyle: String = "fade",
    val exitDuration: Float = 0.35f,
    val exitEase: String = "ease_in",
    val opacity: Float = 1f
)
