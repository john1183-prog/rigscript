package com.example.engine.acting

import com.example.engine.BakedKeyframe
import com.example.engine.EasingMath
import com.example.engine.StickFigureRig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The compiled timeline as the acting layer needs it (WP2.2): the keyframes plus a few per-keyframe numbers measured
 * once (when the timeline loads), so the per-frame evaluation does no searching. Pure data, immutable after init (I2).
 */
class ActingMotion(val keyframes: List<BakedKeyframe>, val easeAllWithSpring: Boolean) {
    val size: Int = keyframes.size
    /** The expression changes of the same timeline, for the face model (WP2.3). */
    val face: FaceTimeline = FaceTimeline.fromKeyframes(keyframes)
    /** Progress at which the eased shape first reaches [ActingParams.ARRIVAL_FRACTION] of the move (0 for rigid). */
    val arrivalProgress = FloatArray(size)
    /** How far the eased shape itself overshoots 1, as a fraction of the move (the implicit spring does, slightly). */
    val shapeOvershoot = FloatArray(size)
    /** True when the keyframe is a walk/jog pose change: lead/follow only (no anticipation, no overshoot, no moving hold). */
    val locomotion = BooleanArray(size)
    /** True when no ACTING overshoot is added: locomotion, or an ease that already overshoots by design. */
    val ownOvershoot = BooleanArray(size)
    /** WP2.6: the baked mood per level (index = [ActingIntensity] ordinal; null for OFF) and the locomotion gate. Built once, below. */
    private val moods: Array<BlendTrack?> = arrayOfNulls(ActingIntensity.values().size)
    /** WP2.6: 1 = free, 0 = a walk/jog keyframe is active (the mood is off there). */
    var free: BlendTrack = MoodModel.bakeFree(FloatArray(0), BooleanArray(0))
        private set

    /** The mood track of a level, or null when the level has none (OFF). */
    fun moodFor(intensity: ActingIntensity): BlendTrack? = moods[intensity.ordinal]

    init {
        val cache = HashMap<String, FloatArray>()
        for (i in 0 until size) {
            val k = keyframes[i]
            val loco = isLocomotion(k.fromPoseId) || isLocomotion(k.toPoseId)
            locomotion[i] = loco
            ownOvershoot[i] = loco || k.ease == "spring" || k.ease == "back" || k.ease == "elastic_out" || k.ease == "bounce"
            if (k.ease == "rigid") continue
            val key = k.ease + "|" + k.springStiffness + "|" + k.springDamping
            val m = cache.getOrPut(key) { measure(k, easeAllWithSpring) }
            arrivalProgress[i] = m[0]
            shapeOvershoot[i] = m[1]
        }
        // WP2.6, I12: the mood idles are cosmetic, so a failing bake (it should not) leaves no mood instead of breaking playback.
        try {
            for (level in ActingIntensity.values()) {
                val scale = ActingParams.levelFor(level).moodScale.toDouble()
                if (scale > 0.0) moods[level.ordinal] = MoodModel.bakeMood(face, scale)
            }
            free = MoodModel.bakeFree(FloatArray(size) { keyframes[it].timeSec }, locomotion)
        } catch (e: Exception) {
            moods.fill(null)
        }
    }

    companion object {
        fun isLocomotion(poseId: String): Boolean = poseId.startsWith("walk_") || poseId.startsWith("jog_")

        /** The legacy eased progress of a keyframe at progress [p] (the same rule as `PlaybackEngine.resolveBaseAngles`). */
        fun shape(k: BakedKeyframe, p: Float, easeAll: Boolean): Float = when {
            k.ease == "rigid" -> 1f
            k.ease == "spring" || easeAll -> EasingMath.springAnalytical(p, k.springStiffness, k.springDamping)
            else -> EasingMath.ease(k.ease, p)
        }

        private fun measure(k: BakedKeyframe, easeAll: Boolean): FloatArray {
            val g = ActingParams.SHAPE_GRID
            var arrival = 1f
            var found = false
            var peak = 0f
            for (i in 0..g) {
                val p = i.toFloat() / g
                val v = shape(k, p, easeAll)
                if (!found && v >= ActingParams.ARRIVAL_FRACTION) { arrival = p; found = true }
                if (v > peak) peak = v
            }
            return floatArrayOf(arrival, max(0f, peak - 1f))
        }
    }
}

/**
 * WP2.2 motion model, a pure function of (motion, bone, time, level, seed): per-joint lead/follow and duration stretch by
 * chain level, an anticipation wind-back, a damped settle kick, retarget on overlapping events (position AND velocity carry
 * on) and a tiny moving hold. It keeps the legacy eased shape, so a cue lands when it always did; only overlap, wind-up
 * and settle are added. Allocation-free: scalars only. Craft in the sense of A4 (how a pose change is executed).
 *
 * Mirrors `tools/acting_proto/acting_proto.py` line for line; `ActingMotionTest` pins the two together.
 */
object ActingMotionModel {

    /** Chain level per bone index: torso 0; head, upper arms and upper legs 1; forearms and lower legs 2. */
    val LEVEL_BY_BONE: IntArray = IntArray(StickFigureRig.BONES.size) { levelOf(StickFigureRig.BONES[it].id) }

    fun levelOf(boneId: String): Int = when {
        boneId == "torso" -> 0
        boneId.startsWith("lower_") -> 2
        else -> 1
    }

    private val OMEGA = ActingParams.OVERSHOOT_OMEGA.toDouble()
    private val ZETA = ActingParams.OVERSHOOT_ZETA.toDouble()
    private val WD = OMEGA * sqrt(1.0 - ZETA * ZETA)
    private val SETTLE = ActingParams.SETTLE_SEC
    private val OS_LEAD = ActingParams.OVERSHOOT_LEAD_SEC
    private val OS_RAMP = ActingParams.OVERSHOOT_RAMP_SEC

    private fun smooth(x: Double): Double {
        val c = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x
        return c * c * (3.0 - 2.0 * c)
    }

    private fun fade(x: Double): Double = 1.0 - smooth((x - 0.5 * SETTLE) / (0.5 * SETTLE))

    /** The kick: ramped in, exponentially damped, faded to exactly zero at [SETTLE]. */
    private fun gRaw(tau: Double): Double =
        smooth(tau / OS_RAMP) * exp(-ZETA * OMEGA * tau) * sin(WD * tau) * fade(tau)

    /** Its peak, so the cap is exact (the kick is divided by it). Computed once, deterministically. */
    private val GPEAK: Double = run {
        var peak = 0.0
        val steps = (SETTLE * 1e4).toInt()
        for (i in 0..steps) peak = max(peak, gRaw(i * 1e-4))
        peak
    }

    private fun segStart(k: BakedKeyframe, level: Int, lv: ActingLevel): Double =
        if (k.ease == "rigid") k.timeSec.toDouble() else k.timeSec + level * lv.leadFollowDelaySec.toDouble()

    private fun segDur(k: BakedKeyframe, level: Int, lv: ActingLevel): Double =
        if (k.ease == "rigid") 0.0 else k.duration * (1.0 + level * lv.durationStretchPerLevel.toDouble())

    private fun segSettleTime(m: ActingMotion, idx: Int, level: Int, lv: ActingLevel): Double {
        val k = m.keyframes[idx]
        if (k.ease == "rigid") return k.timeSec.toDouble()
        val s = segStart(k, level, lv)
        val dS = segDur(k, level, lv)
        return s + max(dS, max(dS * m.arrivalProgress[idx] - OS_LEAD + SETTLE, SETTLE))
    }

    private fun dipEligible(m: ActingMotion, idx: Int, move: Double): Boolean {
        val k = m.keyframes[idx]
        return k.ease != "rigid" && !m.locomotion[idx] &&
            abs(move) >= ActingParams.ANTICIPATION_MIN_MOVE_DEG && k.duration >= ActingParams.ANTICIPATION_MIN_DURATION_SEC
    }

    /** One keyframe's own bone value at [t], given the value [fe] the bone had when the keyframe took over and its velocity [v0]. */
    private fun segValue(m: ActingMotion, idx: Int, b: Int, level: Int, lv: ActingLevel, fe: Double, v0: Double, t: Double): Double {
        val k = m.keyframes[idx]
        val to = k.toAngles[b].toDouble()
        if (k.ease == "rigid") return if (t >= k.timeSec) to else fe
        val s = segStart(k, level, lv)
        val e = t - s
        if (e <= 0.0) return fe
        val dS = segDur(k, level, lv)
        val move = to - fe
        val sg = if (move >= 0.0) 1.0 else -1.0
        val p = min(e / dS, 1.0)
        var v = fe + move * ActingMotion.shape(k, p.toFloat(), m.easeAllWithSpring)
        if (!m.ownOvershoot[idx]) {
            val tau = t - (s + dS * m.arrivalProgress[idx] - OS_LEAD)
            if (tau > 0.0) {
                val cap = min(lv.overshootFraction * abs(move), lv.overshootCapDeg.toDouble())
                val o = max(0.0, cap - m.shapeOvershoot[idx] * abs(move))   // the shape's own overshoot counts against the cap
                v += sg * o * gRaw(tau) / GPEAK
            }
        }
        if (v0 != 0.0) v += v0 * e * exp(-e / ActingParams.CARRY_TAU_SEC) * fade(e)
        return v
    }

    /** The legacy pose value of a bone at [t]: exactly what `PlaybackEngine.resolveBaseAngles` writes into `baseAngles`. */
    fun legacyAngle(m: ActingMotion, b: Int, t: Double): Double {
        if (m.size == 0) return Double.NaN
        val idx = activeIndex(m, t)
        val k = m.keyframes[if (idx >= 0) idx else 0]
        val elapsed = max((t - k.timeSec).toFloat(), 0f)
        val p = (elapsed / k.duration).coerceIn(0f, 1f)
        val eased = ActingMotion.shape(k, p, m.easeAllWithSpring)
        return (k.fromAngles[b] + (k.toAngles[b] - k.fromAngles[b]) * eased).toDouble()
    }

    /** Rightmost keyframe whose start is <= [t], or -1 (the same binary search the engine uses). */
    private fun activeIndex(m: ActingMotion, t: Double): Int {
        var lo = 0
        var hi = m.size - 1
        var active = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (m.keyframes[mid].timeSec <= t) { active = mid; lo = mid + 1 } else hi = mid - 1
        }
        return active
    }

    /** The acted bone value at [t] (degrees, total angle), before the amplitude motion is added. NaN-free for finite inputs. */
    fun actedAngle(m: ActingMotion, b: Int, t: Double, lv: ActingLevel, seed: Long): Double {
        val level = LEVEL_BY_BONE[b]
        val n = m.size
        val a = activeIndex(m, t)
        var j = max(a, 0)
        while (j > 0 && segStart(m.keyframes[j], level, lv) > t) j--
        if (segStart(m.keyframes[j], level, lv) > t) {          // nothing has started on this bone yet
            val k0 = m.keyframes[0]
            return (if (k0.ease == "rigid") k0.toAngles[b] else k0.fromAngles[b]).toDouble()
        }
        var c = j
        var depth = 0
        while (c > 0 && depth < ActingParams.CHAIN_DEPTH &&
            segSettleTime(m, c - 1, level, lv) > segStart(m.keyframes[c], level, lv)) { c--; depth++ }
        var fe = m.keyframes[c].fromAngles[b].toDouble()
        var v0 = 0.0
        for (i in c until j) {
            val sN = segStart(m.keyframes[i + 1], level, lv)
            val nfe = segValue(m, i, b, level, lv, fe, v0, sN)
            val vel = (nfe - segValue(m, i, b, level, lv, fe, v0, sN - ActingParams.VELOCITY_STEP_SEC)) / ActingParams.VELOCITY_STEP_SEC
            v0 = if (m.keyframes[i + 1].ease == "rigid") 0.0
                 else max(-ActingParams.CARRY_MAX_DEG_PER_SEC, min(ActingParams.CARRY_MAX_DEG_PER_SEC, vel))
            fe = nfe
        }
        var v = segValue(m, j, b, level, lv, fe, v0, t)
        if (j + 1 < n) {                                        // anticipation: wind back just before the next move starts here
            val nx = m.keyframes[j + 1]
            val sN = segStart(nx, level, lv)
            val lead = lv.anticipationLeadSec.toDouble()
            if (lead > 0.0 && t > sN - lead) {
                val move = nx.toAngles[b] - segValue(m, j, b, level, lv, fe, v0, sN)
                if (dipEligible(m, j + 1, move)) {
                    val u = (t - (sN - lead)) / lead
                    val s1 = sin(Math.PI * u)
                    val a2 = min(lv.anticipationFraction * abs(move), lv.anticipationCapDeg.toDouble())
                    v -= (if (move >= 0.0) 1.0 else -1.0) * a2 * s1 * s1
                }
            }
        }
        val kj = m.keyframes[j]
        if (lv.holdDriftDeg > 0f && !m.locomotion[j]) {
            val w = if (kj.ease == "rigid") smooth((t - kj.timeSec) / ActingParams.HOLD_RAMP_SEC)
                    else smooth((t - segSettleTime(m, j, level, lv) + SETTLE) / ActingParams.HOLD_RAMP_SEC)
            if (w > 0.0) v += w * drift(seed, b, t, lv.holdDriftDeg.toDouble())
        }
        return v
    }

    // ── Moving hold: two seeded sinusoids per bone (splitmix64; the Python prototype has the same numbers) ─────────────

    private fun splitmix(x0: Long): Long {
        var z = x0 + -7046029254386353131L                  // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -4658895280553007687L     // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L     // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Stable pseudo-random number in [0,1) from (seed, bone, slot). */
    fun unit(seed: Long, bone: Int, slot: Int): Double {
        val h = splitmix(seed xor (bone.toLong() * -7046029254386353131L) xor (slot.toLong() * -3335678366873096957L))
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    fun drift(seed: Long, bone: Int, t: Double, amp: Double): Double {
        val f1 = 0.21 + 0.19 * unit(seed, bone, 0)
        val f2 = 0.37 + 0.31 * unit(seed, bone, 1)
        val p1 = 2.0 * Math.PI * unit(seed, bone, 2)
        val p2 = 2.0 * Math.PI * unit(seed, bone, 3)
        return amp * (0.65 * sin(2.0 * Math.PI * f1 * t + p1) + 0.35 * sin(2.0 * Math.PI * f2 * t + p2))
    }
}
