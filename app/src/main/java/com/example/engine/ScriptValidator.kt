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

    private val VALID_OVERLAY_TYPE  = setOf("text", "shape")
    private val VALID_OVERLAY_SHAPE = setOf("rect", "circle", "line")
    private val VALID_OVERLAY_STYLE = setOf("fade", "pop", "zoom", "slideup", "slidedown", "none")
    private val VALID_OVERLAY_SLOT  = setOf("upper", "center", "lower")
    // Ease values valid for enterEase/exitEase — same set VALID_EASE covers
    // minus "rigid" (rigid is pose-transition-specific and meaningless for
    // an opacity/scale fade) plus "back" (added specifically for overlay
    // pop/zoom styles, not used anywhere in ScriptEvent).
    private val VALID_OVERLAY_EASE = setOf(
        "linear", "ease_in", "ease_out", "ease_in_out", "bounce", "elastic_out", "spring", "back"
    )

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

        layers.filter { it.endSec <= it.startSec }
            .takeIf { it.isNotEmpty() }
            ?.let { bad ->
                warnings += "Overlay layer(s) with endSec <= startSec will never be visible: ${bad.joinToString { it.id.ifBlank { "(unnamed)" } }}"
            }

        // Clash detection — O(n^2) over layer count, fine since a project
        // realistically has a handful to a few dozen overlay layers, same
        // scale assumption as the cue-list linear scans elsewhere.
        for (i in layers.indices) {
            for (j in i + 1 until layers.size) {
                val a = layers[i]; val b = layers[j]
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

    private fun unknownValues(values: List<String>, allowed: Set<String>, caseInsensitive: Boolean = false): List<String> {
        val allowedNorm = if (caseInsensitive) allowed.map { it.lowercase() }.toSet() else allowed
        return values.distinct().filter { v -> (if (caseInsensitive) v.lowercase() else v) !in allowedNorm }
    }
}
