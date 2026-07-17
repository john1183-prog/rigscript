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
 *                            bounce | elastic_out | spring | rigid
 *                    "rigid" snaps instantly to the target pose with no
 *                    interpolation and no spring chase — for mechanical or
 *                    abrupt emotional cuts. Every OTHER ease type gets a
 *                    physics spring-chase layered on top by default (see
 *                    [com.example.data.AmplitudeSettings.easeAllWithSpring]).
 * [springStiffness]  Only used when ease == "spring". Higher = snappier.
 * [springDamping]    Only used when ease == "spring". Higher = less oscillation.
 * [expression]       Optional facial expression for this event — one of
 *                    normal | wide | squint | worried | angry | happy (see
 *                    [com.example.engine.Expression]). null = hold whatever
 *                    expression was last set (same carry-forward semantics as
 *                    pose). Independent of the audio-driven mouth shape.
 * [cameraZoom]       Optional camera zoom target (1.0 = no zoom). null = hold
 *                    previous value. Eases toward the target using [ease] over
 *                    [duration], same as the pose transition.
 * [cameraPanX]       Optional horizontal pan target, as a fraction of canvas
 *                    width (-1..1 roughly). null = hold previous value.
 * [cameraPanY]       Optional vertical pan target, as a fraction of canvas
 *                    height. null = hold previous value.
 * [cameraShake]      Optional one-shot camera shake burst (0..1 intensity)
 *                    triggered the instant this event becomes active. Decays
 *                    over ~0.3s. null/0 = no shake. Does NOT carry forward —
 *                    it's a momentary trigger, not a held state.
 * [caption]          Optional on-screen caption text starting at this event's
 *                    [timeSec]. BOUNDED-WINDOW semantics, not carry-forward:
 *                    unlike pose/expression/camera, a caption does NOT stay
 *                    visible until the next event sets a new one — it's only
 *                    shown for [captionDurationSec], then disappears. This
 *                    matches how captions are actually authored (one line per
 *                    spoken beat) and avoids stale/orphaned text sitting on
 *                    screen through unrelated later events. Extracted
 *                    separately as [com.example.engine.CaptionCue] rather than
 *                    baked into [com.example.engine.BakedKeyframe] — see
 *                    [com.example.engine.TimelineCompiler.extractCaptions].
 * [captionDurationSec] How long [caption] stays on screen, in seconds. Ignored
 *                    if [caption] is null.
 * [skyColor]         Optional scene sky/background color for this event
 *                    onward — ARGB Long, same convention as
 *                    [AppearanceSettings]'s color fields. null = hold the
 *                    previous value (or the appearance default if never set).
 *                    Carry-forward, same rule as pose/expression.
 * [groundColor]      Optional ground-band color, same carry-forward rule as [skyColor].
 * [horizonY]         Optional horizon line position, fraction of canvas height
 *                    (0..1). null = hold previous value.
 * [sceneShape]       Optional background silhouette — one of
 *                    [com.example.engine.SceneShape]'s constants as a string
 *                    (none|mountains|city|trees|clouds). null = hold previous.
 * [sceneAtmosphere]  Optional foreground weather/atmosphere overlay — one of
 *                    [com.example.engine.SceneAtmosphere]'s constants as a
 *                    string (none|rain|snow|fog|stars). null = hold previous.
 * [soundEffect]      Optional one-shot sound effect id, triggered AT this
 *                    event's [timeSec]. Does NOT carry forward — same
 *                    category as [cameraShake], not [caption]. The id must
 *                    match a clip already imported into the project's sound
 *                    effect library (see
 *                    [com.example.viewmodel.MainViewModel.importSoundEffect]);
 *                    an unrecognized id is silently ignored rather than
 *                    treated as an error, same graceful-degradation
 *                    reasoning as [com.example.engine.SceneShape.fromString].
 * [soundEffectVolume] Multiplier (0..1 typical, not hard-clamped) applied on
 *                    top of the clip's own configured volume for this
 *                    specific trigger. Ignored if [soundEffect] is null.
 */
@Serializable
data class ScriptEvent(
    val timeSec: Float,
    val pose: String,
    val duration: Float = 0.5f,
    val ease: String = "ease_in_out",
    val springStiffness: Float = 280f,
    val springDamping: Float = 28f,
    val expression: String? = null,
    val cameraZoom: Float? = null,
    val cameraPanX: Float? = null,
    val cameraPanY: Float? = null,
    val cameraShake: Float? = null,
    val caption: String? = null,
    val captionDurationSec: Float = 2.5f,
    val skyColor: Long? = null,
    val groundColor: Long? = null,
    val horizonY: Float? = null,
    val sceneShape: String? = null,
    val sceneAtmosphere: String? = null,
    val soundEffect: String? = null,
    val soundEffectVolume: Float = 1.0f
)

/**
 * The complete animation script attached to a project.
 * This is stored as JSON inside the project row in the database and can also be
 * imported/exported as a standalone .json file.
 *
 * [blinkEvents] Timestamps (seconds) where a dramatic blink should fire,
 * independent of [events]. Kept separate from [ScriptEvent] rather than a flag
 * on it because a blink can happen mid-hold, unrelated to any pose change — the
 * AI shouldn't have to restate the current pose just to place one. Natural
 * blinking happens automatically regardless of this list; these are ADDITIONAL,
 * deliberate, AI-placed blinks for emotional emphasis (e.g. right before a key
 * line, or on a reaction beat).
 */
@Serializable
data class AnimScript(
    val version: String = "1.0",
    val events: List<ScriptEvent> = emptyList(),
    val blinkEvents: List<Float> = emptyList()
) {
    companion object {
        /** Canonical blank script shown when a project is created. */
        val EMPTY = AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f, ease = "ease_out")
            )
        )

        /** Demo script — exercises poses plus the V2 expression/camera/rigid/blink features. */
        val DEMO = AnimScript(
            events = listOf(
                ScriptEvent(0.0f,  "stand_straight", 0.4f, "ease_out"),
                ScriptEvent(1.5f,  "wave",           0.6f, "spring", expression = "happy"),
                ScriptEvent(3.5f,  "explain",        0.7f, "ease_in_out"),
                ScriptEvent(6.0f,  "present",        0.6f, "ease_out"),
                ScriptEvent(8.5f,  "point_self",     0.5f, "spring"),
                ScriptEvent(10.5f, "open_hands",     0.5f, "ease_in_out"),
                ScriptEvent(12.5f, "think",          0.8f, "ease_in_out", expression = "worried"),
                ScriptEvent(15.0f, "excited",        0.5f, "elastic_out", expression = "wide",
                    cameraZoom = 1.12f),
                ScriptEvent(17.5f, "walk_a",         0.4f, "ease_in_out", expression = "normal",
                    cameraZoom = 1f),
                ScriptEvent(18.3f, "walk_b",         0.4f, "ease_in_out"),
                ScriptEvent(19.1f, "walk_a",         0.4f, "ease_in_out"),
                ScriptEvent(19.9f, "stand_straight", 0.1f, "rigid"),
                ScriptEvent(21.5f, "celebrate",      0.6f, "elastic_out", expression = "happy",
                    cameraZoom = 1.18f, cameraShake = 0.4f),
                ScriptEvent(24.0f, "stand_straight", 0.8f, "ease_in_out", expression = "normal",
                    cameraZoom = 1f)
            ),
            blinkEvents = listOf(1.3f, 14.7f, 21.4f)
        )
    }
}
