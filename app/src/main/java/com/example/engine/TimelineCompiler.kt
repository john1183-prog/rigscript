package com.example.engine

import com.example.data.AnimScript
import com.example.data.PoseDef

/**
 * A single compiled keyframe ready for the render loop.
 *
 * [timeSec]        When this pose change begins (absolute, seconds from audio start).
 * [duration]       Transition duration in seconds.
 * [ease]           Easing type string forwarded to [EasingMath.ease].
 * [fromAngles]     Total bone angles (defaultAngle + poseOffset) at the START of transition.
 * [toAngles]       Total bone angles at the END of transition (the target pose).
 * [springStiffness]/[springDamping] Forwarded to the spring integrator when ease=="spring".
 */
data class BakedKeyframe(
    val timeSec: Float,
    val duration: Float,
    val ease: String,
    val fromAngles: FloatArray,
    val toAngles: FloatArray,
    val springStiffness: Float,
    val springDamping: Float
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

            result += BakedKeyframe(
                timeSec         = event.timeSec,
                duration        = event.duration.coerceAtLeast(0.016f),
                ease            = event.ease,
                fromAngles      = prevAngles.copyOf(),
                toAngles        = toAngles.copyOf(),
                springStiffness = event.springStiffness,
                springDamping   = event.springDamping
            )

            prevAngles = toAngles.copyOf()
        }

        return result
    }
}
