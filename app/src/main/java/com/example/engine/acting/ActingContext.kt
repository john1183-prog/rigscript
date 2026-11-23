package com.example.engine.acting

/**
 * Resolves a target named by a script (`layer:<id>`, `camera`, ...) to normalized screen coordinates at a time.
 * WP2.5 (`point_at`, `look_at`) is the first user; WP2.1 only defines the seam so the context is complete.
 */
interface ActingTargets {
    /** Writes (x, y) into [out] (size 2, normalized screen coordinates) and returns true, or returns false when unresolvable. */
    fun resolve(targetId: String, timeSec: Float, out: FloatArray): Boolean

    companion object {
        /** No targets: every lookup is unresolvable. */
        val NONE: ActingTargets = object : ActingTargets {
            override fun resolve(targetId: String, timeSec: Float, out: FloatArray): Boolean = false
        }
    }
}

/**
 * Everything the acting layer may read besides the base state: the intensity, a stable seed, the amplitude envelope
 * and resolved targets. Immutable, so it can be handed to the render thread by a single volatile write (I2: the
 * layer's output is a pure function of base state, this context and time).
 */
class ActingContext(
    val intensity: ActingIntensity,
    /** Stable per project (see [seedForProject]); pseudo-randomness is seeded from it, never from the clock (I2). */
    val seed: Long,
    /** Amplitude envelope (0..1) sampled at [envelopeFps]; empty when there is none. */
    val envelope: FloatArray = FloatArray(0),
    val envelopeFps: Int = 30,
    val targets: ActingTargets = ActingTargets.NONE
) {
    /** Envelope sample at [timeSec]: the index is clamped, and 0 when there is no envelope. Allocation-free. */
    fun envelopeAt(timeSec: Float): Float {
        if (envelope.isEmpty() || envelopeFps <= 0) return 0f
        val idx = (timeSec * envelopeFps).toInt().coerceIn(0, envelope.size - 1)
        return envelope[idx]
    }

    /** The same context with another intensity (the editor's A/B toggle uses this). */
    fun withIntensity(other: ActingIntensity): ActingContext = ActingContext(other, seed, envelope, envelopeFps, targets)

    companion object {
        /** FNV-1a (64 bit) over the characters of the project id: stable across runs, devices and builds. */
        fun seedForProject(projectId: String): Long {
            var h = -3750763034362895579L // 0xcbf29ce484222325
            for (ch in projectId) {
                h = h xor ch.code.toLong()
                h *= 1099511628211L
            }
            return h
        }

        /** Builds a context from the stored intensity string (unknown values degrade, see [ActingIntensity.parse]). */
        fun create(
            intensityName: String?,
            projectId: String,
            envelope: FloatArray = FloatArray(0),
            envelopeFps: Int = 30,
            targets: ActingTargets = ActingTargets.NONE
        ): ActingContext =
            ActingContext(ActingIntensity.parse(intensityName), seedForProject(projectId), envelope, envelopeFps, targets)
    }
}

/**
 * The slice of engine state the acting layer reads and may replace. [angles] are the total bone angles in degrees
 * (the engine's `currentAngles` for the base frame; a separate array for the acted output). The other fields are
 * inputs that later work packages use (expression blending, mouth smoothing); WP2.1 only carries them.
 */
class ActingFrame(val angles: FloatArray) {
    var expression: Int = 0
    var mouthShape: Int = 0
    var amplitude: Float = 0f
}
