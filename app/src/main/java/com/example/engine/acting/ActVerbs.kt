package com.example.engine.acting

import com.example.engine.BakedKeyframe
import com.example.engine.StickFigureRig
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** All constants of the intent verbs in one place (A8). They mirror `tools/acting_proto/verbs_proto.py`; change both together. */
object VerbParams {
    /** Head radii: the gaze offset of a directed look (the idle gaze cap is [ActingParams.GAZE_CAP]). */
    const val LOOK_CAP: Double = 0.30
    /** Head turn (a tilt) that goes with a full-cap look, degrees. */
    const val HEAD_LOOK_DEG: Double = 7.0
    const val LOOK_IN: Double = 0.18
    const val LOOK_OUT: Double = 0.25
    /** A look_at that no later gaze verb replaces lasts this long (seconds), then falls over [LOOK_OUT]. */
    const val LOOK_MAX_HOLD: Double = 2.0
    /** point_at also looks at its target, this fraction of a full look. */
    const val POINT_LOOK_SHARE: Double = 0.6
    /** Fraction of the arm length kept off the full stretch, so the elbow never locks. */
    const val REACH_MARGIN: Double = 0.02
    /** The longest any verb window can be (look_at: hold + fall), used to bound the search. */
    const val MAX_WINDOW: Double = 2.3

    /** Natural duration per verb, index = position in [ActVocabulary.VERBS] (look_at: the hold is data dependent, see [ActVerbTimeline]). */
    val DURATION: DoubleArray = doubleArrayOf(0.45, 0.60, 0.35, 0.70, 0.80, 1.00, 1.80, LOOK_MAX_HOLD + LOOK_OUT, 0.80, 1.80)

    const val NOD = 0
    const val SHAKE_HEAD = 1
    const val EMPHASIZE = 2
    const val SURPRISE = 3
    const val THINK = 4
    const val LAUGH = 5
    const val SAD = 6
    const val LOOK_AT = 7
    const val LOOK_AWAY = 8
    const val POINT_AT = 9
}

/**
 * Where the figure stands, for the verbs that aim at something on the screen (look_at, point_at): the canvas aspect
 * (width / height), the pelvis anchor in normalized screen coordinates (y down) and the character scale. From the project's
 * appearance; the defaults are the appearance defaults. Dual-aspect exports share one acting evaluation, so they use the primary aspect.
 */
class ActingStage(val aspect: Float, val rootX: Float, val rootY: Float, val characterScale: Float) {
    companion object {
        val DEFAULT = ActingStage(9f / 16f, 0.50f, 0.52f, 1.0f)

        fun of(aspect: Float, rootX: Float, rootY: Float, characterScale: Float): ActingStage =
            if (aspect.isFinite() && aspect > 0.1f && aspect < 10f) ActingStage(aspect, rootX, rootY, characterScale) else DEFAULT
    }
}

/** The stage as resolved for one frame (the figure overrides applied); reused, so the evaluation allocates nothing. */
class VerbPlacement {
    var aspect: Double = 9.0 / 16.0
    var rootX: Double = 0.5
    var rootY: Double = 0.52
    var characterScale: Double = 1.0

    /** Rig units to canvas-height units: portrait scales with the width, landscape with 9/16 of the height. */
    fun scaleH(): Double = (if (aspect < 1.0) aspect else 0.5625) * characterScale
}

/** What the verbs add to the acted (or base) frame at one time: bone offsets in degrees and face deltas. Reused per frame. */
class VerbOffsets {
    val b = DoubleArray(StickFigureRig.BONE_COUNT)
    var eyeH = 0.0
    var eyeR = 0.0
    var gx = 0.0
    var gy = 0.0
    /** Weight (0..1) with which a directed gaze replaces the idle gaze. */
    var gw = 0.0
    var browH = 0.0
    var browL = 0.0
    var mouthW = 0.0
    var mouthB = 0.0
    /** Mouth opening of a laugh (0 = not laughing). */
    var laugh = 0.0
    /** True when at least one verb window holds the time (even at an instant where its offsets are zero). */
    var active = false

    fun clear() {
        java.util.Arrays.fill(b, 0.0)
        eyeH = 0.0; eyeR = 0.0; gx = 0.0; gy = 0.0; gw = 0.0; browH = 0.0; browL = 0.0; mouthW = 0.0; mouthB = 0.0; laugh = 0.0
        active = false
    }
}

/**
 * The `act` verbs of a compiled timeline, validated and flattened once when the timeline loads (pure data, immutable, I2).
 * Verbs with an unknown name, or a look_at / point_at without a valid target, are dropped (I4). Sorted by time.
 */
class ActVerbTimeline(
    val times: FloatArray,
    val kinds: IntArray,
    val intensity: FloatArray,
    val targets: Array<ActVocabulary.ActTarget?>,
    /** look_at only: when the hold ends and when the look has fully faded, relative to the verb's start. */
    val lookEnd: FloatArray,
    val lookFall: FloatArray
) {
    val size: Int = times.size
    val isEmpty: Boolean get() = size == 0

    /** True when some verb's window may hold [t] (cheap: used to skip the evaluation, and the whole layer at OFF). */
    fun mayBeActive(t: Float): Boolean {
        if (size == 0) return false
        var lo = 0
        var hi = size - 1
        var idx = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (times[mid] <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        return idx >= 0 && t - times[idx] < VerbParams.MAX_WINDOW.toFloat()
    }

    /** The index of the last verb that starts at or before [t], or -1. */
    fun lastStartAtOrBefore(t: Double): Int {
        var lo = 0
        var hi = size - 1
        var idx = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (times[mid] <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        return idx
    }

    fun windowEnd(i: Int): Double = if (kinds[i] == VerbParams.LOOK_AT) lookFall[i].toDouble() else VerbParams.DURATION[kinds[i]]

    companion object {
        val EMPTY = ActVerbTimeline(FloatArray(0), IntArray(0), FloatArray(0), arrayOfNulls(0), FloatArray(0), FloatArray(0))

        fun fromKeyframes(keyframes: List<BakedKeyframe>): ActVerbTimeline {
            val t = ArrayList<Float>()
            val k = ArrayList<Int>()
            val a = ArrayList<Float>()
            val tg = ArrayList<ActVocabulary.ActTarget?>()
            for (kf in keyframes) {
                val name = kf.act
                if (!ActVocabulary.isVerb(name)) continue
                val kind = ActVocabulary.VERBS.indexOf(name)
                val target = ActVocabulary.parseTarget(kf.actTarget)
                if (ActVocabulary.needsTarget(name) && target == null) continue
                val inten = kf.actIntensity
                t.add(kf.timeSec); k.add(kind); a.add(if (inten.isNaN()) 0.7f else inten.coerceIn(0f, 1f)); tg.add(target)
            }
            val n = t.size
            val times = FloatArray(n) { t[it] }
            val kinds = IntArray(n) { k[it] }
            val lookEnd = FloatArray(n)
            val lookFall = FloatArray(n)
            for (i in 0 until n) {
                if (kinds[i] != VerbParams.LOOK_AT) continue
                var end = VerbParams.LOOK_MAX_HOLD
                var fall = VerbParams.LOOK_MAX_HOLD + VerbParams.LOOK_OUT
                // The next gaze verb replaces this look: the hold ends where it starts and the two cross-fade over the rise.
                var j = i + 1
                while (j < n && !ActVocabulary.isGazeVerb(ActVocabulary.VERBS[kinds[j]])) j++
                if (j < n) {
                    val gap = (times[j] - times[i]).toDouble()
                    if (gap < VerbParams.LOOK_MAX_HOLD) {
                        end = max(gap, VerbParams.LOOK_IN + 0.02)
                        fall = end + VerbParams.LOOK_IN
                    }
                }
                lookEnd[i] = end.toFloat()
                lookFall[i] = fall.toFloat()
            }
            return ActVerbTimeline(times, kinds, FloatArray(n) { a[it] }, Array(n) { tg[it] }, lookEnd, lookFall)
        }
    }
}

/**
 * The verb model, a pure function of (timeline, time, placement, base angles): additive offsets over the pose the engine (and
 * the acting Craft stages) produced. Every shape starts and ends at exactly zero with zero slope, so a verb returns to the base
 * pose without a pop and overlapping verbs simply add. Direction (A4): it runs at every acting intensity, including OFF.
 * Mirrors `tools/acting_proto/verbs_proto.py`; `ActVerbTest` pins the two together. Allocation-free (I7).
 */
object ActVerbModel {
    private val IX_TORSO = StickFigureRig.BONE_INDEX["torso"] ?: 0
    private val IX_HEAD = StickFigureRig.BONE_INDEX["head"] ?: 1
    private val IX_UR = StickFigureRig.BONE_INDEX["upper_arm_r"] ?: 2
    private val IX_FR = StickFigureRig.BONE_INDEX["lower_arm_r"] ?: 3
    private val IX_UL = StickFigureRig.BONE_INDEX["upper_arm_l"] ?: 4
    private val IX_FL = StickFigureRig.BONE_INDEX["lower_arm_l"] ?: 5
    private val TORSO_LEN = StickFigureRig.BONES[IX_TORSO].normalizedLength.toDouble()
    private val HEAD_LEN = StickFigureRig.BONES[IX_HEAD].normalizedLength.toDouble()
    private val L1 = StickFigureRig.BONES[IX_UR].normalizedLength.toDouble()
    private val L2 = StickFigureRig.BONES[IX_FR].normalizedLength.toDouble()

    private class Scratch { val xy = FloatArray(2); val ik = DoubleArray(4) }
    private val scratchLocal: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }
    private fun scratch(): Scratch = scratchLocal.get()!!

    fun smooth(x: Double): Double {
        val c = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x
        return c * c * (3 - 2 * c)
    }

    /** sin^2 lobe: 0 at both ends with zero slope, 1 in the middle. */
    fun bump(u: Double, d: Double): Double {
        if (u <= 0.0 || u >= d) return 0.0
        val s = sin(Math.PI * u / d)
        return s * s
    }

    /** 0 to 1 over [rise], 1 until [holdEnd], back to 0 at [fallEnd] (smoothsteps). */
    fun plateau(u: Double, rise: Double, holdEnd: Double, fallEnd: Double): Double {
        if (u <= 0.0 || u >= fallEnd) return 0.0
        return smooth(u / rise) * (1 - smooth((u - holdEnd) / (fallEnd - holdEnd)))
    }

    fun wrap(a: Double): Double = (((a + 180.0) % 360.0) + 360.0) % 360.0 - 180.0

    /**
     * One verb's offsets at local time [u] (not point_at: see [pointWeight]) added into [o]. ([dirx], [diry]) is the unit look
     * direction (+y up); [end] and [fall] are the look_at hold end and fade end.
     */
    fun addVerb(kind: Int, a: Double, u: Double, dirx: Double, diry: Double, end: Double, fall: Double, o: VerbOffsets) {
        val cap = VerbParams.LOOK_CAP
        when (kind) {
            VerbParams.NOD -> {
                val w = bump(u, 0.45)
                o.b[IX_HEAD] += 16.0 * a * w
                o.b[IX_TORSO] += 3.0 * a * w
                o.gy += -0.55 * cap * a * w; o.gw += 0.6 * a * w
                o.eyeH += -0.20 * a * w
            }
            VerbParams.SHAKE_HEAD -> {
                val env = bump(u, 0.6)
                val s = sin(4 * Math.PI * u / 0.6)
                o.b[IX_HEAD] += 18.0 * a * s * env
                o.gx += 0.6 * cap * a * s * env; o.gw += 0.6 * a * env
                o.b[IX_TORSO] += -2.0 * a * s * env
            }
            VerbParams.EMPHASIZE -> {
                val w = bump(u, 0.35)
                o.b[IX_TORSO] += 6.0 * a * w
                o.b[IX_HEAD] += -7.0 * a * w
                o.b[IX_UR] += -16.0 * a * w
                o.b[IX_UL] += 16.0 * a * w
                o.b[IX_FR] += -10.0 * a * w
                o.b[IX_FL] += 10.0 * a * w
                o.browH += 0.25 * a * w
            }
            VerbParams.SURPRISE -> {
                val sq = if (u < 0.12) bump(u, 0.12) else 0.0
                val tk = if (u < 0.10) 0.0 else smooth((u - 0.10) / 0.10) * (1 - smooth((u - 0.28) / 0.42))
                val lean = plateau(u, 0.16, 0.30, 0.70)
                o.eyeH += -0.35 * a * sq + 0.50 * a * tk
                o.eyeR += 0.045 * a * tk
                o.browH += 0.55 * a * tk
                o.b[IX_TORSO] += -7.0 * a * lean
                o.b[IX_HEAD] += -5.0 * a * lean
                o.b[IX_UR] += -18.0 * a * lean
                o.b[IX_UL] += 18.0 * a * lean
                o.b[IX_FR] += -8.0 * a * lean
                o.b[IX_FL] += 8.0 * a * lean
                o.mouthW += -0.10 * a * lean
            }
            VerbParams.THINK -> {
                val w = plateau(u, 0.22, 0.52, 0.80)
                o.b[IX_HEAD] += 15.0 * a * w
                o.b[IX_TORSO] += 2.0 * a * w
                o.gx += 0.75 * cap * a * w; o.gy += 0.8 * cap * a * w; o.gw += a * w
                o.browL += 0.30 * a * w; o.browH += 0.10 * a * w
                o.eyeH += -0.10 * a * w
            }
            VerbParams.LAUGH -> {
                val env = if (u > 0.0 && u < 1.0) smooth(u / 0.15) * (1 - smooth((u - 0.8) / 0.2)) else 0.0
                val s = sin(2 * Math.PI * 3.5 * u / 1.0)
                o.b[IX_TORSO] += 3.5 * a * s * env
                o.b[IX_HEAD] += -2.5 * a * s * env
                o.b[IX_UR] += -4.0 * a * s * env
                o.b[IX_UL] += 4.0 * a * s * env
                o.eyeH += -0.30 * a * env
                o.mouthW += 0.30 * a * env; o.mouthB += -0.03 * a * env
                o.laugh = max(o.laugh, (0.55 + 0.35 * abs(s)) * a * env)
            }
            VerbParams.SAD -> {
                val w = plateau(u, 0.40, 1.30, 1.80)
                o.b[IX_TORSO] += 5.0 * a * w
                o.b[IX_HEAD] += 16.0 * a * w
                o.b[IX_UR] += 7.0 * a * w
                o.b[IX_UL] += -7.0 * a * w
                o.gy += -0.8 * cap * a * w; o.gw += a * w
                o.browL += 0.50 * a * w; o.browH += -0.10 * a * w
                o.eyeH += -0.20 * a * w
                o.mouthB += 0.05 * a * w; o.mouthW += -0.10 * a * w
            }
            VerbParams.LOOK_AT, VerbParams.LOOK_AWAY -> {
                val w = if (kind == VerbParams.LOOK_AT) plateau(u, VerbParams.LOOK_IN, end, fall) else plateau(u, 0.15, 0.50, 0.80)
                // Intensity does not scale a look: it points where it points (the verb is its own full-strength direction).
                o.gx += dirx * cap * w; o.gy += diry * cap * w
                o.gw += w
                o.b[IX_HEAD] += VerbParams.HEAD_LOOK_DEG * dirx * w
            }
        }
    }

    /** point_at weight: rise with a small wind-back, hold, fall; zero at both ends with zero slope. */
    fun pointWeight(u: Double): Double {
        val d = VerbParams.DURATION[VerbParams.POINT_AT]
        val main = plateau(u, 0.35, 1.55, d)
        val ant = -0.08 * bump(u, 0.12)
        return main + ant * (1 - smooth(u / 0.2))
    }

    /**
     * Two-bone IK. Writes (upper relative, fore relative, reached 1/0, distance) into [out]: the relative angles in degrees that put
     * the wrist on (tx, ty) from the shoulder (sx, sy), y down; [side] +1 = the right arm (screen right), -1 = the left arm, so the elbow
     * points down. A target out of reach is clamped to the reach, just short of a locked elbow.
     */
    fun ikTwoBone(sx: Double, sy: Double, tx: Double, ty: Double, parentAbsDeg: Double, side: Int, l1: Double, l2: Double, out: DoubleArray) {
        val dx = tx - sx
        val dy = ty - sy
        val d = hypot(dx, dy)
        val lo = abs(l1 - l2) + 1e-4
        val hi = (l1 + l2) * (1 - VerbParams.REACH_MARGIN)
        val dc = min(max(d, lo), hi)
        val reached = d >= lo && d <= hi
        val ang = atan2(dy, dx)
        val c2 = (dc * dc - l1 * l1 - l2 * l2) / (2 * l1 * l2)
        var th2 = acos(max(-1.0, min(1.0, c2)))
        th2 = -side * th2
        val al = atan2(l2 * sin(th2), l1 + l2 * cos(th2))
        val upAbs = ang - al
        out[0] = wrap(Math.toDegrees(upAbs) - parentAbsDeg)
        out[1] = wrap(Math.toDegrees(th2))
        out[2] = if (reached) 1.0 else 0.0
        out[3] = d
    }

    // ---- evaluation -----------------------------------------------------------------------------------------------

    private fun headPos(angles: FloatArray, o: VerbOffsets, p: VerbPlacement, out: DoubleArray) {
        val ta = Math.toRadians(angles[IX_TORSO] + o.b[IX_TORSO])
        val ha = Math.toRadians(angles[IX_TORSO] + o.b[IX_TORSO] + angles[IX_HEAD] + o.b[IX_HEAD])
        val hx = TORSO_LEN * cos(ta) + HEAD_LEN * cos(ha)
        val hy = TORSO_LEN * sin(ta) + HEAD_LEN * sin(ha)
        val sc = p.scaleH()
        out[0] = p.rootX + hx * sc / p.aspect
        out[1] = p.rootY + hy * sc
    }

    /** Resolves a target to a screen point (normalized, y down) into [xy]; false when it cannot be resolved right now. */
    private fun screenPoint(tg: ActVocabulary.ActTarget, t: Double, targets: ActingTargets, xy: FloatArray): Boolean = when (tg.kind) {
        ActVocabulary.T_POINT -> { xy[0] = tg.x; xy[1] = tg.y; true }
        ActVocabulary.T_LAYER -> targets.resolve(tg.key ?: "", t.toFloat(), xy)
        else -> false
    }

    private val HEAD_XY = ThreadLocal.withInitial { DoubleArray(2) }

    /** The unit gaze direction (+y up) for a look target; false = skip the verb (an unresolvable layer). */
    private fun lookDir(tg: ActVocabulary.ActTarget?, t: Double, p: VerbPlacement, angles: FloatArray, o: VerbOffsets,
                        targets: ActingTargets, dir: DoubleArray): Boolean {
        dir[0] = 0.0; dir[1] = 0.0
        if (tg == null) return true
        when (tg.kind) {
            ActVocabulary.T_LEFT -> { dir[0] = -1.0 }
            ActVocabulary.T_RIGHT -> { dir[0] = 1.0 }
            ActVocabulary.T_UP -> { dir[1] = 1.0 }
            ActVocabulary.T_DOWN -> { dir[1] = -1.0 }
            ActVocabulary.T_CAMERA -> {}
            else -> {
                val sc = scratch()
                if (!screenPoint(tg, t, targets, sc.xy)) return false
                val hp = HEAD_XY.get()!!
                headPos(angles, o, p, hp)
                val dx = (sc.xy[0] - hp[0]) * p.aspect
                val dy = -(sc.xy[1] - hp[1])
                val n = hypot(dx, dy)
                if (n >= 1e-9) { dir[0] = dx / n; dir[1] = dy / n }
            }
        }
        return true
    }

    private val DIR_LOCAL = ThreadLocal.withInitial { DoubleArray(2) }

    /**
     * Evaluates every verb whose window holds [t] into [o] (cleared first). [angles] are the bone angles the verbs sit on top of (the
     * acted angles, or the base at OFF); only the torso, head and arms are read, for the aimed verbs. Never throws on its own data.
     */
    fun evaluate(tl: ActVerbTimeline, t: Double, p: VerbPlacement, targets: ActingTargets, angles: FloatArray, o: VerbOffsets) {
        o.clear()
        if (tl.size == 0 || angles.size < StickFigureRig.BONE_COUNT) return
        val last = tl.lastStartAtOrBefore(t)
        if (last < 0) return
        val dir = DIR_LOCAL.get()!!
        // Pass 1: every verb except point_at (their offsets must be known before the arm is aimed from the torso).
        var i = last
        while (i >= 0 && t - tl.times[i] < VerbParams.MAX_WINDOW) {
            val kind = tl.kinds[i]
            val u = t - tl.times[i]
            if (kind != VerbParams.POINT_AT && u < tl.windowEnd(i)) {
                var ok = true
                dir[0] = 0.0; dir[1] = 0.0
                if (kind == VerbParams.LOOK_AT) ok = lookDir(tl.targets[i], t, p, angles, o, targets, dir)
                else if (kind == VerbParams.LOOK_AWAY) {
                    val tg = tl.targets[i]
                    val side = when {
                        tg != null && tg.kind == ActVocabulary.T_LEFT -> 1.0
                        tg != null && tg.kind == ActVocabulary.T_RIGHT -> -1.0
                        (FaceMotionModel.mix64(java.lang.Float.floatToRawIntBits(tl.times[i]).toLong()) and 1L) == 0L -> 1.0
                        else -> -1.0
                    }
                    dir[0] = side * 0.9; dir[1] = -0.45
                }
                if (ok) {
                    addVerb(kind, tl.intensity[i].toDouble(), u, dir[0], dir[1], tl.lookEnd[i].toDouble(), tl.lookFall[i].toDouble(), o)
                    o.active = true
                }
            }
            i--
        }
        // Pass 2: point_at.
        i = last
        while (i >= 0 && t - tl.times[i] < VerbParams.MAX_WINDOW) {
            if (tl.kinds[i] == VerbParams.POINT_AT) {
                val u = t - tl.times[i]
                if (u < VerbParams.DURATION[VerbParams.POINT_AT]) addPoint(tl.targets[i], tl.intensity[i].toDouble(), u, t, p, targets, angles, o)
            }
            i--
        }
    }

    private fun addPoint(tg: ActVocabulary.ActTarget?, a: Double, u: Double, t: Double, p: VerbPlacement, targets: ActingTargets,
                         angles: FloatArray, o: VerbOffsets) {
        if (tg == null) return
        val sc = scratch()
        val w = pointWeight(u) * a
        val ta = (angles[IX_TORSO] + o.b[IX_TORSO]).toDouble()
        val sxy = Math.toRadians(ta)
        val shx = TORSO_LEN * cos(sxy)
        val shy = TORSO_LEN * sin(sxy)
        val tx: Double
        val ty: Double
        if (tg.kind == ActVocabulary.T_POINT || tg.kind == ActVocabulary.T_LAYER) {
            if (!screenPoint(tg, t, targets, sc.xy)) return
            val s = p.scaleH()
            tx = ((sc.xy[0] - p.rootX) * p.aspect) / s
            ty = (sc.xy[1] - p.rootY) / s
        } else {
            // A direction word: point at a spot one arm length away in that direction.
            val reach = (L1 + L2)
            val ux = when (tg.kind) { ActVocabulary.T_LEFT -> -1.0; ActVocabulary.T_RIGHT -> 1.0; else -> 0.0 }
            val uy = when (tg.kind) { ActVocabulary.T_UP -> -1.0; ActVocabulary.T_DOWN -> 1.0; else -> 0.0 }
            tx = shx + ux * reach
            ty = shy + uy * reach
        }
        val right = tx >= shx
        val ub = if (right) IX_UR else IX_UL
        val fb = if (right) IX_FR else IX_FL
        ikTwoBone(shx, shy, tx, ty, ta, if (right) 1 else -1, L1, L2, sc.ik)
        val baseUp = (angles[ub] + o.b[ub]).toDouble()
        val baseFore = (angles[fb] + o.b[fb]).toDouble()
        o.b[ub] += wrap(sc.ik[0] - baseUp) * w
        o.b[fb] += wrap(sc.ik[1] - baseFore) * w
        // It also looks at what it points at, a fraction of a full look.
        val hp = HEAD_XY.get()!!
        var gx = 0.0
        var gy = 0.0
        if (tg.kind == ActVocabulary.T_POINT || tg.kind == ActVocabulary.T_LAYER) {
            headPos(angles, o, p, hp)
            val dx = (sc.xy[0] - hp[0]) * p.aspect
            val dy = -(sc.xy[1] - hp[1])
            val n = hypot(dx, dy)
            if (n >= 1e-9) { gx = dx / n; gy = dy / n }
        } else {
            gx = (if (tg.kind == ActVocabulary.T_LEFT) -1.0 else if (tg.kind == ActVocabulary.T_RIGHT) 1.0 else 0.0)
            gy = (if (tg.kind == ActVocabulary.T_UP) 1.0 else if (tg.kind == ActVocabulary.T_DOWN) -1.0 else 0.0)
        }
        val lw = VerbParams.POINT_LOOK_SHARE * (pointWeight(u).coerceAtLeast(0.0))
        o.gx += gx * VerbParams.LOOK_CAP * lw; o.gy += gy * VerbParams.LOOK_CAP * lw
        o.gw += lw
        o.b[IX_HEAD] += VerbParams.HEAD_LOOK_DEG * gx * lw
        o.active = true
    }
}
