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
    val cameraShake: Float
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
                cameraShake     = event.cameraShake ?: 0f
            )

            prevAngles = toAngles.copyOf()
            prevZoom = toZoom
            prevPanX = toPanX
            prevPanY = toPanY
        }

        return result
    }
}
