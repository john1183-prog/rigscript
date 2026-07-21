package com.example.engine

import com.example.data.AnimScript
import com.example.data.PoseDef

/**
 * A single compiled keyframe ready for the render loop.
 *
 * [timeSec]        When this pose change begins (absolute, seconds from audio start).
 * [duration]       Transition duration in seconds.
 * [ease]           Easing type string forwarded to [EasingMath.ease]. `"rigid"` means
 *                  [PlaybackEngine] snaps directly to [toAngles]/camera targets with
 *                  no interpolation and no spring chase, regardless of
 *                  [com.example.data.AmplitudeSettings.easeAllWithSpring].
 * [fromAngles]     Total bone angles (defaultAngle + poseOffset) at the START of transition.
 * [toAngles]       Total bone angles at the END of transition (the target pose).
 * [springStiffness]/[springDamping] Forwarded to the spring integrator when ease=="spring"
 *                  or when [com.example.data.AmplitudeSettings.easeAllWithSpring] applies
 *                  the default spring chase to a non-"rigid" transition.
 * [expression]     Resolved [Expression] constant active as of this keyframe — carries
 *                  forward from the previous keyframe when the source event's
 *                  `expression` field was null (same carry-forward rule as pose).
 * [fromCameraZoom]/[toCameraZoom]           Camera zoom, interpolated like pose angles.
 * [fromCameraPanX]/[toCameraPanX]           Horizontal pan, fraction of canvas width.
 * [fromCameraPanY]/[toCameraPanY]           Vertical pan, fraction of canvas height.
 * [cameraShake]    One-shot shake intensity triggered AT [timeSec]. Does NOT carry
 *                  forward — 0f unless the source event explicitly set it.
 * [fromSkyColor]/[toSkyColor]         Sky/background color, interpolated like
 *                  camera zoom. Null means "no scripted override was ever set
 *                  as of this keyframe" — [PlaybackEngine]/[RigRenderer] fall
 *                  back to [com.example.data.AppearanceSettings]'s bg color in
 *                  that case, so a project with no scene events renders
 *                  exactly as before this feature existed.
 * [fromGroundColor]/[toGroundColor]   Same null-means-no-override rule, for the ground band.
 * [fromHorizonY]/[toHorizonY]         Same null-means-no-override rule, for the horizon line's Y fraction.
 * [sceneShape]     Current background silhouette — one of [SceneShape]'s
 *                  constants. Snap semantics (carry-forward, no interpolation),
 *                  same as [expression].
 * [sceneAtmosphere] Current foreground weather/atmosphere overlay — one of
 *                  [SceneAtmosphere]'s constants. Snap semantics, same as [expression].
 */
data class BakedKeyframe(
    val timeSec: Float,
    val duration: Float,
    val ease: String,
    val fromAngles: FloatArray,
    val toAngles: FloatArray,
    val springStiffness: Float,
    val springDamping: Float,
    val expression: Int,
    val fromCameraZoom: Float,
    val toCameraZoom: Float,
    val fromCameraPanX: Float,
    val toCameraPanX: Float,
    val fromCameraPanY: Float,
    val toCameraPanY: Float,
    val cameraShake: Float,
    val fromSkyColor: Long?,
    val toSkyColor: Long?,
    val fromGroundColor: Long?,
    val toGroundColor: Long?,
    val fromHorizonY: Float?,
    val toHorizonY: Float?,
    val sceneShape: String,
    val sceneAtmosphere: String
)

/**
 * A caption cue extracted from [com.example.data.ScriptEvent.caption]. Kept as
 * a separate flat list rather than folded into [BakedKeyframe] because
 * captions use BOUNDED-WINDOW visibility (show for [durationSec] then vanish)
 * instead of the carry-forward semantics every other keyframe field uses — see
 * [com.example.data.ScriptEvent.caption]'s doc comment. Folding it into
 * BakedKeyframe would require every consumer to re-derive "is this still
 * within its window" from data that doesn't carry-forward, which is exactly
 * the bug class bounded-window semantics exist to avoid.
 */
data class CaptionCue(
    val startSec: Float,
    val endSec: Float,
    val text: String
)

/**
 * A one-shot sound-effect trigger extracted from [com.example.data.ScriptEvent.soundEffect].
 * Bounded to a single instant, not a window — unlike [CaptionCue], there's no
 * "end" time; playback duration is however long the referenced clip actually
 * is. Kept separate from [BakedKeyframe] for the same reason [CaptionCue] is:
 * this doesn't carry forward, so folding it into carry-forward keyframe
 * state would misrepresent it.
 *
 * [clipId] is resolved against the project's sound-effect library
 * (`ProjectDef.soundEffects`) by the caller — [TimelineCompiler] itself has
 * no knowledge of `ProjectDef` and doesn't validate the id exists.
 */
data class SoundEffectCue(
    val timeSec: Float,
    val clipId: String,
    val volumeMultiplier: Float
)

/**
 * Compiles an [AnimScript] into an ordered [List<BakedKeyframe>].
 *
 * [poseResolver] is called with a pose ID and should return the matching [PoseDef]
 * (looking first in the global DB, then in built-in poses). Unknown pose IDs log a
 * warning and fall back to the rest position.
 */
object TimelineCompiler {

    fun compile(
        script: AnimScript,
        poseResolver: (String) -> PoseDef?
    ): List<BakedKeyframe> {
        val rig    = StickFigureRig
        val bones  = rig.BONES
        val n      = rig.BONE_COUNT

        // Default total angles = each bone's defaultAngleDegrees (pose offset = 0)
        val defaultAngles = FloatArray(n) { i -> bones[i].defaultAngleDegrees }

        // Sort events by time, so we can compute fromAngles from the previous event's target.
        val sorted = script.events.sortedBy { it.timeSec }

        val result = mutableListOf<BakedKeyframe>()
        var prevAngles = defaultAngles.copyOf()   // angles at the END of previous keyframe

        // V2 — carried state across events. Expression and camera both use
        // carry-forward semantics identical to pose: a null field on the event
        // means "keep whatever was active", not "reset to default/NORMAL".
        var currentExpression = Expression.NORMAL
        var prevZoom = 1f
        var prevPanX = 0f
        var prevPanY = 0f

        // V2 scene state — null means "no override yet", carried forward exactly
        // like prevZoom/prevPanX/prevPanY above. sceneShape/sceneAtmosphere use
        // "none" as their own not-null default since NONE is itself a valid state.
        var prevSkyColor: Long?    = null
        var prevGroundColor: Long? = null
        var prevHorizonY: Float?   = null
        var currentSceneShape      = SceneShape.NONE
        var currentSceneAtmosphere = SceneAtmosphere.NONE

        for (event in sorted) {
            val pose = poseResolver(event.pose)
            if (pose == null) {
                android.util.Log.w("TimelineCompiler", "Pose '${event.pose}' not found, skipping.")
                continue
            }

            // Compute target total angles: defaultAngle + poseRelativeOffset
            val toAngles = FloatArray(n) { i ->
                val boneId = bones[i].id
                bones[i].defaultAngleDegrees + (pose.joints[boneId] ?: 0f)
            }

            if (event.expression != null) {
                currentExpression = Expression.fromString(event.expression)
            }
            val toZoom = event.cameraZoom ?: prevZoom
            val toPanX = event.cameraPanX ?: prevPanX
            val toPanY = event.cameraPanY ?: prevPanY

            val toSkyColor    = event.skyColor ?: prevSkyColor
            val toGroundColor = event.groundColor ?: prevGroundColor
            val toHorizonY    = event.horizonY ?: prevHorizonY
            if (event.sceneShape != null) currentSceneShape = SceneShape.fromString(event.sceneShape)
            if (event.sceneAtmosphere != null) currentSceneAtmosphere = SceneAtmosphere.fromString(event.sceneAtmosphere)

            result += BakedKeyframe(
                timeSec         = event.timeSec,
                duration        = event.duration.coerceAtLeast(0.016f),
                ease            = event.ease,
                fromAngles      = prevAngles.copyOf(),
                toAngles        = toAngles.copyOf(),
                springStiffness = event.springStiffness,
                springDamping   = event.springDamping,
                expression      = currentExpression,
                fromCameraZoom  = prevZoom,
                toCameraZoom    = toZoom,
                fromCameraPanX  = prevPanX,
                toCameraPanX    = toPanX,
                fromCameraPanY  = prevPanY,
                toCameraPanY    = toPanY,
                cameraShake     = event.cameraShake ?: 0f,
                fromSkyColor    = prevSkyColor,
                toSkyColor      = toSkyColor,
                fromGroundColor = prevGroundColor,
                toGroundColor   = toGroundColor,
                fromHorizonY    = prevHorizonY,
                toHorizonY      = toHorizonY,
                sceneShape      = currentSceneShape,
                sceneAtmosphere = currentSceneAtmosphere
            )

            prevAngles = toAngles.copyOf()
            prevZoom = toZoom
            prevPanX = toPanX
            prevPanY = toPanY
            prevSkyColor = toSkyColor
            prevGroundColor = toGroundColor
            prevHorizonY = toHorizonY
        }

        return result
    }

    /**
     * Extracts caption cues from [script]'s events. Kept as a separate pass
     * (rather than something computed alongside [compile]'s keyframe loop)
     * because captions don't need pose resolution or any of the carry-forward
     * state above — see [CaptionCue]'s doc comment for why they're not folded
     * into [BakedKeyframe] at all.
     */
    fun extractCaptions(script: AnimScript): List<CaptionCue> =
        script.events
            .filter { !it.caption.isNullOrBlank() }
            .sortedBy { it.timeSec }
            .map { CaptionCue(it.timeSec, it.timeSec + it.captionDurationSec.coerceAtLeast(0.1f), it.caption!!) }

    /** Extracts one-shot sound-effect trigger points — see [SoundEffectCue]'s doc comment. */
    fun extractSoundEffectCues(script: AnimScript): List<SoundEffectCue> =
        script.events
            .filter { !it.soundEffect.isNullOrBlank() }
            .sortedBy { it.timeSec }
            .map { SoundEffectCue(it.timeSec, it.soundEffect!!, it.soundEffectVolume) }

    /**
     * Extracts the motion-graphics overlay layers, sorted by [com.example.data.OverlayLayer.startSec].
     * Unlike [compile]'s keyframe loop, there's no carry-forward state to
     * thread through here — each [com.example.data.OverlayLayer] is already
     * fully self-contained (explicit start AND end), so sorting is the only
     * "compilation" needed. Per-frame resolution happens later, in
     * [OverlayResolver.resolve].
     */
    fun extractOverlayLayers(script: AnimScript): List<com.example.data.OverlayLayer> =
        script.overlayLayers.sortedBy { it.startSec }
}
