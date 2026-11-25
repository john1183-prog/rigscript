package com.example.engine.acting

import com.example.engine.MouthShape
import kotlin.math.exp
import kotlin.math.floor

/**
 * WP2.4 speech mouth: continuous (open, width, roundness) from the amplitude envelope. Prototype and plots:
 * `tools/acting_proto/mouth_proto.py` (the constants are the same, see [ActingParams] `MOUTH_*`).
 *
 * The model is BAKED once from (envelope, mouth classes, silence threshold) into a track sampled at
 * [ActingParams.MOUTH_ENV_FPS] x [ActingParams.MOUTH_SUBSTEPS] Hz and evaluated by linear interpolation, so the mouth at
 * time t is a pure function of the envelope and t: seek equals sequential (I2) and it does not depend on the export
 * frame rate. The legacy mouth (acting OFF, or no envelope) is untouched. Pure Kotlin, no Android types.
 *
 * Steps per envelope frame k (amplitude a, raw class c):
 *  1. class: CLOSED counts as MID (quiet but voiced speech opens a little), and a class change must last
 *     [ActingParams.MOUTH_CLASS_DWELL] frames (a one-frame blip is ignored);
 *  2. gate: opens above th*(1+h), closes only after [ActingParams.MOUTH_SILENCE_HOLD_SEC] below th*(1-h) (hysteresis h);
 *  3. targets: gate closed = (0, closed width, 0); open = the legacy class half-height at this amplitude, the legacy class
 *     half-width, and a roundness for loud WIDE vowels;
 *  4. open follows its target with an attack and a release time constant, width and roundness with one each
 *     (co-articulation), stepped exactly (exponential) at the track rate; a closed mouth snaps to exactly 0.
 */
class MouthTrack private constructor(private val data: FloatArray, val sampleCount: Int) {

    val isEmpty: Boolean get() = sampleCount == 0

    /** Writes (open, width, round), each 0..1, at [timeSec] into [out] (size 3). An empty track writes the closed mouth. Allocation-free. */
    fun evaluate(timeSec: Double, out: DoubleArray) {
        if (sampleCount == 0) {
            out[0] = 0.0; out[1] = CLOSED_WIDTH_PARAM; out[2] = 0.0
            return
        }
        val rate = (ActingParams.MOUTH_ENV_FPS * ActingParams.MOUTH_SUBSTEPS).toDouble()
        val u = (timeSec * rate).coerceIn(0.0, (sampleCount - 1).toDouble())
        val j = floor(u).toInt()
        if (j >= sampleCount - 1) {
            val b = (sampleCount - 1) * 3
            out[0] = data[b].toDouble(); out[1] = data[b + 1].toDouble(); out[2] = data[b + 2].toDouble()
            return
        }
        val f = u - j
        val b = j * 3
        for (c in 0 until 3) out[c] = data[b + c] * (1.0 - f) + data[b + 3 + c] * f
    }

    companion object {
        val EMPTY = MouthTrack(FloatArray(0), 0)

        /** The width parameter of the legacy closed mouth: (0.44 - W_MIN) / (W_MAX - W_MIN). */
        val CLOSED_WIDTH_PARAM: Double =
            (ActingParams.MOUTH_CLOSED_HALF_WIDTH - ActingParams.MOUTH_W_MIN) / (ActingParams.MOUTH_W_MAX - ActingParams.MOUTH_W_MIN)

        private fun classWidth(cls: Int): Double = when (cls) {
            MouthShape.WIDE -> 0.44
            MouthShape.NARROW -> 0.32
            else -> 0.42
        }

        private fun classHeight(cls: Int): Double = when (cls) {
            MouthShape.WIDE -> 0.28
            MouthShape.NARROW -> 0.10
            else -> 0.14
        }

        /**
         * Bakes the track. An empty [envelope] gives [EMPTY]. [shapes] may be empty (every frame is then MID) or shorter than
         * the envelope (the last class repeats, like the exporters' index clamp). [silenceThreshold] is
         * `AmplitudeSettings.silenceThreshold`.
         */
        fun bake(envelope: FloatArray, shapes: IntArray, silenceThreshold: Float): MouthTrack {
            val n = envelope.size
            if (n == 0) return EMPTY
            val sub = ActingParams.MOUTH_SUBSTEPS
            val dt = 1.0 / (ActingParams.MOUTH_ENV_FPS * sub)
            val aAtt = 1.0 - exp(-dt / ActingParams.MOUTH_TAU_ATTACK)
            val aRel = 1.0 - exp(-dt / ActingParams.MOUTH_TAU_RELEASE)
            val aW = 1.0 - exp(-dt / ActingParams.MOUTH_TAU_WIDTH)
            val aR = 1.0 - exp(-dt / ActingParams.MOUTH_TAU_ROUND)
            val count = n * sub + 1
            val data = FloatArray(count * 3)
            var o = 0.0
            var w = CLOSED_WIDTH_PARAM
            var r = 0.0
            data[0] = o.toFloat(); data[1] = w.toFloat(); data[2] = r.toFloat()
            var gate = false
            var silent = 0.0
            var stable = MouthShape.MID
            var cand = MouthShape.MID
            var cnt = 0
            val th = silenceThreshold.toDouble()
            val on = th * (1.0 + ActingParams.MOUTH_HYSTERESIS)
            val off = th * (1.0 - ActingParams.MOUTH_HYSTERESIS)
            val frameSec = 1.0 / ActingParams.MOUTH_ENV_FPS
            for (k in 0 until n) {
                val a = envelope[k].toDouble().let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
                var c = if (shapes.isEmpty()) MouthShape.MID else shapes[minOf(k, shapes.size - 1)]
                if (c != MouthShape.MID && c != MouthShape.WIDE && c != MouthShape.NARROW) c = MouthShape.MID
                if (c == stable) {
                    cnt = 0
                } else if (c == cand) {
                    cnt += 1
                    if (cnt >= ActingParams.MOUTH_CLASS_DWELL) { stable = cand; cnt = 0 }
                } else {
                    cand = c; cnt = 1
                    if (ActingParams.MOUTH_CLASS_DWELL <= 1) { stable = cand; cnt = 0 }
                }
                if (!gate) {
                    if (a > on) { gate = true; silent = 0.0 }
                } else if (a < off) {
                    silent += frameSec
                    if (silent >= ActingParams.MOUTH_SILENCE_HOLD_SEC - 1e-9) gate = false
                } else {
                    silent = 0.0
                }
                val to: Double
                val tw: Double
                val tr: Double
                if (!gate) {
                    to = 0.0; tw = CLOSED_WIDTH_PARAM; tr = 0.0
                } else {
                    to = ((classHeight(stable) * (0.5 + 0.5 * a) - ActingParams.MOUTH_H0) / (ActingParams.MOUTH_HMAX - ActingParams.MOUTH_H0)).coerceIn(0.0, 1.0)
                    tw = (classWidth(stable) - ActingParams.MOUTH_W_MIN) / (ActingParams.MOUTH_W_MAX - ActingParams.MOUTH_W_MIN)
                    tr = if (stable == MouthShape.WIDE) ActingParams.MOUTH_ROUND_MAX * ((a - 0.5) / 0.4).coerceIn(0.0, 1.0) else 0.0
                }
                for (s in 0 until sub) {
                    o += (to - o) * (if (to > o) aAtt else aRel)
                    w += (tw - w) * aW
                    r += (tr - r) * aR
                    if (!gate && to == 0.0 && o < ActingParams.MOUTH_SNAP_CLOSED) o = 0.0
                    val b = (k * sub + s + 1) * 3
                    data[b] = o.toFloat(); data[b + 1] = w.toFloat(); data[b + 2] = r.toFloat()
                }
            }
            return MouthTrack(data, count)
        }
    }
}
