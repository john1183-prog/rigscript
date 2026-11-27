package com.example.engine.acting

import com.example.engine.StickFigureRig

/**
 * The acting layer (WP2.1 plumbing). The engine computes the base state exactly as before, then calls [apply]; both
 * renderers draw whatever the engine publishes as its render state (I3: no logic in the renderers).
 *
 * Contract:
 *  - With a null context or intensity OFF, [apply] returns [base] itself: bit-identical, no work, no allocation.
 *  - Otherwise it returns either [base] (nothing to change) or [out] with every field filled. It never writes into
 *    [base] (the base angles are engine state, e.g. the hot-reload chase), and its result is a pure function of
 *    (base, ctx, timeSec) (I2).
 *  - WP2.2 registers the first Craft stage (per-joint timing, see [ActingMotionModel]); WP2.3 adds the face, WP2.4 the
 *    mouth, WP2.6 the mood idles. With no timeline loaded the layer still returns [base].
 */
object ActingLayer {

    /** Per-thread scratch for the mouth evaluation (I7: nothing is allocated per frame); read through a non-null helper. */
    private val mouthScratchLocal: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(3) }
    private fun mouthScratch(): DoubleArray = mouthScratchLocal.get()!!

    private val HEAD_IDX: Int = StickFigureRig.BONES.indexOfFirst { it.id == "head" }
    private val TORSO_IDX: Int = StickFigureRig.BONES.indexOfFirst { it.id == "torso" }
    private val ARM_R_IDX: Int = StickFigureRig.BONES.indexOfFirst { it.id == "upper_arm_r" }
    private val ARM_L_IDX: Int = StickFigureRig.BONES.indexOfFirst { it.id == "upper_arm_l" }

    fun apply(base: ActingFrame, ctx: ActingContext?, timeSec: Float, out: ActingFrame): ActingFrame {
        // WP2.5 verbs are Direction: they run at every intensity, OFF included. With no verb window holding the time the OFF path is
        // exactly as before: the base frame itself, no work, no allocation.
        val vt = base.verbs
        val verbsMaybe = vt != null && vt.mayBeActive(timeSec)
        if (ctx == null || ctx.intensity == ActingIntensity.OFF) {
            if (!verbsMaybe || out.angles.size != base.angles.size) return base
            return applyVerbsOnly(base, timeSec, out)
        }
        val motion = base.motion ?: return base
        val n = base.angles.size
        if (motion.size == 0 || n != ActingMotionModel.LEVEL_BY_BONE.size || out.angles.size != n) return base

        // Stage 1 (WP2.2, motion model): the acted pose minus the legacy pose is a per-bone delta, added to the frame the engine
        // computed, so the amplitude motion (breath, talk sway, fidget) stays in the result exactly as it was.
        val lv = ActingParams.levelFor(ctx.intensity)
        val t = timeSec.toDouble()
        val bones = StickFigureRig.BONES
        for (i in 0 until n) {
            val delta = ActingMotionModel.actedAngle(motion, i, t, lv, ctx.seed) - ActingMotionModel.legacyAngle(motion, i, t)
            val b = base.angles[i]
            var v = (b + delta).toFloat()
            if (!v.isFinite()) v = b
            // Same joint limits as the engine's amplitude layer, widened to include the base value so the layer never pulls a
            // base value that was already outside them (the engine only clamps when the amplitude motion is on).
            val bone = bones[i]
            val lo = bone.minAngleDegrees
            val hi = bone.maxAngleDegrees
            if (lo != null && hi != null) {
                v = v.coerceIn(minOf(bone.defaultAngleDegrees + lo, b), maxOf(bone.defaultAngleDegrees + hi, b))
            }
            out.angles[i] = v
        }
        // Stage 1b (WP2.6, mood idles): breathing rate and depth and the sway follow the expression; off while walking. Scales the
        // legacy amplitude motion the engine recorded in the base frame, so a frame without any is left alone.
        val mood = motion.moodFor(ctx.intensity)
        if (mood != null) MoodModel.apply(base, mood, motion.free, t, TORSO_IDX, HEAD_IDX, ARM_R_IDX, ARM_L_IDX, out)
        // Stage 2 (WP2.3, face): the blended expression, brows and gaze go to out.face; the head follows the eyes by a few
        // degrees, added to the head bone (the mouth and eyes turn with it because the renderers rotate them with the head).
        val tilt = FaceMotionModel.resolve(motion.face, lv, ctx.seed, t, base.eyeOpenness, base.blinkDurationSec, out.face)
        if (HEAD_IDX >= 0 && tilt != 0.0) {
            val bone = bones[HEAD_IDX]
            val b = base.angles[HEAD_IDX]
            var v = (out.angles[HEAD_IDX] + tilt).toFloat()
            if (!v.isFinite()) v = out.angles[HEAD_IDX]
            val lo = bone.minAngleDegrees
            val hi = bone.maxAngleDegrees
            if (lo != null && hi != null) v = v.coerceIn(minOf(bone.defaultAngleDegrees + lo, b), maxOf(bone.defaultAngleDegrees + hi, b))
            out.angles[HEAD_IDX] = v
        }
        // Stage 3 (WP2.4, speech): the baked continuous mouth. No track (no envelope, or amplitude motion off) keeps the legacy
        // shape table, which is what the renderers draw when mouthActed is false.
        val track = base.mouthTrack
        val face = out.face
        if (track != null && !track.isEmpty) {
            val m = mouthScratch()
            track.evaluate(t, m)
            val open = (m[0] * lv.mouthOpenScale).coerceIn(0.0, 1.0)
            if (open.isFinite() && m[1].isFinite() && m[2].isFinite()) {
                face.mouthActed = true
                face.mouthOpen = open.toFloat()
                face.mouthWidth = m[1].coerceIn(0.0, 1.0).toFloat()
                face.mouthRound = m[2].coerceIn(0.0, 1.0).toFloat()
            } else face.mouthActed = false
        } else face.mouthActed = false
        // Stage 4 (WP2.5, intent verbs): added on top of everything above (Direction).
        if (verbsMaybe) applyVerbs(base, timeSec, out)
        out.expression = base.expression
        out.mouthShape = base.mouthShape
        out.amplitude = base.amplitude
        out.eyeOpenness = base.eyeOpenness
        out.blinkDurationSec = base.blinkDurationSec
        out.motion = motion
        out.mouthTrack = track
        return out
    }

    /** OFF with a verb active: the legacy face and the base pose, plus the verb offsets. Returns [base] when no verb holds the time after all. */
    private fun applyVerbsOnly(base: ActingFrame, timeSec: Float, out: ActingFrame): ActingFrame {
        base.angles.copyInto(out.angles)
        out.face.setLegacy(base.expression, base.eyeOpenness)
        if (!applyVerbs(base, timeSec, out)) return base
        out.expression = base.expression
        out.mouthShape = base.mouthShape
        out.amplitude = base.amplitude
        out.eyeOpenness = base.eyeOpenness
        out.blinkDurationSec = base.blinkDurationSec
        out.motion = base.motion
        out.mouthTrack = base.mouthTrack
        return out
    }

    /** Evaluates the verbs over [out]'s angles and face and adds them. False (and nothing changed) when no verb holds the time. */
    private fun applyVerbs(base: ActingFrame, timeSec: Float, out: ActingFrame): Boolean {
        val vt = base.verbs ?: return false
        val o = out.verbOffsets
        ActVerbModel.evaluate(vt, timeSec.toDouble(), base.verbPlacement, base.verbTargets, out.angles, o)
        if (!o.active) return false
        val bones = StickFigureRig.BONES
        for (i in 0 until out.angles.size) {
            val d = o.b[i]
            if (d == 0.0) continue
            val b = base.angles[i]
            var v = (out.angles[i] + d).toFloat()
            if (!v.isFinite()) v = out.angles[i]
            val bone = bones[i]
            val lo = bone.minAngleDegrees
            val hi = bone.maxAngleDegrees
            if (lo != null && hi != null) v = v.coerceIn(minOf(bone.defaultAngleDegrees + lo, b), maxOf(bone.defaultAngleDegrees + hi, b))
            out.angles[i] = v
        }
        val f = out.face
        if (o.eyeH.isFinite()) f.eyeHeightMul = maxOf(0.2f, f.eyeHeightMul + o.eyeH.toFloat())
        if (o.eyeR.isFinite()) f.eyeRadiusFrac += o.eyeR.toFloat()
        val gw = o.gw.coerceIn(0.0, 1.0)
        if (o.gx.isFinite() && o.gy.isFinite() && gw.isFinite()) {
            f.gazeX = (f.gazeX * (1.0 - gw) + o.gx).toFloat()
            f.gazeY = (f.gazeY * (1.0 - gw) + o.gy).toFloat()
        }
        // Brows only move where they are already drawn (the acting levels draw them on every expression; legacy only WORRIED and ANGRY),
        // so a verb never makes a brow appear from nothing.
        if (f.browsVisible) {
            if (o.browH.isFinite()) f.browHeight += o.browH.toFloat()
            if (o.browL.isFinite()) f.browInnerLift += o.browL.toFloat()
        }
        if (o.mouthW.isFinite()) f.mouthWidthMul += o.mouthW.toFloat()
        if (o.mouthB.isFinite()) f.mouthCyBiasFrac += o.mouthB.toFloat()
        if (o.laugh > 0.05 && o.laugh.isFinite()) {
            val open = o.laugh.coerceIn(0.0, 1.0).toFloat()
            val wide = (0.7 * o.laugh).coerceIn(0.0, 1.0).toFloat()
            if (f.mouthActed) {
                f.mouthOpen = maxOf(f.mouthOpen, open)
                f.mouthWidth = maxOf(f.mouthWidth, wide)
            } else {
                f.mouthActed = true
                f.mouthOpen = open; f.mouthWidth = wide; f.mouthRound = 0f
            }
        }
        return true
    }
}

/**
 * I11 fail-soft: an exception inside the acting layer returns the base frame, is counted, and its first message is
 * kept (the engine has no logger it may use on the JVM). Playback and export never crash because of acting.
 */
class ActingFault {
    @Volatile var message: String? = null
        private set
    @Volatile var count: Int = 0
        private set

    /** Runs [block]; on any throwable returns [base] and records the fault. Inline, so the hot path allocates nothing. */
    inline fun guard(base: ActingFrame, block: () -> ActingFrame): ActingFrame =
        try {
            block()
        } catch (e: Throwable) {
            record(e)
            base
        }

    fun record(e: Throwable) {
        count += 1
        if (message == null) message = e::class.java.simpleName + ": " + (e.message ?: "")
    }
}
