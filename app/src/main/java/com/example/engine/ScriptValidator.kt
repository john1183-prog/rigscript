package com.example.engine

import com.example.data.AnimScript

/**
 * Semantic validation for an [AnimScript] that's already valid JSON — catches
 * the class of mistake JSON parsing can't: an unrecognized pose id, an
 * unknown `ease` string, a `sceneShape`/`sceneAtmosphere` value outside the
 * allowed set, a `soundEffect` id that doesn't exist in this project's
 * library, or events crowded onto (near-)identical timestamps.
 *
 * None of these are hard failures at render time — every one of them is
 * handled with graceful degradation elsewhere (an unknown pose is skipped
 * with a log line in [TimelineCompiler], an unknown ease falls through to
 * linear in [EasingMath], an unknown expression/sceneShape/sceneAtmosphere
 * falls back to its default). That graceful degradation is the right choice
 * for render-time robustness, but it also means a mistake in an
 * AI-generated script is otherwise SILENT — the video just quietly renders
 * without that pose, effect, or scene change, and the person watching it has
 * no way to know something was dropped. This class exists to surface those
 * mistakes as visible warnings in the editor instead, without changing any
 * of the underlying render-time leniency.
 *
 * Deliberately returns human-readable strings rather than a structured
 * result type — the only consumer is a warning list in the Script tab, and
 * a richer structured type would be over-engineering for that.
 */
object ScriptValidator {

    private val VALID_EASE = setOf(
        "linear", "ease_in", "ease_out", "ease_in_out", "bounce", "elastic_out", "spring", "rigid"
    )
    private val VALID_SCENE_SHAPE = setOf("none", "mountains", "city", "trees", "clouds")
    private val VALID_ATMOSPHERE  = setOf("none", "rain", "snow", "fog", "stars")

    private val VALID_OVERLAY_TYPE  = setOf("text", "shape", "particles")
    private val VALID_OVERLAY_SHAPE = setOf("rect", "circle", "line", "arrow")
    private val VALID_PARTICLE_SHAPE = setOf("circle", "rect")
    private val VALID_PHYSICS = setOf("none", "projectile", "bounce")
    private val VALID_OVERLAY_STYLE = setOf("fade", "pop", "zoom", "slideup", "slidedown", "none")
    private val VALID_OVERLAY_SLOT  = setOf("upper", "center", "lower")
    // Ease values valid for enterEase/exitEase — same set VALID_EASE covers
    // minus "rigid" (rigid is pose-transition-specific and meaningless for
    // an opacity/scale fade) plus "back" (added specifically for overlay
    // pop/zoom styles, not used anywhere in ScriptEvent).
    private val VALID_OVERLAY_EASE = setOf(
        "linear", "ease_in", "ease_out", "ease_in_out", "bounce", "elastic_out", "spring", "back"
    )

    private val VALID_BACKGROUND_STYLE = setOf("solid", "gradient")

    /**
     * Rough, deliberately approximate estimate of how far the figure's limbs/
     * head typically reach from its root anchor, as a fraction of canvas
     * min-dimension at figureScale==1.0 — used ONLY to flag "this combination
     * of figureX/figureY/figureScale probably crops the figure," not as a
     * precise geometric bound. Getting this exactly right would mean walking
     * the actual bone chain at render time; this is a cheap heuristic for the
     * editor's warning list, not a hard constraint anywhere in the renderer.
     *
     * Chosen together with [OFF_SCREEN_MARGIN] so the safe range at
     * figureScale==1.0 works out to roughly 15%-85% (the number actually
     * quoted to the AI in the prompt's FIGURE TRANSFORM & COLORS guidance —
     * verified numerically, not by hand, after an earlier pair of constants
     * turned out to make the check mathematically impossible to satisfy at
     * any figureScale above ~1.375, which would have made every combination
     * above that scale warn regardless of position).
     */
    private const val APPROX_FIGURE_HALF_EXTENT = 0.25f
    private const val OFF_SCREEN_MARGIN = 0.1f

    /**
     * @param soundEffectIds ids actually present in the active project's sound
     *   effect library — passed in rather than read from anywhere global,
     *   since the valid set is per-project, not fixed.
     */
    fun validate(script: AnimScript, soundEffectIds: Set<String>): List<String> {
        if (script.events.isEmpty() && script.overlayLayers.isEmpty()) return emptyList()
        val warnings = mutableListOf<String>()
        val poseIds = StickFigureRig.BUILT_IN_POSE_INDEX.keys

        unknownValues(script.events.map { it.pose }, poseIds)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown pose id(s) — those events will be SKIPPED entirely: ${it.joinToString()}" }

        unknownValues(script.events.map { it.ease }, VALID_EASE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown ease value(s), treated as linear: ${it.joinToString()}" }

        unknownValues(script.events.mapNotNull { it.sceneShape }, VALID_SCENE_SHAPE, caseInsensitive = true)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown sceneShape value(s), treated as none: ${it.joinToString()}" }

        unknownValues(script.events.mapNotNull { it.sceneAtmosphere }, VALID_ATMOSPHERE, caseInsensitive = true)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown sceneAtmosphere value(s), treated as none: ${it.joinToString()}" }

        unknownValues(script.events.mapNotNull { it.soundEffect }, soundEffectIds)
            .takeIf { it.isNotEmpty() }
            ?.let {
                warnings += if (soundEffectIds.isEmpty())
                    "Script references sound effect id(s) but this project has no sound effects imported yet: ${it.joinToString()}"
                else
                    "Sound effect id(s) not in this project's library, will not play: ${it.joinToString()}"
            }

        unknownValues(script.events.mapNotNull { it.backgroundStyle }, VALID_BACKGROUND_STYLE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown backgroundStyle value(s), ignored: ${it.joinToString()}" }

        // Off-screen risk — see APPROX_FIGURE_HALF_EXTENT's doc comment for
        // why this is a heuristic, not exact geometry. Only checked on
        // events that actually set figureX/figureY/figureScale, since a
        // script that never touches these can't have introduced the risk.
        for (event in script.events) {
            if (event.figureX == null && event.figureY == null && event.figureScale == null) continue
            val x = event.figureX ?: 0.5f
            val y = event.figureY ?: 0.5f
            val extent = APPROX_FIGURE_HALF_EXTENT * (event.figureScale ?: 1f)
            val margin = OFF_SCREEN_MARGIN
            val offScreenX = x - extent < -margin || x + extent > 1f + margin
            val offScreenY = y - extent < -margin || y + extent > 1f + margin
            if (offScreenX || offScreenY) {
                warnings += "Event at ${event.timeSec}s: figureX/figureY/figureScale combination will likely crop the figure off-screen (approximate check, not exact)."
            }
        }

        val times = script.events.map { it.timeSec }.sorted()
        for (i in 1 until times.size) {
            if (times[i] - times[i - 1] < 0.01f) {
                warnings += "Two or more events share (or nearly share) the same timeSec — the earlier one's transition may be skipped entirely."
                break
            }
        }

        warnings += validateOverlayLayers(script.overlayLayers)

        return warnings
    }

    /**
     * Semantic checks for [com.example.data.OverlayLayer] — same
     * graceful-degradation-but-warn philosophy as the checks above.
     *
     * The clash check specifically targets the bug class that motivated
     * requiring bounded start/end on every layer in the first place (see
     * [com.example.data.OverlayLayer]'s doc comment): two layers whose
     * visible windows overlap AND land in the same screen region (same
     * `slot`, or unset `slot` with near-identical x/y) will visually
     * overlap on screen. Structurally requiring an end time prevents the
     * "forgot to fade out" version of this bug, but two DELIBERATELY
     * bounded layers can still be placed in the same spot at the same
     * time by mistake — that's what this catches.
     */
    private fun validateOverlayLayers(layers: List<com.example.data.OverlayLayer>): List<String> {
        if (layers.isEmpty()) return emptyList()
        val warnings = mutableListOf<String>()

        unknownValues(layers.map { it.type }, VALID_OVERLAY_TYPE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay layer type(s), layer will not render: ${it.joinToString()}" }

        unknownValues(layers.filter { it.type == "shape" }.map { it.shape }, VALID_OVERLAY_SHAPE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay shape value(s), treated as rect: ${it.joinToString()}" }

        unknownValues(layers.map { it.enterStyle }, VALID_OVERLAY_STYLE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay enterStyle value(s), treated as fade: ${it.joinToString()}" }

        unknownValues(layers.map { it.exitStyle }, VALID_OVERLAY_STYLE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay exitStyle value(s), treated as fade: ${it.joinToString()}" }

        unknownValues(layers.map { it.enterEase }, VALID_OVERLAY_EASE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay enterEase value(s), treated as linear: ${it.joinToString()}" }

        unknownValues(layers.map { it.exitEase }, VALID_OVERLAY_EASE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay exitEase value(s), treated as linear: ${it.joinToString()}" }

        unknownValues(layers.mapNotNull { it.slot }, VALID_OVERLAY_SLOT)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay slot value(s), ignored (falls back to explicit x/y): ${it.joinToString()}" }

        unknownValues(layers.filter { it.type == "particles" }.map { it.particleShape }, VALID_PARTICLE_SHAPE)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay particleShape value(s), treated as circle: ${it.joinToString()}" }

        unknownValues(layers.map { it.physics }, VALID_PHYSICS)
            .takeIf { it.isNotEmpty() }
            ?.let { warnings += "Unknown overlay physics value(s), treated as none: ${it.joinToString()}" }

        val knownBoneIds = StickFigureRig.BONE_INDEX.keys
        layers.filter { layer ->
            val pb = layer.parentBone
            pb != null && pb !in knownBoneIds
        }
            .takeIf { it.isNotEmpty() }
            ?.let { bad ->
                warnings += "Unknown overlay parentBone value(s), attachment ignored: ${bad.mapNotNull { it.parentBone }.distinct().joinToString()}"
            }

        layers.filter { it.parentBone != null && it.parentLayer != null }
            .takeIf { it.isNotEmpty() }
            ?.let { bad ->
                warnings += "Overlay layer(s) with BOTH parentBone and parentLayer set — parentBone wins, parentLayer is ignored: ${bad.joinToString { it.id.ifBlank { "@${it.startSec}s" } }}"
            }

        val idToLayer = layers.filter { it.id.isNotBlank() }.associateBy { it.id }
        layers.filter { layer ->
            val pl = layer.parentLayer
            layer.parentBone == null && !pl.isNullOrBlank() && pl !in idToLayer
        }
            .takeIf { it.isNotEmpty() }
            ?.let { bad ->
                warnings += "Overlay layer(s) with a parentLayer id that doesn't match any layer's id, treated as unparented: ${bad.joinToString { "'${it.parentLayer}'" }}"
            }

        for (layer in layers) {
            if (layer.parentBone != null || layer.parentLayer.isNullOrBlank() || layer.id.isBlank()) continue
            if (hasParentCycle(layer, idToLayer)) {
                warnings += "Overlay layer '${layer.id}' has a parentLayer cycle (directly or through a chain) — treated as unparented to avoid an infinite loop."
            }
        }

        layers.filter { it.endSec <= it.startSec }
            .takeIf { it.isNotEmpty() }
            ?.let { bad ->
                warnings += "Overlay layer(s) with endSec <= startSec will never be visible: ${bad.joinToString { it.id.ifBlank { "(unnamed)" } }}"
            }

        // Clash detection — O(n^2) over layer count, fine since a project
        // realistically has a handful to a few dozen overlay layers, same
        // scale assumption as the cue-list linear scans elsewhere. Skipped
        // for physics-driven layers on EITHER side: their x/y is only a
        // launch point, not a resting position, so comparing raw x/y
        // between two moving layers doesn't mean anything about whether
        // they'll actually collide on screen.
        for (i in layers.indices) {
            for (j in i + 1 until layers.size) {
                val a = layers[i]; val b = layers[j]
                if (a.physics != "none" || b.physics != "none") continue
                val windowsOverlap = a.startSec < b.endSec && b.startSec < a.endSec
                if (!windowsOverlap) continue
                val sameSlot = a.slot != null && a.slot == b.slot
                val nearSamePosition = a.slot == null && b.slot == null &&
                    kotlin.math.abs(a.x - b.x) < 0.08f && kotlin.math.abs(a.y - b.y) < 0.08f
                if (sameSlot || nearSamePosition) {
                    val aLabel = a.id.ifBlank { "@${a.startSec}s" }
                    val bLabel = b.id.ifBlank { "@${b.startSec}s" }
                    warnings += "Overlay layers '$aLabel' and '$bLabel' overlap in both time and position — they may visually collide on screen."
                }
            }
        }

        return warnings
    }

    /** Walks a parentLayer chain looking for a cycle back to [start], capped at depth 8 to match [OverlayResolver]'s own guard. */
    private fun hasParentCycle(start: com.example.data.OverlayLayer, idToLayer: Map<String, com.example.data.OverlayLayer>): Boolean {
        var current: com.example.data.OverlayLayer? = start
        val visited = mutableSetOf<String>()
        for (depth in 0 until 8) {
            val parentId = current?.parentLayer
            if (parentId.isNullOrBlank()) return false
            if (parentId == start.id) return true
            if (parentId in visited) return true
            visited += parentId
            current = idToLayer[parentId] ?: return false
        }
        return false
    }

    private fun unknownValues(values: List<String>, allowed: Set<String>, caseInsensitive: Boolean = false): List<String> {
        val allowedNorm = if (caseInsensitive) allowed.map { it.lowercase() }.toSet() else allowed
        return values.distinct().filter { v -> (if (caseInsensitive) v.lowercase() else v) !in allowedNorm }
    }
}
