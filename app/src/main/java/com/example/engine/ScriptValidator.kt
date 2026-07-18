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

    /**
     * @param soundEffectIds ids actually present in the active project's sound
     *   effect library — passed in rather than read from anywhere global,
     *   since the valid set is per-project, not fixed.
     */
    fun validate(script: AnimScript, soundEffectIds: Set<String>): List<String> {
        if (script.events.isEmpty()) return emptyList()
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

        return warnings
    }

    private fun unknownValues(values: List<String>, allowed: Set<String>, caseInsensitive: Boolean = false): List<String> {
        val allowedNorm = if (caseInsensitive) allowed.map { it.lowercase() }.toSet() else allowed
        return values.distinct().filter { v -> (if (caseInsensitive) v.lowercase() else v) !in allowedNorm }
    }
}
