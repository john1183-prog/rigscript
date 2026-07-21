package com.example.engine

import com.example.data.OverlayLayer

/**
 * Output of the PARENTING phase — final world-space (fraction of canvas)
 * state, ready for [RigRenderer] to draw. See [OverlayResolver]'s doc
 * comment for why resolution is split into this and [TimeResolvedOverlay].
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
    val glowRadius: Float,
    /** World-fraction trail sample points, oldest first, current position last. Empty unless [OverlayLayer.trail] is set on a physics-driven layer. */
    val trailPoints: List<Pair<Float, Float>> = emptyList()
)

/**
 * Output of the TIME-BASED phase — everything about a layer's state at a
 * given instant EXCEPT its final world position, which depends on
 * [OverlayLayer.parentBone]/[OverlayLayer.parentLayer] and (for a bone
 * parent) the actual pixel dimensions of whichever canvas it's being
 * drawn to. [localX]/[localY] are either an absolute canvas fraction (no
 * parent) or an offset relative to the parent's resolved position
 * (parent set) — see [OverlayResolver.applyParenting].
 */
data class TimeResolvedOverlay(
    val id: String,
    val type: String,
    val shape: String,
    val parentBone: String?,
    val parentLayer: String?,
    val localX: Float,
    val localY: Float,
    val localRotationDeg: Float,
    val localScale: Float,
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
    val glowRadius: Float,
    /** Set only for a physics-driven "arrow" shape — overrides rotation with the direction of travel instead of a static angle. */
    val velocityAngleDeg: Float? = null,
    /** Local-space trail sample points (see [ResolvedOverlay.trailPoints]). */
    val trailPointsLocal: List<Pair<Float, Float>> = emptyList()
)

/**
 * Resolves [OverlayLayer] definitions against a point in time and, for
 * parented layers, against the figure's current bone positions.
 *
 * Split into two phases rather than one, for a reason that only shows up
 * with dual-aspect export: [OverlayLayer.parentBone] attachment depends
 * on actual canvas pixel dimensions (bone tip positions come out of
 * [RigRenderer]'s FK pass, which uses `canvasW`/`canvasH`/`minDim`
 * directly) — but [VideoExporter] resolves overlays ONCE per frame and
 * reuses that across dual-aspect export's differently-sized targets (see
 * V2_DECISIONS.md's "Motion graphics overlay layers" section). Splitting
 * lets the expensive, purely time-based work (physics, particle
 * expansion, easing) stay shared across targets exactly like Phase 1,
 * while only the cheap parent-compounding step — [applyParenting] — runs
 * once per target, using that target's own bone positions:
 *   1. [resolveTimeBased] — pure function of (layers, timeSec). No
 *      canvas dimensions involved anywhere in this phase.
 *   2. [applyParenting] — pure function of (time-resolved layers, bone
 *      anchor fractions for THIS target). Cheap: addition/rotation math
 *      over a small list, not the physics/particle work above.
 *
 * Physics ([OverlayLayer.physics]) is closed-form, not frame-stepped —
 * see the private physics functions below for why "bounce" specifically
 * needs a small per-call loop rather than a single formula, and why that
 * loop still counts as closed-form (it solves each flight segment
 * analytically; it does not integrate forward frame by frame).
 *
 * Particles ([OverlayLayer.type] == "particles") are BURST-ONLY for this
 * phase (all spawn at [OverlayLayer.startSec]) — a continuous emission
 * stream was cut from this phase's scope; see V2_DECISIONS.md. Each
 * particle's random angle/speed/size comes from a FRESH
 * `kotlin.random.Random` seeded from `(layer.id, particle index)`, not a
 * shared running generator — that's what makes a burst reproduce
 * identically no matter when/how it's resolved, which
 * [com.example.engine.PlaybackEngine]'s seek-anywhere model requires.
 *
 * [OverlayLayer.parentBone] attachment is POSITION-ONLY (does not inherit
 * the bone's rotation) — an attached text/shape layer keeping its own
 * static rotation avoids it flipping upside down as an arm swings past
 * vertical, which briefly did happen during development of this feature
 * when rotation inheritance was tried for bone parents.
 * [OverlayLayer.parentLayer] (layer-to-layer grouping), in contrast, DOES
 * compound rotation/scale/opacity — full 2D transform inheritance, the
 * conventional meaning of "group" this was ported from the reference
 * tool with.
 */
object OverlayResolver {

    private val SLOT_Y = mapOf(
        "upper"  to 0.22f,
        "center" to 0.50f,
        "lower"  to 0.78f
    )

    // ── Phase 1: time-based resolution ──────────────────────────────────────

    fun resolveTimeBased(layers: List<OverlayLayer>, timeSec: Float): List<TimeResolvedOverlay> {
        if (layers.isEmpty()) return emptyList()
        val out = mutableListOf<TimeResolvedOverlay>()
        for (layer in layers) {
            if (timeSec < layer.startSec || timeSec >= layer.endSec) continue
            if (layer.type == "particles") {
                out += expandParticles(layer, timeSec - layer.startSec)
            } else {
                out += resolveOne(layer, timeSec)
            }
        }
        return out
    }

    private fun resolveOne(layer: OverlayLayer, timeSec: Float): TimeResolvedOverlay {
        val windowLen = (layer.endSec - layer.startSec).coerceAtLeast(0.001f)
        val halfWindow = windowLen / 2f
        val enterDur = layer.enterDuration.coerceIn(0.001f, halfWindow)
        val exitDur  = layer.exitDuration.coerceIn(0.001f, halfWindow)

        val tSinceStart = timeSec - layer.startSec
        val tUntilEnd   = layer.endSec - timeSec

        val (progress, style, ease) = when {
            tSinceStart < enterDur -> Triple((tSinceStart / enterDur).coerceIn(0f, 1f), layer.enterStyle, layer.enterEase)
            tUntilEnd < exitDur    -> Triple((tUntilEnd / exitDur).coerceIn(0f, 1f), layer.exitStyle, layer.exitEase)
            else                   -> Triple(1f, "none", "linear")
        }
        val eased = EasingMath.ease(ease, progress)
        val (opacityMul, scaleMul, offsetX, offsetY) = animate(style, eased)

        val usesPhysics = layer.physics != "none"
        val localT = tSinceStart.coerceAtLeast(0f)

        val baseX: Float
        val baseY: Float
        var velocityAngle: Float? = null
        var trailPts: List<Pair<Float, Float>> = emptyList()

        if (usesPhysics) {
            val (px, py) = physicsPosition(layer, localT)
            baseX = px; baseY = py
            if (layer.shape == "arrow") velocityAngle = velocityAngleAt(layer, localT)
            if (layer.trail) trailPts = trailPoints(layer, localT)
        } else {
            baseX = layer.x
            baseY = layer.slot?.let { SLOT_Y[it] } ?: layer.y
        }

        // Enter/exit position offsets (slideup/slidedown) only make sense
        // for a resting x/y, not layered on top of real physics motion.
        val finalX = if (usesPhysics) baseX else baseX + offsetX
        val finalY = if (usesPhysics) baseY else baseY + offsetY

        return TimeResolvedOverlay(
            id = layer.id, type = layer.type, shape = layer.shape,
            parentBone = layer.parentBone, parentLayer = layer.parentLayer,
            localX = finalX, localY = finalY,
            localRotationDeg = layer.rotationDeg, localScale = layer.scale * scaleMul,
            opacity = (layer.opacity * opacityMul).coerceIn(0f, 1f),
            text = layer.text, fontSize = layer.fontSize, bold = layer.bold, align = layer.align,
            color = layer.color, gradientColor = layer.gradientColor,
            width = layer.width, height = layer.height, radius = layer.radius,
            glow = layer.glow, glowColor = layer.glowColor ?: layer.color, glowRadius = layer.glowRadius,
            velocityAngleDeg = velocityAngle, trailPointsLocal = trailPts
        )
    }

    private fun animate(style: String, p: Float): FourFloats = when (style) {
        "fade"      -> FourFloats(p, 1f, 0f, 0f)
        "pop"       -> FourFloats(p, p, 0f, 0f)
        "zoom"      -> FourFloats(p, 0.4f + 0.6f * p, 0f, 0f)
        "slideup"   -> FourFloats(p, 1f, 0f, (1f - p) * 0.15f)
        "slidedown" -> FourFloats(p, 1f, 0f, -(1f - p) * 0.15f)
        "none"      -> FourFloats(1f, 1f, 0f, 0f)
        else        -> FourFloats(p, 1f, 0f, 0f)
    }

    private data class FourFloats(val a: Float, val b: Float, val c: Float, val d: Float)

    // ── Physics (closed-form) ───────────────────────────────────────────────

    /**
     * Local (x, y) fraction position for a physics-driven layer at
     * [localT] seconds since [OverlayLayer.startSec]. "projectile" is a
     * single closed-form evaluation; "bounce" walks a small number of
     * analytically-solved flight segments to find which one [localT]
     * falls in (see the class doc comment for why this still counts as
     * closed-form rather than simulation).
     */
    private fun physicsPosition(layer: OverlayLayer, localT: Float): Pair<Float, Float> {
        val x = layer.x + layer.physicsVx * localT
        if (layer.physics != "bounce") {
            val y = layer.y + layer.physicsVy * localT + 0.5f * layer.physicsGravity * localT * localT
            return x to y
        }

        val g = layer.physicsGravity.coerceAtLeast(0.01f)
        val floor = layer.physicsFloorY
        val damping = layer.physicsBounceDamping.coerceIn(0f, 0.98f)

        var segStartT = 0f
        var segY0 = layer.y
        var segVy0 = layer.physicsVy
        // At most 40 segments -- speed decays geometrically each bounce,
        // so this comfortably covers any gravity/damping combination
        // before remaining bounces become visually meaningless. If a
        // pathological config somehow exhausts this, the fallthrough
        // below just rests the layer at the floor rather than crashing.
        repeat(40) {
            val tImpact = timeToReachY(segY0, segVy0, g, floor)
            if (tImpact == null || segStartT + tImpact > localT) {
                val tLocal = (localT - segStartT).coerceAtLeast(0f)
                val y = segY0 + segVy0 * tLocal + 0.5f * g * tLocal * tLocal
                return x to y
            }
            val vyImpact = segVy0 + g * tImpact
            val vyNext = -vyImpact * damping
            segStartT += tImpact
            segY0 = floor
            segVy0 = vyNext
            if (kotlin.math.abs(vyNext) < 0.02f) {
                return x to floor
            }
        }
        return x to floor
    }

    /** Smallest positive t solving `y0 + v0*t + 0.5*g*t^2 = targetY`, or null if it's never reached going forward. */
    private fun timeToReachY(y0: Float, v0: Float, g: Float, targetY: Float): Float? {
        if (g <= 0.0001f) {
            if (kotlin.math.abs(v0) < 0.0001f) return null
            val t = (targetY - y0) / v0
            return if (t > 0.0005f) t else null
        }
        val a = 0.5f * g
        val b = v0
        val c = y0 - targetY
        val disc = b * b - 4f * a * c
        if (disc < 0f) return null
        val sqrtDisc = kotlin.math.sqrt(disc)
        val t1 = (-b + sqrtDisc) / (2f * a)
        val t2 = (-b - sqrtDisc) / (2f * a)
        val candidates = listOfNotNull(t1.takeIf { it > 0.0005f }, t2.takeIf { it > 0.0005f })
        return candidates.minOrNull()
    }

    /** Direction of travel in degrees, via central-difference sampling of [physicsPosition] -- avoids hand-differentiating the bounce solver's segment logic. */
    private fun velocityAngleAt(layer: OverlayLayer, localT: Float): Float {
        val dt = 0.02f
        val (x0, y0) = physicsPosition(layer, (localT - dt).coerceAtLeast(0f))
        val (x1, y1) = physicsPosition(layer, localT + dt)
        return Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
    }

    private fun trailPoints(layer: OverlayLayer, localT: Float): List<Pair<Float, Float>> {
        val samples = 6
        val pts = ArrayList<Pair<Float, Float>>(samples)
        for (i in 0 until samples) {
            val t = (localT - layer.trailLengthSec * (samples - 1 - i) / (samples - 1f)).coerceAtLeast(0f)
            pts += physicsPosition(layer, t)
        }
        return pts
    }

    // ── Particles (burst, deterministic) ────────────────────────────────────

    private fun expandParticles(layer: OverlayLayer, localT: Float): List<TimeResolvedOverlay> {
        if (localT < 0f) return emptyList()
        val out = ArrayList<TimeResolvedOverlay>(layer.particleCount)
        val lifetime = layer.particleLifetimeSec.coerceAtLeast(0.05f)
        for (i in 0 until layer.particleCount) {
            if (localT > lifetime) continue
            // Fresh Random per particle index -- deterministic regardless
            // of when this is called, no shared generator state to keep
            // in sync across seeks. See the class doc comment.
            val rnd = kotlin.random.Random(layer.id.hashCode() * 1_000_003 + i)
            val angleDeg = rnd.nextFloat() * 360f
            val speed = layer.particleSpeed * (0.3f + rnd.nextFloat() * 0.7f)
            val size = layer.particleSizeMin + rnd.nextFloat() * (layer.particleSizeMax - layer.particleSizeMin)
            val rad = Math.toRadians(angleDeg.toDouble())
            val vx = (kotlin.math.cos(rad) * speed).toFloat()
            val vy = (kotlin.math.sin(rad) * speed).toFloat()

            val px = layer.x + vx * localT
            val py = layer.y + vy * localT + 0.5f * layer.particleGravity * localT * localT
            val lifeProgress = (localT / lifetime).coerceIn(0f, 1f)
            val opacity = (1f - lifeProgress) * layer.opacity

            out += TimeResolvedOverlay(
                id = "", type = "shape", shape = layer.particleShape,
                parentBone = layer.parentBone, parentLayer = layer.parentLayer,
                localX = px, localY = py, localRotationDeg = 0f, localScale = 1f,
                opacity = opacity.coerceIn(0f, 1f),
                text = null, fontSize = 0f, bold = false, align = "center",
                color = layer.color, gradientColor = layer.gradientColor,
                width = null, height = null, radius = size,
                glow = layer.glow, glowColor = layer.glowColor ?: layer.color, glowRadius = layer.glowRadius
            )
        }
        return out
    }

    // ── Phase 2: parenting ───────────────────────────────────────────────────

    private data class WorldState(val x: Float, val y: Float, val rotationDeg: Float, val scale: Float, val opacity: Float)

    /**
     * Compounds [TimeResolvedOverlay.localX]/etc into final world-fraction
     * state, given this specific target's current bone anchor positions
     * (fraction of ITS OWN canvas dimensions — a different value per
     * dual-aspect target, which is exactly why this step can't be shared
     * across targets the way [resolveTimeBased] is).
     *
     * [boneAnchors] maps bone id (see [StickFigureRig.BONES]) to its tip
     * position, fraction of canvas width/height.
     */
    fun applyParenting(partials: List<TimeResolvedOverlay>, boneAnchors: Map<String, Pair<Float, Float>>): List<ResolvedOverlay> {
        if (partials.isEmpty()) return emptyList()
        val byId = partials.filter { it.id.isNotBlank() }.associateBy { it.id }
        val cache = HashMap<String, WorldState>()

        return partials.map { p ->
            val w = resolveWorld(p, byId, boneAnchors, cache, mutableSetOf())
            ResolvedOverlay(
                type = p.type, shape = p.shape,
                x = w.x, y = w.y, scale = w.scale,
                rotationDeg = p.velocityAngleDeg ?: w.rotationDeg,
                opacity = w.opacity.coerceIn(0f, 1f),
                text = p.text, fontSize = p.fontSize, bold = p.bold, align = p.align,
                color = p.color, gradientColor = p.gradientColor,
                width = p.width, height = p.height, radius = p.radius,
                glow = p.glow, glowColor = p.glowColor, glowRadius = p.glowRadius,
                trailPoints = p.trailPointsLocal
            )
        }
    }

    private fun resolveWorld(
        p: TimeResolvedOverlay,
        byId: Map<String, TimeResolvedOverlay>,
        boneAnchors: Map<String, Pair<Float, Float>>,
        cache: MutableMap<String, WorldState>,
        visiting: MutableSet<String>
    ): WorldState {
        if (p.id.isNotBlank()) cache[p.id]?.let { return it }

        val world: WorldState = when {
            p.parentBone != null -> {
                val anchor = boneAnchors[p.parentBone]
                if (anchor == null) WorldState(p.localX, p.localY, p.localRotationDeg, p.localScale, p.opacity)
                else WorldState(anchor.first + p.localX, anchor.second + p.localY, p.localRotationDeg, p.localScale, p.opacity)
            }
            !p.parentLayer.isNullOrBlank() && p.id !in visiting && visiting.size < 8 -> {
                val parentLayerId = p.parentLayer as String
                val parent = byId[parentLayerId]
                if (parent == null) {
                    WorldState(p.localX, p.localY, p.localRotationDeg, p.localScale, p.opacity)
                } else {
                    visiting += p.id
                    val pw = resolveWorld(parent, byId, boneAnchors, cache, visiting)
                    visiting -= p.id
                    val rad = Math.toRadians(pw.rotationDeg.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    val rx = (p.localX * cos - p.localY * sin) * pw.scale
                    val ry = (p.localX * sin + p.localY * cos) * pw.scale
                    WorldState(
                        pw.x + rx, pw.y + ry,
                        pw.rotationDeg + p.localRotationDeg,
                        pw.scale * p.localScale,
                        pw.opacity * p.opacity
                    )
                }
            }
            else -> WorldState(p.localX, p.localY, p.localRotationDeg, p.localScale, p.opacity)
        }
        if (p.id.isNotBlank()) cache[p.id] = world
        return world
    }
}
