package com.example.engine

import com.example.data.OverlayLayer

/**
 * A fully-resolved overlay layer state for ONE frame, ready for
 * [RigRenderer] to draw. Every position/size field is still fractional
 * (0..1 of canvas width/height or min-dimension, matching [OverlayLayer]'s
 * own convention) — [RigRenderer] is what multiplies by the actual canvas
 * pixel dimensions, exactly like it already does for camera pan and scene
 * shapes. Keeping the fraction here rather than resolving to pixels is
 * what lets a single resolved list be reused across dual-aspect export's
 * two differently-sized targets with zero extra work.
 */
data class ResolvedOverlay(
    val type: String,
    val shape: String,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotationDeg: Float,
    val opacity: Float,
    val text: String?,
    val fontSize: Float,
    val bold: Boolean,
    val align: String,
    val color: Long,
    val gradientColor: Long?,
    val width: Float?,
    val height: Float?,
    val radius: Float?,
    val glow: Boolean,
    val glowColor: Long,
    val glowRadius: Float
)

/**
 * Stateless resolution of [OverlayLayer] definitions against a point in
 * time — same "pure function of t" spirit as [EasingMath], no per-instance
 * state to manage across preview/export threads.
 *
 * Each layer independently goes through three phases between [OverlayLayer.startSec]
 * and [OverlayLayer.endSec]:
 *   1. ENTER  — [OverlayLayer.enterStyle] animates in over [OverlayLayer.enterDuration].
 *   2. HOLD   — fully "in": opacity/scale/position at rest.
 *   3. EXIT   — [OverlayLayer.exitStyle] animates out over [OverlayLayer.exitDuration],
 *               finishing exactly at [OverlayLayer.endSec].
 * A layer outside [startSec, endSec) is simply omitted from the result —
 * there is no carry-forward state to track between calls, unlike
 * [BakedKeyframe]'s pose/camera/scene fields, which is why this can be a
 * pure function instead of needing its own compiled/baked intermediate
 * form.
 */
object OverlayResolver {

    private val SLOT_Y = mapOf(
        "upper"  to 0.22f,
        "center" to 0.50f,
        "lower"  to 0.78f
    )

    fun resolve(layers: List<OverlayLayer>, timeSec: Float): List<ResolvedOverlay> {
        if (layers.isEmpty()) return emptyList()
        val out = mutableListOf<ResolvedOverlay>()
        for (layer in layers) {
            if (timeSec < layer.startSec || timeSec >= layer.endSec) continue
            out += resolveOne(layer, timeSec)
        }
        return out
    }

    private fun resolveOne(layer: OverlayLayer, timeSec: Float): ResolvedOverlay {
        val windowLen = (layer.endSec - layer.startSec).coerceAtLeast(0.001f)
        // Clamp enter/exit so they can never together exceed the layer's own
        // window — a short-lived layer with long enter/exit durations should
        // compress proportionally rather than have exit start before enter
        // finishes (which would produce an undefined/overlapping blend).
        val halfWindow = windowLen / 2f
        val enterDur = layer.enterDuration.coerceIn(0.001f, halfWindow)
        val exitDur  = layer.exitDuration.coerceIn(0.001f, halfWindow)

        val tSinceStart = timeSec - layer.startSec
        val tUntilEnd   = layer.endSec - timeSec

        // progress: 0 = fully "out" (at the animated edge), 1 = fully "in" (at rest)
        val (progress, style, ease) = when {
            tSinceStart < enterDur -> Triple((tSinceStart / enterDur).coerceIn(0f, 1f), layer.enterStyle, layer.enterEase)
            tUntilEnd < exitDur    -> Triple((tUntilEnd / exitDur).coerceIn(0f, 1f), layer.exitStyle, layer.exitEase)
            else                   -> Triple(1f, "none", "linear")
        }
        val eased = EasingMath.ease(ease, progress)

        val (opacityMul, scaleMul, offsetX, offsetY) = animate(style, eased)

        val baseY = layer.slot?.let { SLOT_Y[it] } ?: layer.y

        return ResolvedOverlay(
            type          = layer.type,
            shape         = layer.shape,
            x             = layer.x + offsetX,
            y             = baseY + offsetY,
            scale         = layer.scale * scaleMul,
            rotationDeg   = layer.rotationDeg,
            opacity       = (layer.opacity * opacityMul).coerceIn(0f, 1f),
            text          = layer.text,
            fontSize      = layer.fontSize,
            bold          = layer.bold,
            align         = layer.align,
            color         = layer.color,
            gradientColor = layer.gradientColor,
            width         = layer.width,
            height        = layer.height,
            radius        = layer.radius,
            glow          = layer.glow,
            glowColor     = layer.glowColor ?: layer.color,
            glowRadius    = layer.glowRadius
        )
    }

    /**
     * Maps an enter/exit style name + eased progress (0=animated edge,
     * 1=at rest) to (opacityMultiplier, scaleMultiplier, xOffsetFraction,
     * yOffsetFraction). Offsets are added to the layer's resting x/y, so a
     * "slideup" layer starts below its resting position and settles upward
     * into it as progress approaches 1.
     */
    private fun animate(style: String, p: Float): FourFloats = when (style) {
        "fade"     -> FourFloats(p, 1f, 0f, 0f)
        "pop"      -> FourFloats(p, p, 0f, 0f)
        "zoom"     -> FourFloats(p, 0.4f + 0.6f * p, 0f, 0f)
        "slideup"   -> FourFloats(p, 1f, 0f, (1f - p) * 0.15f)
        "slidedown" -> FourFloats(p, 1f, 0f, -(1f - p) * 0.15f)
        "none"     -> FourFloats(1f, 1f, 0f, 0f)
        else       -> FourFloats(p, 1f, 0f, 0f)
    }

    /**
     * Small named tuple — avoids a boxed generic Quadruple for a per-frame
     * path. A data class already provides component1..4 matching
     * constructor order, which is all the destructuring in [animate]'s
     * call sites needs.
     */
    private data class FourFloats(val a: Float, val b: Float, val c: Float, val d: Float)
}
