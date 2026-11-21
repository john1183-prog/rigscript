package com.example.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A grayscale image: row-major, one Float per pixel on a 0 to 255 scale (WP1.6a). The diagnostics decode exported
 * frames into this type so every comparison is plain array math that runs on the JVM.
 */
class GrayImage(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(data.size == width * height) { "data has ${data.size} values, expected ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    companion object {
        /** Rec. 601 luma of ARGB_8888 pixels (what `Bitmap.getPixels` returns). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): GrayImage {
            require(pixels.size == width * height) { "pixels has ${pixels.size} values, expected ${width * height}" }
            val out = FloatArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            }
            return GrayImage(width, height, out)
        }
    }
}

/** A pixel rectangle: [left, right) by [top, bottom). Out-of-range boxes are clamped by the functions that take them. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun intersect(other: PixelBox): PixelBox =
        PixelBox(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))

    companion object {
        fun whole(image: GrayImage): PixelBox = PixelBox(0, 0, image.width, image.height)
    }
}

/**
 * Where the test image sits relative to the reference, in pixels: positive [dx] means the test content is further
 * right, positive [dy] further down. [reliable] is false when the region had no edges to measure (dx and dy are 0).
 */
class OffsetEstimate(val dx: Double, val dy: Double, val correlation: Double, val reliable: Boolean) {
    val magnitude: Double get() = sqrt(dx * dx + dy * dy)
}

/** One tone measured inside the windows around the SFX cues and everywhere else (amplitudes, fraction of full scale). */
class WindowedTone(val insideMean: Double, val insideMin: Double, val outside: Double) {
    /** The weakest cue window against the background level; finite even for digital silence. */
    fun ratio(): Double = insideMin / max(outside, DiagnosticsDsp.RATIO_FLOOR)
}

/** What one sampled frame says about GLES against Canvas. NaN means "could not be measured" and is never a pass. */
data class FrameMetrics(
    val offsetPx: Double,
    val meanAbsDiff: Double,
    val laplacianRatio: Double,
    val edgeAaRatio: Double,
    /** Reported, not gating (R4, the faint landscape mouth): GLES ink / Canvas ink in the mouth box. */
    val mouthInkRatio: Double = Double.NaN
)

/**
 * Pure DSP and image-comparison math for the diagnostics (WP1.6a). No Android types: the Android glue decodes the
 * exported audio to 16-bit PCM and the sampled video frames to [GrayImage]s and calls these functions; [GlesAcceptance]
 * turns the numbers into a verdict. Everything here is deterministic and fail-soft: bad input gives 0 or NaN, never an
 * exception.
 */
object DiagnosticsDsp {

    const val FULL_SCALE = 32768.0

    /** Keeps `inside / outside` finite for digital silence (-80 dBFS). */
    const val RATIO_FLOOR = 1e-4

    /** An edge neighbourhood needs at least this gray range (0..255) before its pixels are looked at for anti-aliasing. */
    const val AA_MIN_CONTRAST = 32f

    /** A pixel counts as intermediate when it is this fraction of the local range away from both extremes. */
    const val AA_GRAY_MARGIN = 0.1f

    /** With fewer intermediate-gray edge pixels than this in the Canvas frame the anti-aliasing ratio is not trusted. */
    const val AA_MIN_CANVAS_PIXELS = 20

    /** A Canvas text region with a Laplacian variance below this has no detail to compare against. */
    const val MIN_LAPLACIAN_VARIANCE = 1.0

    /** The offset estimator needs this normalized correlation at its best shift before it trusts the result. */
    const val MIN_OFFSET_CORRELATION = 0.3

    const val DEFAULT_MAX_SHIFT = 3

    /** Less total ink than one fully dark pixel in the Canvas mouth box and the ink ratio is not trusted. */
    const val MIN_INK = 255.0

    // ------------------------------------------------------------------------------------------------------ audio

    /**
     * Estimated amplitude, as a fraction of 16-bit full scale, of a sinusoid at [freqHz] in `samples[from, to)`
     * (Goertzel). A pure sine of amplitude 0.5 reads about 0.5; silence and other frequencies read near 0. The range
     * is clamped to the array. With [hann] (the default) a Hann window is applied first, so a burst that starts
     * inside the range does not splatter into other frequencies.
     * Returns 0 for an empty range, a non-positive or non-finite frequency, or one at or above Nyquist.
     */
    fun goertzelAmplitude(
        samples: ShortArray,
        from: Int,
        to: Int,
        sampleRate: Int,
        freqHz: Double,
        hann: Boolean = true
    ): Double {
        val start = max(0, from)
        val end = min(samples.size, to)
        val n = end - start
        if (n <= 0 || sampleRate <= 0 || !freqHz.isFinite() || freqHz <= 0.0 || freqHz >= sampleRate / 2.0) return 0.0
        val coeff = 2.0 * cos(2.0 * PI * freqHz / sampleRate)
        val windowStep = if (n > 1) 2.0 * PI / (n - 1) else 0.0
        var s1 = 0.0
        var s2 = 0.0
        var windowSum = 0.0
        for (i in 0 until n) {
            val w = if (hann && n > 1) 0.5 - 0.5 * cos(windowStep * i) else 1.0
            windowSum += w
            val s0 = samples[start + i] * w + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        if (windowSum <= 0.0) return 0.0
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return 2.0 * sqrt(max(0.0, power)) / windowSum / FULL_SCALE
    }

    /** Root-mean-square level of `samples[from, to)` as a fraction of full scale (0 for an empty range). */
    fun rms(samples: ShortArray, from: Int, to: Int): Double {
        val start = max(0, from)
        val end = min(samples.size, to)
        val n = end - start
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n) / FULL_SCALE
    }

    /** Average of the channels of interleaved PCM; a trailing partial frame is dropped. One channel is returned as is. */
    fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * The level of one tone inside a window of +-[halfWidthSec] around each of [centersSec] (the SFX cues) and
     * everywhere else (non-overlapping windows of the same width, kept a half window away from the cue windows).
     * `insideMin` is the weakest cue window, so a single missing cue shows. Non-finite cue times are ignored.
     */
    fun toneInWindows(
        samples: ShortArray,
        sampleRate: Int,
        freqHz: Double,
        centersSec: List<Double>,
        halfWidthSec: Double = 0.05
    ): WindowedTone {
        val cues = centersSec.filter { it.isFinite() }
        if (cues.isEmpty() || samples.isEmpty() || sampleRate <= 0 || !halfWidthSec.isFinite()) {
            return WindowedTone(0.0, 0.0, 0.0)
        }
        val half = (halfWidthSec * sampleRate).roundToInt()
        if (half <= 0) return WindowedTone(0.0, 0.0, 0.0)
        val width = 2 * half
        val starts = IntArray(cues.size) { (cues[it] * sampleRate).roundToInt() - half }

        var insideSum = 0.0
        var insideMin = Double.MAX_VALUE
        for (s in starts) {
            val level = goertzelAmplitude(samples, s, s + width, sampleRate, freqHz)
            insideSum += level
            insideMin = min(insideMin, level)
        }

        var outsideSum = 0.0
        var outsideCount = 0
        var windowStart = 0
        while (windowStart + width <= samples.size) {
            val windowEnd = windowStart + width
            var near = false
            for (s in starts) {
                if (windowStart < s + width + half && windowEnd > s - half) {
                    near = true
                    break
                }
            }
            if (!near) {
                outsideSum += goertzelAmplitude(samples, windowStart, windowEnd, sampleRate, freqHz)
                outsideCount++
            }
            windowStart = windowEnd
        }
        return WindowedTone(
            insideMean = insideSum / starts.size,
            insideMin = insideMin,
            outside = if (outsideCount > 0) outsideSum / outsideCount else 0.0
        )
    }

    // ------------------------------------------------------------------------------------------------------ images

    /** Central-difference gradient magnitude, zero on the one-pixel border. */
    fun gradientMagnitude(image: GrayImage): FloatArray {
        val w = image.width
        val h = image.height
        val d = image.data
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (d[i + 1] - d[i - 1]) * 0.5f
                val gy = (d[i + w] - d[i - w]) * 0.5f
                out[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** Mean absolute gray difference inside [box] (0..255). NaN when the sizes differ or the box is empty. */
    fun meanAbsDiff(a: GrayImage, b: GrayImage, box: PixelBox): Double {
        if (a.width != b.width || a.height != b.height) return Double.NaN
        val r = box.intersect(PixelBox.whole(a))
        if (r.isEmpty) return Double.NaN
        var sum = 0.0
        for (y in r.top until r.bottom) {
            val row = y * a.width
            for (x in r.left until r.right) sum += abs(a.data[row + x] - b.data[row + x])
        }
        return sum / (r.width.toLong() * r.height)
    }

    /** Variance of the 4-neighbour Laplacian inside [box] (a sharpness measure). NaN when the box has no interior pixel. */
    fun laplacianVariance(image: GrayImage, box: PixelBox): Double {
        val r = box.intersect(PixelBox(1, 1, image.width - 1, image.height - 1))
        if (r.isEmpty) return Double.NaN
        val w = image.width
        val d = image.data
        var sum = 0.0
        var sumSq = 0.0
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                val i = y * w + x
                val lap = (d[i - 1] + d[i + 1] + d[i - w] + d[i + w] - 4f * d[i]).toDouble()
                sum += lap
                sumSq += lap * lap
            }
        }
        val n = r.width.toDouble() * r.height
        val mean = sum / n
        return max(0.0, sumSq / n - mean * mean)
    }

    /** Sharpness of GLES against Canvas inside [box]: `variance(gles) / variance(canvas)`. NaN when Canvas has no detail. */
    fun laplacianRatio(canvas: GrayImage, gles: GrayImage, box: PixelBox): Double {
        if (canvas.width != gles.width || canvas.height != gles.height) return Double.NaN
        val c = laplacianVariance(canvas, box)
        val g = laplacianVariance(gles, box)
        if (!c.isFinite() || !g.isFinite() || c < MIN_LAPLACIAN_VARIANCE) return Double.NaN
        return g / c
    }

    /**
     * The number of intermediate-gray edge pixels inside [box]: pixels whose 3x3 neighbourhood spans at least
     * [AA_MIN_CONTRAST] gray levels and whose own value is more than [AA_GRAY_MARGIN] of that range away from both
     * the darkest and the brightest neighbour. A hard (aliased) edge has none; an anti-aliased edge has one or two
     * per crossing.
     */
    fun edgeAaCount(image: GrayImage, box: PixelBox): Int {
        val r = box.intersect(PixelBox(1, 1, image.width - 1, image.height - 1))
        if (r.isEmpty) return 0
        val w = image.width
        val d = image.data
        var count = 0
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                val i = y * w + x
                var lo = d[i]
                var hi = d[i]
                for (dy in -1..1) {
                    val row = i + dy * w
                    for (dx in -1..1) {
                        val v = d[row + dx]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                val range = hi - lo
                if (range >= AA_MIN_CONTRAST) {
                    val v = d[i]
                    if (v > lo + AA_GRAY_MARGIN * range && v < hi - AA_GRAY_MARGIN * range) count++
                }
            }
        }
        return count
    }

    /** `edgeAaCount(gles) / edgeAaCount(canvas)` inside [box]. NaN when Canvas has fewer than [AA_MIN_CANVAS_PIXELS]. */
    fun edgeAaRatio(canvas: GrayImage, gles: GrayImage, box: PixelBox): Double {
        if (canvas.width != gles.width || canvas.height != gles.height) return Double.NaN
        val c = edgeAaCount(canvas, box)
        if (c < AA_MIN_CANVAS_PIXELS) return Double.NaN
        return edgeAaCount(gles, box).toDouble() / c
    }

    /**
     * GLES ink over Canvas ink inside [box]: the summed distance from the background (the median gray of the Canvas
     * box). Used for the faint landscape mouth (R4); reported, not gating. NaN when Canvas has less than [MIN_INK].
     */
    fun inkRatio(canvas: GrayImage, gles: GrayImage, box: PixelBox): Double {
        if (canvas.width != gles.width || canvas.height != gles.height) return Double.NaN
        val r = box.intersect(PixelBox.whole(canvas))
        if (r.isEmpty) return Double.NaN
        val w = canvas.width
        val values = FloatArray(r.width * r.height)
        var k = 0
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) values[k++] = canvas.data[y * w + x]
        }
        values.sort()
        val background = values[values.size / 2]
        var inkCanvas = 0.0
        var inkGles = 0.0
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                val i = y * w + x
                inkCanvas += abs(canvas.data[i] - background)
                inkGles += abs(gles.data[i] - background)
            }
        }
        if (inkCanvas < MIN_INK) return Double.NaN
        return inkGles / inkCanvas
    }

    /**
     * The sub-pixel position (within -0.5..0.5) of the maximum of the parabola through (-1, [left]), (0, [mid]) and
     * (1, [right]). 0 when the three points are not a peak.
     */
    fun parabolaPeak(left: Double, mid: Double, right: Double): Double {
        val denominator = left - 2.0 * mid + right
        if (!(denominator < -1e-12)) return 0.0
        return (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
    }

    /**
     * How far [test] is displaced from [reference] inside [box]: the integer shift within +-[maxShift] pixels that
     * maximizes the normalized correlation of the two gradient-magnitude (edge) maps, refined per axis by a parabola
     * through the correlation at the neighbouring shifts. A shift at the edge of the range is not refined (the true
     * offset may be larger; it is far above any sensible threshold either way). The box is clamped so every shifted
     * sample stays inside the image. Cost is about 49 passes over the box, so pass a tight box on large frames.
     */
    fun estimateOffset(
        reference: GrayImage,
        test: GrayImage,
        box: PixelBox,
        maxShift: Int = DEFAULT_MAX_SHIFT
    ): OffsetEstimate {
        val none = OffsetEstimate(0.0, 0.0, 0.0, false)
        if (reference.width != test.width || reference.height != test.height || maxShift < 1) return none
        val w = reference.width
        val region = box.intersect(PixelBox(maxShift, maxShift, w - maxShift, reference.height - maxShift))
        if (region.isEmpty) return none

        val edgesReference = gradientMagnitude(reference)
        val edgesTest = gradientMagnitude(test)
        val side = 2 * maxShift + 1
        val correlation = DoubleArray(side * side)
        val n = region.width.toDouble() * region.height

        var best = Double.NEGATIVE_INFINITY
        var bestX = 0
        var bestY = 0
        for (sy in -maxShift..maxShift) {
            for (sx in -maxShift..maxShift) {
                var sa = 0.0
                var sb = 0.0
                var saa = 0.0
                var sbb = 0.0
                var sab = 0.0
                for (y in region.top until region.bottom) {
                    val rowA = y * w
                    val rowB = (y + sy) * w + sx
                    for (x in region.left until region.right) {
                        val a = edgesReference[rowA + x].toDouble()
                        val b = edgesTest[rowB + x].toDouble()
                        sa += a
                        sb += b
                        saa += a * a
                        sbb += b * b
                        sab += a * b
                    }
                }
                val varA = n * saa - sa * sa
                val varB = n * sbb - sb * sb
                val c = if (varA > 1e-9 && varB > 1e-9) (n * sab - sa * sb) / sqrt(varA * varB) else -1.0
                correlation[(sy + maxShift) * side + (sx + maxShift)] = c
                if (c > best) {
                    best = c
                    bestX = sx
                    bestY = sy
                }
            }
        }
        if (best < MIN_OFFSET_CORRELATION) return OffsetEstimate(0.0, 0.0, max(best, -1.0), false)

        fun at(sx: Int, sy: Int): Double = correlation[(sy + maxShift) * side + (sx + maxShift)]
        val fractionX =
            if (bestX > -maxShift && bestX < maxShift) parabolaPeak(at(bestX - 1, bestY), at(bestX, bestY), at(bestX + 1, bestY)) else 0.0
        val fractionY =
            if (bestY > -maxShift && bestY < maxShift) parabolaPeak(at(bestX, bestY - 1), at(bestX, bestY), at(bestX, bestY + 1)) else 0.0
        return OffsetEstimate(bestX + fractionX, bestY + fractionY, best, true)
    }

    /**
     * All per-frame numbers of the quick check for one sampled frame. The offset and the mean absolute difference
     * are measured inside [figureBox], the Laplacian ratio inside [textBox], the anti-aliasing ratio over the whole
     * frame, and (reported only) the mouth ink inside [mouthBox]. Different sizes give all NaN.
     */
    fun compareFrames(
        canvas: GrayImage,
        gles: GrayImage,
        figureBox: PixelBox,
        textBox: PixelBox,
        mouthBox: PixelBox? = null
    ): FrameMetrics {
        if (canvas.width != gles.width || canvas.height != gles.height) {
            return FrameMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
        val offset = estimateOffset(canvas, gles, figureBox)
        return FrameMetrics(
            offsetPx = if (offset.reliable) offset.magnitude else Double.NaN,
            meanAbsDiff = meanAbsDiff(canvas, gles, figureBox),
            laplacianRatio = laplacianRatio(canvas, gles, textBox),
            edgeAaRatio = edgeAaRatio(canvas, gles, PixelBox.whole(canvas)),
            mouthInkRatio = if (mouthBox != null) inkRatio(canvas, gles, mouthBox) else Double.NaN
        )
    }
}
