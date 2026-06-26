package com.example.data

import kotlinx.serialization.Serializable

/**
 * A single event in the animation script.
 *
 * Example JSON:
 * ```json
 * { "timeSec": 2.5, "pose": "wave", "duration": 0.8, "ease": "spring" }
 * ```
 *
 * [timeSec]          Absolute timestamp in seconds (relative to audio start).
 * [pose]             ID of a pose in the global PoseDef library.
 * [duration]         How long the transition INTO this pose takes, in seconds.
 * [ease]             One of: linear | ease_in | ease_out | ease_in_out |
 *                            bounce | elastic_out | spring
 * [springStiffness]  Only used when ease == "spring". Higher = snappier.
 * [springDamping]    Only used when ease == "spring". Higher = less oscillation.
 */
@Serializable
data class ScriptEvent(
    val timeSec: Float,
    val pose: String,
    val duration: Float = 0.5f,
    val ease: String = "ease_in_out",
    val springStiffness: Float = 280f,
    val springDamping: Float = 28f
)

/**
 * The complete animation script attached to a project.
 * This is stored as JSON inside the project row in the database and can also be
 * imported/exported as a standalone .json file.
 */
@Serializable
data class AnimScript(
    val version: String = "1.0",
    val events: List<ScriptEvent> = emptyList()
) {
    companion object {
        /** Canonical blank script shown when a project is created. */
        val EMPTY = AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f, ease = "ease_out")
            )
        )

        /** Minimal demo script that exercises several poses. */
        val DEMO = AnimScript(
            events = listOf(
                ScriptEvent(0.0f,  "stand_straight", 0.4f, "ease_out"),
                ScriptEvent(1.5f,  "wave",           0.6f, "spring"),
                ScriptEvent(3.5f,  "explain",        0.7f, "ease_in_out"),
                ScriptEvent(6.0f,  "present",        0.6f, "ease_out"),         // new
                ScriptEvent(8.5f,  "point_self",     0.5f, "spring"),           // new
                ScriptEvent(10.5f, "open_hands",     0.5f, "ease_in_out"),      // new
                ScriptEvent(12.5f, "think",          0.8f, "ease_in_out"),
                ScriptEvent(15.0f, "excited",        0.5f, "elastic_out"),
                ScriptEvent(17.5f, "walk_a",         0.4f, "ease_in_out"),
                ScriptEvent(18.3f, "walk_b",         0.4f, "ease_in_out"),
                ScriptEvent(19.1f, "walk_a",         0.4f, "ease_in_out"),
                ScriptEvent(19.9f, "stand_straight", 0.5f, "ease_out"),
                ScriptEvent(21.5f, "celebrate",      0.6f, "elastic_out"),
                ScriptEvent(24.0f, "stand_straight", 0.8f, "ease_in_out")
            )
        )
    }
}
