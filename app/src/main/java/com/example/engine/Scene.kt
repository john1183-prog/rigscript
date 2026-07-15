package com.example.engine

/**
 * Background scene-shape silhouettes RigRenderer can draw behind the ground
 * line (mountains, city skyline, trees) — cheap procedural shapes, not
 * imported art, so they scale to any canvas size and re-color safely per
 * [com.example.data.AppearanceSettings]/scripted sky+ground colors.
 *
 * Timestamped via [com.example.data.ScriptEvent.sceneShape] so the AI can
 * shift the backdrop mid-narration (e.g. "mountains" while describing a hike,
 * then "city" for the next scene) without the user hand-editing appearance
 * settings.
 */
object SceneShape {
    const val NONE      = "none"
    const val MOUNTAINS = "mountains"
    const val CITY      = "city"
    const val TREES     = "trees"
    const val CLOUDS    = "clouds"

    private val ALL = setOf(NONE, MOUNTAINS, CITY, TREES, CLOUDS)

    /** Unknown/blank strings fall back to NONE rather than throwing — a bad AI-generated value should degrade gracefully, not break the render. */
    fun fromString(value: String?): String = value?.lowercase()?.takeIf { it in ALL } ?: NONE
}

/**
 * Foreground atmosphere/weather overlays — light, cheap particle or tint
 * effects drawn over the whole scene. Same timestamped/carry-forward
 * semantics as [SceneShape] via [com.example.data.ScriptEvent.sceneAtmosphere].
 */
object SceneAtmosphere {
    const val NONE  = "none"
    const val RAIN  = "rain"
    const val SNOW  = "snow"
    const val FOG   = "fog"
    const val STARS = "stars"

    private val ALL = setOf(NONE, RAIN, SNOW, FOG, STARS)

    fun fromString(value: String?): String = value?.lowercase()?.takeIf { it in ALL } ?: NONE
}
