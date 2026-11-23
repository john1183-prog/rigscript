package com.example.engine.acting

/**
 * The acting layer (WP2.1 plumbing). The engine computes the base state exactly as before, then calls [apply]; both
 * renderers draw whatever the engine publishes as its render state (I3: no logic in the renderers).
 *
 * Contract:
 *  - With a null context or intensity OFF, [apply] returns [base] itself: bit-identical, no work, no allocation.
 *  - Otherwise it returns either [base] (nothing to change) or [out] with every field filled. It never writes into
 *    [base] (the base angles are engine state, e.g. the hot-reload chase), and its result is a pure function of
 *    (base, ctx, timeSec) (I2).
 *  - WP2.1 registers no Craft stage yet, so every intensity currently returns [base]. WP2.2 adds per-joint timing,
 *    WP2.3 the face, WP2.4 the mouth, WP2.6 the mood idles.
 */
object ActingLayer {

    @Suppress("UNUSED_PARAMETER")
    fun apply(base: ActingFrame, ctx: ActingContext?, timeSec: Float, out: ActingFrame): ActingFrame {
        if (ctx == null || ctx.intensity == ActingIntensity.OFF) return base
        // No Craft stage exists yet (WP2.2 onwards).
        return base
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
