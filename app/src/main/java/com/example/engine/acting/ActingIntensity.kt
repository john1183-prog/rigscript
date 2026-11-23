package com.example.engine.acting

/**
 * How strongly the engine applies Craft (WP2.1, brief Part C Phase 2): per-joint timing, expression blending, gaze
 * micro-movement, mouth smoothing and mood idles. `OFF` disables Craft only; Direction (`act` verbs and `emote`,
 * WP2.5) is authored by the script and runs at every intensity.
 *
 * Stored in `AppearanceSettings.actingIntensity` as a plain string so a stored project from any earlier build still
 * decodes (I12); [parse] is the single place where the string becomes a value.
 */
enum class ActingIntensity {
    OFF, SUBTLE, NORMAL, BOLD;

    companion object {
        /** The stored default (John, 2026-10-09: acting defaults to NORMAL). */
        const val DEFAULT_NAME: String = "NORMAL"

        /** The value an unknown or missing string degrades to (I4: never crash, never invent). */
        val DEFAULT: ActingIntensity = NORMAL

        /** Case-insensitive, trimmed. Null, blank or an unknown word gives [DEFAULT]. */
        fun parse(raw: String?): ActingIntensity {
            val s = raw?.trim()?.uppercase() ?: return DEFAULT
            for (v in values()) if (v.name == s) return v
            return DEFAULT
        }

        /** The values the Appearance selector offers, in display order. */
        val SELECTABLE_NAMES: List<String> = listOf("OFF", "SUBTLE", "NORMAL", "BOLD")
    }
}
