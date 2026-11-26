package com.example.engine.acting

/**
 * The closed vocabulary of the WP2.5 intent verbs (Direction in the sense of A4: what the character does is authored by the
 * script, never derived from audio) and the grammar of `actTarget`. Pure Kotlin, shared by the validator, the compiler
 * side and the verb model, so the three can never disagree about what is valid (I4: unknown values degrade, they never crash).
 */
object ActVocabulary {
    val VERBS: List<String> = listOf(
        "nod", "shake_head", "emphasize", "react_surprise", "react_think", "react_laugh", "react_sad",
        "look_at", "look_away", "point_at"
    )
    val EMOTES: List<String> = listOf("exclaim", "question", "sweat", "sparkle", "heart", "idea", "anger", "zzz", "dots")

    fun isVerb(name: String?): Boolean = name != null && name in VERBS
    fun isEmote(name: String?): Boolean = name != null && name in EMOTES

    /** Verbs that aim at an `actTarget`; without a valid one they are ignored (and the validator warns). */
    fun needsTarget(verb: String?): Boolean = verb == "look_at" || verb == "point_at"

    /** Verbs that set the gaze for a while: a later one replaces an earlier one (see [ActVerbTimeline]). */
    fun isGazeVerb(verb: String?): Boolean = verb == "look_at" || verb == "look_away"

    const val T_LEFT = 0
    const val T_RIGHT = 1
    const val T_UP = 2
    const val T_DOWN = 3
    const val T_CAMERA = 4
    const val T_LAYER = 5
    const val T_POINT = 6

    /** A parsed `actTarget`. [x] and [y] are normalized screen coordinates for [T_POINT] (y down); [layerId] is set for [T_LAYER]. */
    class ActTarget(val kind: Int, val layerId: String? = null, val x: Float = 0f, val y: Float = 0f)

    /** Parses `left|right|up|down|camera|layer:<id>|x,y`; null when it is none of these (or a number is not finite). */
    fun parseTarget(text: String?): ActTarget? {
        val t = text?.trim() ?: return null
        when (t.lowercase()) {
            "left" -> return ActTarget(T_LEFT)
            "right" -> return ActTarget(T_RIGHT)
            "up" -> return ActTarget(T_UP)
            "down" -> return ActTarget(T_DOWN)
            "camera" -> return ActTarget(T_CAMERA)
        }
        if (t.startsWith("layer:")) {
            val id = t.substring(6).trim()
            return if (id.isEmpty()) null else ActTarget(T_LAYER, layerId = id)
        }
        val comma = t.indexOf(',')
        if (comma > 0) {
            val x = t.substring(0, comma).trim().toFloatOrNull()
            val y = t.substring(comma + 1).trim().toFloatOrNull()
            if (x != null && y != null && x.isFinite() && y.isFinite()) return ActTarget(T_POINT, x = x, y = y)
        }
        return null
    }
}
