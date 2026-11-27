package com.example.engine.acting

import com.example.engine.StickFigureRig
import kotlin.math.PI
import kotlin.math.sin

/**
 * A piecewise smoothstep blend of [channels] values between target vectors at change times, as an exact left-to-right
 * recurrence: the value at each change time is stored, so the blend has no depth cap, and the integral of
 * (channel [integ] - 1) has a closed form (used to turn a breathing RATE into a phase without a jump).
 *
 * Before the first change the [init] values hold. On [t_i, t_i+1): value = V_i + (target_i - V_i) * smoothstep((t - t_i) / blend),
 * with V_i the value reached at t_i. Pure data, immutable after init (I2); [evaluate] is allocation-free.
 * Mirrors `BlendTrack` in `tools/acting_proto/mood_proto.py`.
 */
class BlendTrack(
    private val init: DoubleArray,
    private val times: FloatArray,
    private val targets: Array<DoubleArray>,
    private val blendSec: Double,
    /** The channel whose (value - 1) is integrated into the warp, or -1 for none. */
    private val integ: Int = -1
) {
    val channels: Int = init.size
    val size: Int = times.size
    private val v = Array(size) { DoubleArray(channels) }
    private val cum = DoubleArray(size)

    init {
        if (size > 0) {
            for (k in 0 until channels) v[0][k] = init[k]
            if (integ >= 0) cum[0] = (init[integ] - 1.0) * times[0].toDouble()
            for (i in 0 until size - 1) {
                val len = times[i + 1].toDouble() - times[i].toDouble()
                val u = len / blendSec
                for (k in 0 until channels) {
                    val from = v[i][k]
                    val to = targets[i][k]
                    v[i + 1][k] = if (u >= 1.0) to else from + (to - from) * smooth(u)
                }
                if (integ >= 0) {
                    val from = v[i][integ]
                    val to = targets[i][integ]
                    cum[i + 1] = cum[i] + (from - 1.0) * len + (to - from) * blendSec * smoothIntegral(u)
                }
            }
        }
    }

    /** Writes the channel values at [t] into [out] and returns the warp (the integral of channel [integ] - 1 from 0 to t; 0 with none). */
    fun evaluate(t: Double, out: DoubleArray): Double {
        var lo = 0
        var hi = size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) {
            for (k in 0 until channels) out[k] = init[k]
            return if (integ >= 0) (init[integ] - 1.0) * t else 0.0
        }
        val len = t - times[idx].toDouble()
        val u = len / blendSec
        for (k in 0 until channels) {
            val from = v[idx][k]
            val to = targets[idx][k]
            out[k] = if (u >= 1.0) to else from + (to - from) * smooth(u)
        }
        if (integ < 0) return 0.0
        val from = v[idx][integ]
        val to = targets[idx][integ]
        return cum[idx] + (from - 1.0) * len + (to - from) * blendSec * smoothIntegral(u)
    }

    private fun smooth(x: Double): Double {
        val c = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x
        return c * c * (3.0 - 2.0 * c)
    }

    /** The integral of the smoothstep from 0 to [u] blend times. */
    private fun smoothIntegral(u: Double): Double = if (u >= 1.0) u - 0.5 else u * u * u - 0.5 * u * u * u * u
}

/**
 * WP2.6 mood idles (Craft, A4: how the body idles, never what happens). The breathing rate and depth and the talk/fidget
 * sway follow the current expression (calm below 1, energetic above, [ActingParams.MOOD_RATE] and friends), scaled by the
 * level's moodScale, and are off while a walk/jog keyframe is active.
 *
 * The layer ADDS (acted - legacy) to the frame the engine built, so the legacy amplitude motion survives under it:
 *   breath: d = free * a * (depth * sin(2 pi f (t + W)) - sin(2 pi f t)); torso += d, head -= 0.5 d
 *   sway:   bone += free * (sway - 1) * (the legacy talk-sway and fidget contribution to that bone)
 * with W the closed-form warp from [BlendTrack], so the phase is continuous when the rate changes, and exactly 0 when every
 * multiplier is 1 (then the delta is exactly 0). Pure functions of (timeline, level, time, the recorded legacy frame).
 */
object MoodModel {
    const val CH_RATE = 0
    const val CH_DEPTH = 1
    const val CH_SWAY = 2

    /** The (rate, depth, sway) an expression settles to at [scale] (the level's moodScale), each clamped to the allowed range. */
    fun target(expr: Int, scale: Double): DoubleArray {
        val e = FaceTimeline.clampExpr(expr)
        fun one(table: DoubleArray) = (1.0 + scale * (table[e] - 1.0)).coerceIn(ActingParams.MOOD_MIN, ActingParams.MOOD_MAX)
        return doubleArrayOf(one(ActingParams.MOOD_RATE), one(ActingParams.MOOD_DEPTH), one(ActingParams.MOOD_SWAY))
    }

    /** The mood of a face timeline at a level scale: three channels (rate, depth, sway), the rate integrated into the warp. */
    fun bakeMood(face: FaceTimeline, scale: Double): BlendTrack = BlendTrack(
        target(face.expr0, scale), face.times, Array(face.size) { target(face.exprs[it], scale) }, ActingParams.MOOD_BLEND_SEC, CH_RATE
    )

    /**
     * The locomotion gate: one channel, 1 = free (mood on) and 0 = a walk/jog keyframe is active. Toggles are the keyframe
     * times where the flag changes (the same-time rule of [FaceTimeline.fromKeyframes]: the later keyframe wins).
     */
    fun bakeFree(keyframeTimes: FloatArray, locomotion: BooleanArray): BlendTrack {
        val n = keyframeTimes.size
        if (n == 0) return BlendTrack(doubleArrayOf(1.0), FloatArray(0), emptyArray(), ActingParams.MOOD_LOCO_RAMP_SEC)
        val t = ArrayList<Float>()
        val f = ArrayList<Boolean>()
        val first = locomotion[0]
        var prev = first
        for (i in 1 until n) {
            val flag = locomotion[i]
            if (t.isNotEmpty() && t[t.size - 1] == keyframeTimes[i]) {
                val before = if (f.size >= 2) f[f.size - 2] else first
                if (flag == before) { t.removeAt(t.size - 1); f.removeAt(f.size - 1) } else f[f.size - 1] = flag
                prev = flag
                continue
            }
            if (flag != prev) { t.add(keyframeTimes[i]); f.add(flag); prev = flag }
        }
        return BlendTrack(
            doubleArrayOf(if (first) 0.0 else 1.0), FloatArray(t.size) { t[it] },
            Array(f.size) { doubleArrayOf(if (f[it]) 0.0 else 1.0) }, ActingParams.MOOD_LOCO_RAMP_SEC
        )
    }

    /** Per-thread scratch so [apply] allocates nothing (the layer may run on the render and the UI thread). */
    private class Scratch { val mood = DoubleArray(3); val free = DoubleArray(1) }
    private val scratch = object : ThreadLocal<Scratch>() { override fun initialValue() = Scratch() }

    /**
     * Adds the mood to [out] (already holding the stage-1 pose). [base] carries what the legacy engine recorded for this
     * frame (`ampBreathDeg`, `ampBreathHz`, `ampSway`); a frame with no amplitude motion has nothing to scale and is left alone.
     */
    fun apply(base: ActingFrame, mood: BlendTrack, free: BlendTrack, t: Double, torso: Int, head: Int, armR: Int, armL: Int, out: ActingFrame) {
        val breathAmp = base.ampBreathDeg
        val sw = base.ampSway
        val anySway = sw[0] != 0f || sw[1] != 0f || sw[2] != 0f || sw[3] != 0f
        if (breathAmp == 0f && !anySway) return
        val s = scratch.get()!!
        free.evaluate(t, s.free)
        val w = s.free[0]
        if (w <= 0.0) return
        val warp = mood.evaluate(t, s.mood)
        val depth = s.mood[CH_DEPTH]
        val sway = s.mood[CH_SWAY]
        if (breathAmp != 0f && base.ampBreathHz > 0f) {
            val hz = base.ampBreathHz
            val acted = depth * sin(2.0 * PI * hz * (t + warp))
            val legacy = sin(2.0 * PI * hz * t)
            val d = w * breathAmp * (acted - legacy)
            if (d != 0.0) {
                nudge(base, out, torso, d)
                nudge(base, out, head, -0.5 * d)
            }
        }
        val g = w * (sway - 1.0)
        if (g != 0.0 && anySway) {
            nudge(base, out, torso, g * sw[0])
            nudge(base, out, head, g * sw[1])
            nudge(base, out, armR, g * sw[2])
            nudge(base, out, armL, g * sw[3])
        }
    }

    /** Adds [d] degrees to a bone, finite and inside the joint limits widened to include the base value (the rule of every other stage). */
    private fun nudge(base: ActingFrame, out: ActingFrame, idx: Int, d: Double) {
        if (idx < 0 || idx >= out.angles.size || d == 0.0) return
        val b = base.angles[idx]
        var v = (out.angles[idx] + d).toFloat()
        if (!v.isFinite()) return
        val bone = StickFigureRig.BONES[idx]
        val lo = bone.minAngleDegrees
        val hi = bone.maxAngleDegrees
        if (lo != null && hi != null) v = v.coerceIn(minOf(bone.defaultAngleDegrees + lo, b), maxOf(bone.defaultAngleDegrees + hi, b))
        out.angles[idx] = v
    }
}
