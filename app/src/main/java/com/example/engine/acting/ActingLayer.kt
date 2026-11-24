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

    fun apply(base: ActingFrame, ctx: ActingContext?, timeSec: Float, out: ActingFrame): ActingFrame {
        if (ctx == null || ctx.intensity == ActingIntensity.OFF) return base
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
        out.expression = base.expression
        out.mouthShape = base.mouthShape
        out.amplitude = base.amplitude
        out.motion = motion
        return out
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
