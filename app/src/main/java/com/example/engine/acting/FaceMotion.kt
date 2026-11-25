package com.example.engine.acting

import com.example.engine.BakedKeyframe
import com.example.engine.Expression
import com.example.engine.ResolvedFace
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The expression changes of a compiled timeline (WP2.3): the expression that holds before the first change and the
 * times of the changes. The legacy engine picks the expression of the rightmost keyframe at or before the time (the first
 * keyframe's before any), so a change exists where a keyframe differs from the one before it. Two keyframes at the same
 * time keep the last (the legacy rule). Pure data, immutable after init (I2).
 */
class FaceTimeline(
    /** The expression before the first change. */
    val expr0: Int,
    /** Change times, ascending, and the expression that holds from each. */
    val times: FloatArray,
    val exprs: IntArray
) {
    val size: Int = times.size

    companion object {
        fun clampExpr(e: Int): Int = if (e < 0 || e > 5) Expression.NORMAL else e

        /** The changes of a compiled timeline. */
        fun fromKeyframes(keyframes: List<BakedKeyframe>): FaceTimeline {
            val t = ArrayList<Float>()
            val e = ArrayList<Int>()
            val first = if (keyframes.isEmpty()) Expression.NORMAL else clampExpr(keyframes[0].expression)
            var prev = first
            for (i in 1 until keyframes.size) {
                val k = keyframes[i]
                val ex = clampExpr(k.expression)
                if (t.isNotEmpty() && t[t.size - 1] == k.timeSec) {
                    // Same time as the previous change: the later keyframe wins; if it restores the earlier state, the change is void.
                    val before = if (e.size >= 2) e[e.size - 2] else first
                    if (ex == before) { t.removeAt(t.size - 1); e.removeAt(e.size - 1) } else e[e.size - 1] = ex
                    prev = ex
                    continue
                }
                if (ex != prev) { t.add(k.timeSec); e.add(ex); prev = ex }
            }
            return FaceTimeline(first, FloatArray(t.size) { t[it] }, IntArray(e.size) { e[it] })
        }
    }
}

/**
 * WP2.3 face model, a pure function of (face timeline, level, seed, time): expression blending with an anticipation
 * squint and a take, brows on every expression, and idle gaze with seeded saccades, a head that follows the eyes and a
 * blink coupled to large shifts. Craft in the sense of A4 (how a discrete expression is executed; gaze is idle only, never
 * a script target; `look_at` of WP2.5 will feed targets in here). Allocation-free: scalars and the reused [ResolvedFace].
 *
 * Mirrors `tools/acting_proto/face_proto.py` line for line; `FaceMotionTest` pins the two together. In steady state, after
 * the settle time, eyes and mouth equal the legacy numbers for the expression (the targets come from [ResolvedFace.setLegacy]).
 */
object FaceMotionModel {

    private const val N_EXPR = 6
    private const val N_PARAM = 5   // eye radius fraction, mouth width mul, mouth cy bias, brow height, brow inner lift

    /** Per expression: the five blended parameters, from the legacy numbers plus the acting brow table. */
    private val TARGET: Array<DoubleArray> = Array(N_EXPR) { e ->
        val f = ResolvedFace().setLegacy(e, 1f)
        doubleArrayOf(
            f.eyeRadiusFrac.toDouble(), f.mouthWidthMul.toDouble(), f.mouthCyBiasFrac.toDouble(),
            ActingParams.FACE_BROW_HEIGHT[e], ActingParams.FACE_BROW_LIFT[e]
        )
    }

    private const val GOLDEN = -7046029254386353131L        // 0x9E3779B97F4A7C15
    private const val MIX1 = -4658895280553007687L          // 0xBF58476D1CE4E5B9
    private const val MIX2 = -7723592293110705685L          // 0x94D049BB133111EB
    private const val FNV_PRIME = 1099511628211L            // 0x100000001B3

    private fun smooth(x: Double): Double {
        val c = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x
        return c * c * (3 - 2 * c)
    }

    private fun fade(u: Double): Double {
        val s = ActingParams.FACE_SETTLE_SEC
        return 1.0 - smooth((u - 0.5 * s) / (0.5 * s))
    }

    /** splitmix64 finalizer: identical in the Python prototype and on every device. */
    fun mix64(x: Long): Long {
        var z = x + GOLDEN
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Deterministic uniform [0, 1) from (seed, k, salt). */
    fun unit(seed: Long, k: Long, salt: Int): Double {
        val h = mix64(seed xor mix64(k * FNV_PRIME xor (salt.toLong() * GOLDEN)))
        return (h ushr 11).toDouble() / 9007199254740992.0   // 2^53
    }

    // ---- expression blend -------------------------------------------------------------------------------------------

    private fun blendComp(tl: FaceTimeline, i: Int, t: Double, c: Int, blendSec: Double, depth: Int): Double {
        if (i < 0) return TARGET[tl.expr0][c]
        val tgt = TARGET[tl.exprs[i]][c]
        val tc = tl.times[i].toDouble()
        val frm = if (depth >= ActingParams.FACE_CHAIN_DEPTH) TARGET[if (i - 1 < 0) tl.expr0 else tl.exprs[i - 1]][c]
                  else blendComp(tl, i - 1, tc, c, blendSec, depth + 1)
        val s = smooth((t - tc) / blendSec)
        return frm + (tgt - frm) * s
    }

    /** Index of the last change at or before [t] (-1 before the first). */
    fun activeChange(tl: FaceTimeline, t: Double): Int {
        var lo = 0
        var hi = tl.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tl.times[mid] <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        return idx
    }

    /** One change's contribution to the eye-height multiplier (see the prototype's `eye_height_term`). */
    fun eyeHeightTerm(expr: Int, u: Double, lv: ActingLevel): Double {
        val depth = ActingParams.FACE_ANT_DEPTH[expr] * lv.faceTakeScale
        val amp = ActingParams.FACE_TAKE_AMP[expr] * lv.faceTakeScale
        val a = lv.faceAnticipationSec.toDouble()
        if (u < -a || u >= ActingParams.FACE_SETTLE_SEC) return 0.0
        if (u < 0) return -depth * smooth((u + a) / a)
        val z = ActingParams.FACE_ZETA
        val sq = sqrt(1 - z * z)
        val om = Math.PI / lv.expressionBlendSec / sq
        val od = om * sq
        val env = exp(-z * om * u)
        val peakT = atan2(sq, z) / od
        val kick = amp / (exp(-z * om * peakT) * sin(od * peakT))
        val term = -depth * (cos(od * u) + z * om / od * sin(od * u)) + kick * sin(od * u)
        return fade(u) * env * term
    }

    /** Anticipation squint and take as a multiplier of the eye height: the sum of the terms of every change whose window holds [t]. */
    fun eyeHeightMul(tl: FaceTimeline, t: Double, lv: ActingLevel): Double {
        var m = 1.0
        if (tl.size > 0) {
            // First change with time >= t - settle (changes before that are exactly neutral by now).
            val from = t - ActingParams.FACE_SETTLE_SEC
            var lo = 0
            var hi = tl.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (tl.times[mid] < from) lo = mid + 1 else hi = mid }
            val until = t + lv.faceAnticipationSec
            var j = lo
            while (j < tl.size && tl.times[j] <= until) {
                m += eyeHeightTerm(tl.exprs[j], t - tl.times[j], lv)
                j++
            }
        }
        return max(0.2, m)
    }

    // ---- gaze -------------------------------------------------------------------------------------------------------

    fun saccadeTime(seed: Long, k: Long): Double =
        k * ActingParams.GAZE_SLOT_SEC + ActingParams.GAZE_SLOT_SPREAD_SEC * unit(seed, k, 1)

    /** Writes the target of saccade [k] (head radii: +x screen right, +y up) into [out] (size 2). */
    fun gazeTarget(seed: Long, k: Long, scale: Double, out: DoubleArray) {
        if (k < 0) { out[0] = 0.0; out[1] = 0.0; return }
        val cap = ActingParams.GAZE_CAP * scale
        if (unit(seed, k, 2) < ActingParams.GAZE_P_CENTRE) {
            out[0] = cap * 0.25 * (unit(seed, k, 3) * 2 - 1)
            out[1] = cap * 0.20 * (unit(seed, k, 4) * 2 - 1)
        } else {
            val ang = 2 * Math.PI * unit(seed, k, 5)
            val mag = cap * (0.45 + 0.55 * unit(seed, k, 6))
            out[0] = mag * cos(ang)
            out[1] = mag * 0.7 * sin(ang)
        }
    }

    /** The latest saccade at or before [t], or -1. */
    fun latestSaccade(seed: Long, t: Double): Long {
        val k = floor(t / ActingParams.GAZE_SLOT_SEC).toLong()
        var kk = k
        while (kk >= k - 2) {
            if (kk >= 0 && saccadeTime(seed, kk) <= t) return kk
            kk--
        }
        return -1
    }

    /** Per-thread scratch so the per-frame evaluation allocates nothing (I7): [a] and [b] hold two gaze targets, [c] and [d] results. */
    private class Scratch { val a = DoubleArray(2); val b = DoubleArray(2); val c = DoubleArray(2); val d = DoubleArray(2) }
    private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

    /** Gaze at [t] into [out] (size 2); [ramp] is how long a move takes (a saccade for the eyes, slower for the head). */
    fun gazeAt(seed: Long, t: Double, scale: Double, ramp: Double, out: DoubleArray) {
        val k = latestSaccade(seed, t)
        if (k < 0) { out[0] = 0.0; out[1] = 0.0; return }
        val sc = scratch.get()
        val prev = sc.a
        val cur = sc.b
        gazeTarget(seed, k - 1, scale, prev)
        gazeTarget(seed, k, scale, cur)
        val s = smooth((t - saccadeTime(seed, k)) / ramp)
        out[0] = prev[0] + (cur[0] - prev[0]) * s
        out[1] = prev[1] + (cur[1] - prev[1]) * s
    }

    /** Head tilt in degrees (clockwise positive): the head follows the eyes later and slower, at most [ActingParams.HEAD_TILT_MAX_DEG]. */
    fun headTilt(seed: Long, t: Double, scale: Double): Double {
        if (scale <= 0.0) return 0.0
        val g = scratch.get().c
        gazeAt(seed, t - ActingParams.GAZE_EYE_LEAD_SEC, scale, ActingParams.GAZE_HEAD_RAMP_SEC, g)
        val v = ActingParams.HEAD_TILT_MAX_DEG * g[0] / (ActingParams.GAZE_CAP * scale)
        return max(-ActingParams.HEAD_TILT_MAX_DEG, min(ActingParams.HEAD_TILT_MAX_DEG, v))
    }

    /** Openness (0..1) of a blink coupled to a large gaze shift (seeded); 1 when there is none. */
    fun gazeBlink(seed: Long, t: Double, scale: Double, blinkSec: Double): Double {
        if (scale <= 0.0) return 1.0
        val k = latestSaccade(seed, t)
        if (k < 0) return 1.0
        val sc = scratch.get()
        val prev = sc.a
        val cur = sc.b
        gazeTarget(seed, k - 1, scale, prev)
        gazeTarget(seed, k, scale, cur)
        val shift = hypot(cur[0] - prev[0], cur[1] - prev[1])
        if (shift <= ActingParams.GAZE_BLINK_SHIFT * scale || unit(seed, k, 7) >= ActingParams.GAZE_P_BLINK) return 1.0
        val el = t - saccadeTime(seed, k)
        if (el >= blinkSec) return 1.0
        val half = blinkSec / 2
        return if (el < half) 1.0 - el / half else (el - half) / half
    }

    // ---- resolved face ----------------------------------------------------------------------------------------------

    /**
     * Fills [out] with the acted face at [timeSec]. [legacyOpenness] is the engine's blink (script and natural) and
     * [blinkSec] its duration; the coupled gaze blink is combined with it by taking the smaller openness. Returns the head
     * tilt in degrees to add to the head bone (0 when the level has no gaze).
     */
    fun resolve(tl: FaceTimeline, lv: ActingLevel, seed: Long, timeSec: Double, legacyOpenness: Float, blinkSec: Float, out: ResolvedFace): Double {
        val blend = max(1e-3, lv.expressionBlendSec.toDouble())
        val i = activeChange(tl, timeSec)
        out.eyeRadiusFrac = blendComp(tl, i, timeSec, 0, blend, 0).toFloat()
        out.mouthWidthMul = blendComp(tl, i, timeSec, 1, blend, 0).toFloat()
        out.mouthCyBiasFrac = blendComp(tl, i, timeSec, 2, blend, 0).toFloat()
        out.browHeight = blendComp(tl, i, timeSec, 3, blend, 0).toFloat()
        out.browInnerLift = blendComp(tl, i, timeSec, 4, blend, 0).toFloat()
        out.browsVisible = true
        out.eyeHeightMul = eyeHeightMul(tl, timeSec, lv).toFloat()
        val gs = lv.gazeScale.toDouble()
        var open = legacyOpenness
        if (gs > 0.0) {
            val g = scratch.get().d
            gazeAt(seed, timeSec, gs, ActingParams.GAZE_SACCADE_SEC, g)
            out.gazeX = g[0].toFloat()
            out.gazeY = g[1].toFloat()
            val gb = gazeBlink(seed, timeSec, gs, max(0.02, blinkSec.toDouble())).toFloat()
            if (gb < open) open = gb
        } else {
            out.gazeX = 0f
            out.gazeY = 0f
        }
        out.eyeOpenness = open
        return headTilt(seed, timeSec, gs)
    }
}
