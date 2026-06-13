package com.example.engine

import com.example.data.AmplitudeSettings
import kotlin.math.*

/**
 * Stateful animation engine. Owns the current joint angles and spring velocities.
 *
 * Thread-safety: designed to be ticked from the SurfaceView's background HandlerThread.
 * The public API (seekTo, updateAmplitudeSettings) uses @Volatile fields for safe
 * cross-thread reads from the UI thread.
 */
class PlaybackEngine {

    private val rig   = StickFigureRig
    private val n     = rig.BONE_COUNT
    private val bones = rig.BONES

    // ── Public readable state ─────────────────────────────────────────────────

    /** Total bone angles (defaultAngle + activeOffset) — read by RigRenderer. */
    val currentAngles: FloatArray = FloatArray(n) { i -> bones[i].defaultAngleDegrees }

    // ── Internal state ────────────────────────────────────────────────────────

    private val springVelocities: FloatArray = FloatArray(n)
    private var timeline: List<BakedKeyframe> = emptyList()
    private var currentTimeSec: Float = 0f
    private var smoothedAmplitude: Float = 0f

    @Volatile var amplitudeSettings: AmplitudeSettings = AmplitudeSettings()
    @Volatile var rawAmplitude: Float = 0f          // set from AudioPlayer each frame

    // Pre-allocated working arrays — zero allocation in the hot path
    private val baseAngles: FloatArray = FloatArray(n)  // target angles from timeline
    private val ampDelta:   FloatArray = FloatArray(n)  // amplitude-driven offsets

    // Bone index cache for amplitude modulation
    private val idxTorso    = rig.BONE_INDEX["torso"]    ?: -1
    private val idxHead     = rig.BONE_INDEX["head"]     ?: -1
    private val idxArmR     = rig.BONE_INDEX["upper_arm_r"] ?: -1
    private val idxArmL     = rig.BONE_INDEX["upper_arm_l"] ?: -1

    // ── Timeline loading ──────────────────────────────────────────────────────

    fun loadTimeline(keyframes: List<BakedKeyframe>) {
        timeline = keyframes
        reset()
    }

    fun reset() {
        currentTimeSec = 0f
        smoothedAmplitude = 0f
        springVelocities.fill(0f)
        for (i in 0 until n) currentAngles[i] = bones[i].defaultAngleDegrees
        resolveBaseAngles(0f, useAnalyticalSpring = true)
        currentAngles.copyInto(baseAngles)
    }

    // ── Tick (called ~60fps from background thread) ───────────────────────────

    /**
     * Advances time by [dtSec] seconds and updates [currentAngles].
     * [rawAmp] is the normalised audio amplitude for this frame (0..1).
     */
    fun tick(dtSec: Float, rawAmp: Float = this.rawAmplitude) {
        val safeDt = dtSec.coerceIn(0f, 0.05f)   // cap to prevent spring explosion on lag
        currentTimeSec += safeDt

        // 1. Resolve target angles from timeline using analytical spring
        resolveBaseAngles(currentTimeSec, useAnalyticalSpring = false)

        // 2. Spring-integrate currentAngles toward baseAngles
        applySpringIntegration(safeDt)

        // 3. Layer amplitude-driven motion on top
        applyAmplitudeMotion(safeDt, rawAmp)
    }

    /** Seek without physics — instantly snaps to the correct pose at [timeSec]. */
    fun seekTo(timeSec: Float) {
        currentTimeSec = timeSec
        springVelocities.fill(0f)
        resolveBaseAngles(timeSec, useAnalyticalSpring = true)
        baseAngles.copyInto(currentAngles)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fills [baseAngles] with the target total angles at [timeSec].
     *
     * For each keyframe whose transition window contains [timeSec], the
     * progress [t] is computed and the appropriate easing applied.
     * The LAST matching keyframe wins (since events are sorted by time).
     */
    private fun resolveBaseAngles(timeSec: Float, useAnalyticalSpring: Boolean) {
        if (timeline.isEmpty()) {
            bones.forEachIndexed { i, b -> baseAngles[i] = b.defaultAngleDegrees }
            return
        }

        // Find the active keyframe
        var activeKf: BakedKeyframe? = null
        for (kf in timeline) {
            if (timeSec >= kf.timeSec) activeKf = kf
            else break
        }

        val kf = activeKf ?: timeline.first()
        val elapsed  = (timeSec - kf.timeSec).coerceAtLeast(0f)
        val t        = (elapsed / kf.duration).coerceIn(0f, 1f)

        val easedT = if (kf.ease == "spring" && useAnalyticalSpring) {
            EasingMath.springAnalytical(t, kf.springStiffness, kf.springDamping)
        } else if (kf.ease == "spring") {
            t   // real-time spring uses the Euler path in applySpringIntegration
        } else {
            EasingMath.ease(kf.ease, t)
        }

        for (i in 0 until n) {
            baseAngles[i] = kf.fromAngles[i] + (kf.toAngles[i] - kf.fromAngles[i]) * easedT
        }
    }

    /** 4-step Euler spring integration toward [baseAngles]. */
    private fun applySpringIntegration(dt: Float) {
        val subDt = dt / 4f
        repeat(4) {
            for (i in 0 until n) {
                val (newPos, newVel) = EasingMath.springEulerStep(
                    pos       = currentAngles[i],
                    vel       = springVelocities[i],
                    target    = baseAngles[i],
                    stiffness = 320f,   // tight tracking spring — not user-facing
                    damping   = 32f,
                    dt        = subDt
                )
                currentAngles[i]    = newPos
                springVelocities[i] = newVel
            }
        }
    }

    /**
     * Adds amplitude-driven oscillation on top of the timeline-driven angles.
     *
     * Two layers:
     *  - Idle breathing: always present, low amplitude, slow sin wave.
     *  - Talk motion: active above [AmplitudeSettings.silenceThreshold],
     *    proportional to smoothed amplitude, faster oscillation.
     */
    private fun applyAmplitudeMotion(dt: Float, rawAmp: Float) {
        val s = amplitudeSettings
        if (!s.enabled) return

        // Smooth amplitude with a one-pole low-pass filter
        smoothedAmplitude += (rawAmp - smoothedAmplitude) * (1f - s.smoothingFactor)

        val t = currentTimeSec.toDouble()

        // ── Idle breathing ────────────────────────────────────────────────────
        val breathOsc = sin(2.0 * PI * s.breathFreqHz * t).toFloat()
        if (idxTorso  >= 0) currentAngles[idxTorso]  += breathOsc * s.idleBreathAmplitude
        if (idxHead   >= 0) currentAngles[idxHead]   -= breathOsc * (s.idleBreathAmplitude * 0.5f)

        // ── Talk motion (only when above silence threshold) ───────────────────
        if (smoothedAmplitude > s.silenceThreshold) {
            val talkOsc  = sin(2.0 * PI * s.talkFreqHz * t).toFloat()
            val strength = smoothedAmplitude - s.silenceThreshold

            if (idxTorso >= 0)
                currentAngles[idxTorso] += talkOsc * s.talkTorsoAmplitude * strength
            if (idxHead  >= 0)
                currentAngles[idxHead]  += talkOsc * s.talkHeadNodAmplitude * strength

            if (s.armSwayEnabled) {
                val armOsc = sin(2.0 * PI * (s.talkFreqHz * 0.5) * t).toFloat()
                if (idxArmR >= 0)
                    currentAngles[idxArmR] += armOsc * s.armSwayAmplitude * strength
                if (idxArmL >= 0)
                    currentAngles[idxArmL] -= armOsc * s.armSwayAmplitude * strength
            }
        }

        // Apply angle constraints
        for (i in 0 until n) {
            val bone = bones[i]
            val totalMin = bone.minAngleDegrees?.let { bone.defaultAngleDegrees + it }
            val totalMax = bone.maxAngleDegrees?.let { bone.defaultAngleDegrees + it }
            if (totalMin != null && totalMax != null) {
                if (currentAngles[i] < totalMin) {
                    currentAngles[i] = totalMin
                    springVelocities[i] = 0f
                } else if (currentAngles[i] > totalMax) {
                    currentAngles[i] = totalMax
                    springVelocities[i] = 0f
                }
            }
        }
    }

    // ── Pose editor helpers ───────────────────────────────────────────────────

    /** Directly set a bone's total angle (for the interactive pose editor). */
    fun setBoneAngle(boneIndex: Int, totalAngleDegrees: Float) {
        if (boneIndex in 0 until n) {
            currentAngles[boneIndex]    = totalAngleDegrees
            springVelocities[boneIndex] = 0f
            baseAngles[boneIndex]       = totalAngleDegrees
        }
    }

    /** Returns the active rotation offset (total − default) for each bone. */
    fun captureRelativeAngles(): Map<String, Float> {
        return bones.mapIndexedNotNull { i, bone ->
            val offset = currentAngles[i] - bone.defaultAngleDegrees
            if (abs(offset) > 0.5f) bone.id to offset else null
        }.toMap()
    }
}
