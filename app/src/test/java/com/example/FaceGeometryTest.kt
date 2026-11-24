package com.example

import com.example.data.AppearanceSettings
import com.example.engine.Expression
import com.example.engine.FaceBrows
import com.example.engine.FaceEyes
import com.example.engine.FaceGeometry
import com.example.engine.FaceOval
import com.example.engine.MouthShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.3 step 1 (pure refactor): `FaceGeometry` must equal the legacy face math EXACTLY.
 *
 * [LegacyFace] is the legacy code copied verbatim (comments stripped, types renamed) from
 * `RigRenderer.Companion` at commit 9ed354c, i.e. the code both renderers called before the
 * refactor. The tests sweep a grid of inputs and require bit-for-bit equal results, which is the
 * evidence that neither renderer draws anything different. The hard numbers further down were
 * derived BY READING that code (see each comment), not by running it. Step 2 (ExpressionBlender,
 * gaze) must keep steady-state output equal to these functions.
 */
class FaceGeometryTest {

    private class LOval(val cx: Float, val cy: Float, val halfWidth: Float, val halfHeight: Float, val rotationDeg: Float = 0f)
    private class LLine(val sx: Float, val sy: Float, val ex: Float, val ey: Float)
    private class LEyes(val left: LOval, val right: LOval, val perpX: Float, val perpY: Float, val upX: Float, val upY: Float)
    private class LBrows(val left: LLine, val right: LLine, val halfWidth: Float)

    private object LegacyFace {
        fun headRotationDeg(hx: Float, hy: Float, nx: Float, ny: Float): Float {
            val restDeg = -90.0
            val boneDeg = Math.toDegrees(kotlin.math.atan2((hy - ny).toDouble(), (hx - nx).toDouble()))
            return (boneDeg - restDeg).toFloat()
        }

        fun mouth(
            hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
            mouthShape: Int, mouthOpenness: Float, expression: Int,
            headScaleMultiplier: Float
        ): LOval {
            val cx = hx + (nx - hx) * 0.24f * headScaleMultiplier
            var cy = hy + (ny - hy) * 0.24f * headScaleMultiplier

            val (wFrac, hFrac) = when (mouthShape) {
                MouthShape.WIDE   -> 0.44f to 0.28f
                MouthShape.NARROW -> 0.32f to 0.10f
                MouthShape.CLOSED -> 0.44f to 0.05f
                else              -> 0.42f to 0.14f   // MID
            }

            val widthMul: Float
            val cyBiasFrac: Float
            when (expression) {
                Expression.HAPPY -> { widthMul = 1.12f; cyBiasFrac = -0.06f }
                Expression.WORRIED, Expression.ANGRY -> { widthMul = 0.90f; cyBiasFrac = 0.05f }
                else -> { widthMul = 1f; cyBiasFrac = 0f }
            }
            cy += cyBiasFrac * r

            val hw = wFrac * r * widthMul
            val hh = hFrac * r * (0.5f + 0.5f * mouthOpenness.coerceIn(0f, 1f))
            return LOval(cx, cy, hw, hh, headRotationDeg(hx, hy, nx, ny))
        }

        fun eyes(
            hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
            openness: Float, expression: Int, headScaleMultiplier: Float,
            appearance: AppearanceSettings
        ): LEyes {
            val cx = hx + (nx - hx) * appearance.eyeVerticalOffsetNormalized * headScaleMultiplier
            val cy = hy + (ny - hy) * appearance.eyeVerticalOffsetNormalized * headScaleMultiplier

            val rawPx = -(ny - hy)
            val rawPy = (nx - hx)
            val plen = kotlin.math.sqrt(rawPx * rawPx + rawPy * rawPy).let { if (it < 0.0001f) 1f else it }
            val perpX = rawPx / plen
            val perpY = rawPy / plen
            val upX = -perpY
            val upY = perpX

            val eyeSpacing = appearance.eyeSpacingNormalized * r
            val baseEyeRadius = when (expression) {
                Expression.WIDE, Expression.HAPPY -> 0.20f * r
                Expression.SQUINT                  -> 0.11f * r
                else                                -> 0.15f * r
            }
            val openFactor = openness.coerceIn(0f, 1f)
            val eyeRadiusY = baseEyeRadius * appearance.eyeAspectRatio * (0.12f + 0.88f * openFactor)

            val leftX  = cx - perpX * eyeSpacing
            val leftY  = cy - perpY * eyeSpacing
            val rightX = cx + perpX * eyeSpacing
            val rightY = cy + perpY * eyeSpacing

            val rotationDeg = headRotationDeg(hx, hy, nx, ny)

            return LEyes(
                left  = LOval(leftX, leftY, baseEyeRadius, eyeRadiusY, rotationDeg),
                right = LOval(rightX, rightY, baseEyeRadius, eyeRadiusY, rotationDeg),
                perpX = perpX, perpY = perpY, upX = upX, upY = upY
            )
        }

        fun brows(eyes: LEyes, expression: Int): LBrows? {
            if (expression != Expression.WORRIED && expression != Expression.ANGRY) return null

            val eyeRadius = eyes.left.halfWidth   // baseEyeRadius, same magnitude for both eyes
            val browHeight  = eyeRadius * 1.8f
            val browHalfLen = eyeRadius * 1.0f
            val innerLift = eyeRadius * (if (expression == Expression.WORRIED) 0.55f else -0.55f)
            val perpX = eyes.perpX; val perpY = eyes.perpY
            val upX = eyes.upX; val upY = eyes.upY

            val left = LLine(
                eyes.left.cx - perpX * browHalfLen + upX * browHeight,
                eyes.left.cy - perpY * browHalfLen + upY * browHeight,
                eyes.left.cx + perpX * browHalfLen + upX * (browHeight + innerLift),
                eyes.left.cy + perpY * browHalfLen + upY * (browHeight + innerLift)
            )
            val right = LLine(
                eyes.right.cx + perpX * browHalfLen + upX * browHeight,
                eyes.right.cy + perpY * browHalfLen + upY * browHeight,
                eyes.right.cx - perpX * browHalfLen + upX * (browHeight + innerLift),
                eyes.right.cy - perpY * browHalfLen + upY * (browHeight + innerLift)
            )
            return LBrows(left, right, eyeRadius * 0.175f)
        }
    }

    private val expressions = listOf(Expression.NORMAL, Expression.WIDE, Expression.SQUINT, Expression.WORRIED, Expression.ANGRY, Expression.HAPPY)
    private val shapes = listOf(MouthShape.CLOSED, MouthShape.MID, MouthShape.WIDE, MouthShape.NARROW, 99)
    private val opennesses = listOf(-0.3f, 0f, 0.37f, 1f, 1.6f)
    private val scales = listOf(0.5f, 1f, 1.7f)
    private val radii = listOf(0f, 11.5f, 43.2f, 120f)

    /** (hx, hy, nx, ny): upright, tilted both ways, upside down, sideways, and a zero-length bone. */
    private val heads = listOf(
        floatArrayOf(540f, 410f, 540f, 520f),
        floatArrayOf(512.5f, 405.25f, 540f, 520f),
        floatArrayOf(571.3f, 411f, 540f, 520f),
        floatArrayOf(540f, 630f, 540f, 520f),
        floatArrayOf(640f, 520f, 540f, 520f),
        floatArrayOf(300f, 300f, 300f, 300f)
    )

    private val appearances = listOf(
        AppearanceSettings(),
        AppearanceSettings(eyeSpacingNormalized = 0.5f, eyeVerticalOffsetNormalized = 0.1f, eyeAspectRatio = 0.8f)
    )

    private fun same(label: String, a: Float, b: Float) {
        assertTrue("$label: $a vs $b", a.toBits() == b.toBits())
    }

    private fun sameOval(label: String, a: FaceOval, b: LOval) {
        same("$label.cx", a.cx, b.cx); same("$label.cy", a.cy, b.cy)
        same("$label.hw", a.halfWidth, b.halfWidth); same("$label.hh", a.halfHeight, b.halfHeight)
        same("$label.rot", a.rotationDeg, b.rotationDeg)
    }

    private fun sameLine(label: String, a: com.example.engine.FaceLine, b: LLine) {
        same("$label.sx", a.sx, b.sx); same("$label.sy", a.sy, b.sy)
        same("$label.ex", a.ex, b.ex); same("$label.ey", a.ey, b.ey)
    }

    private fun sameEyes(label: String, a: FaceEyes, b: LEyes) {
        sameOval("$label.left", a.left, b.left); sameOval("$label.right", a.right, b.right)
        same("$label.perpX", a.perpX, b.perpX); same("$label.perpY", a.perpY, b.perpY)
        same("$label.upX", a.upX, b.upX); same("$label.upY", a.upY, b.upY)
    }

    // -- Equality with the legacy code over a grid ---------------------------------------------------------

    @Test
    fun headRotationEqualsLegacy() {
        for (h in heads) same("rot", FaceGeometry.headRotationDeg(h[0], h[1], h[2], h[3]), LegacyFace.headRotationDeg(h[0], h[1], h[2], h[3]))
    }

    @Test
    fun mouthEqualsLegacyOnTheWholeGrid() {
        var n = 0
        for (h in heads) for (e in expressions) for (s in shapes) for (o in opennesses) for (sc in scales) for (r in radii) {
            val now = FaceGeometry.mouth(h[0], h[1], h[2], h[3], r, s, o, e, sc)
            val was = LegacyFace.mouth(h[0], h[1], h[2], h[3], r, s, o, e, sc)
            sameOval("mouth e=$e s=$s o=$o sc=$sc r=$r", now, was)
            n++
        }
        assertTrue(n > 5000)
    }

    @Test
    fun eyesAndBrowsEqualLegacyOnTheWholeGrid() {
        var n = 0
        for (a in appearances) for (h in heads) for (e in expressions) for (o in opennesses) for (sc in scales) for (r in radii) {
            val now = FaceGeometry.eyes(h[0], h[1], h[2], h[3], r, o, e, sc, a)
            val was = LegacyFace.eyes(h[0], h[1], h[2], h[3], r, o, e, sc, a)
            sameEyes("eyes e=$e o=$o sc=$sc r=$r", now, was)

            val bNow = FaceGeometry.brows(now, e)
            val bWas = LegacyFace.brows(was, e)
            if (bWas == null) assertTrue("brows must be null for expression $e", bNow == null)
            else {
                assertTrue("brows must exist for expression $e", bNow != null)
                sameLine("browL e=$e", bNow!!.left, bWas.left); sameLine("browR e=$e", bNow.right, bWas.right)
                same("brow halfWidth", bNow.halfWidth, bWas.halfWidth)
            }
            n++
        }
        assertTrue(n == 4320)
    }

    // -- Hard numbers derived BY READING the legacy code ---------------------------------------------------

    private fun near(label: String, expected: Double, actual: Float, tol: Double = 1e-4) {
        assertTrue("$label: expected $expected, got $actual", Math.abs(expected - actual.toDouble()) <= tol)
    }

    // Upright head: tip (100, 50), neck origin (100, 150), r = 20, headScale 1, default appearance
    // (eyeSpacing 0.34, eyeVerticalOffset -0.03, eyeAspect 1.2).
    private val hx = 100f; private val hy = 50f; private val nx = 100f; private val ny = 150f; private val r = 20f

    @Test
    fun mouthSteadyStateNumbersForEverySixExpressions() {
        // Read from the legacy code: cx = hx + (nx-hx)*0.24 = 100; cy = hy + (ny-hy)*0.24 = 74, then
        // cy += cyBias * r with HAPPY -0.06 (74 - 1.2 = 72.8) and WORRIED/ANGRY +0.05 (74 + 1.0 = 75).
        // MID shape: halfWidth = 0.42 * r * widthMul (HAPPY 1.12, WORRIED/ANGRY 0.90, else 1);
        // halfHeight = 0.14 * r * (0.5 + 0.5 * openness). Head upright, so rotation is 0.
        val cyExpected = mapOf(Expression.NORMAL to 74.0, Expression.WIDE to 74.0, Expression.SQUINT to 74.0,
            Expression.WORRIED to 75.0, Expression.ANGRY to 75.0, Expression.HAPPY to 72.8)
        val wMul = mapOf(Expression.NORMAL to 1.0, Expression.WIDE to 1.0, Expression.SQUINT to 1.0,
            Expression.WORRIED to 0.9, Expression.ANGRY to 0.9, Expression.HAPPY to 1.12)
        for (e in expressions) {
            val g = FaceGeometry.mouth(hx, hy, nx, ny, r, MouthShape.MID, 1f, e, 1f)
            near("cx e=$e", 100.0, g.cx); near("cy e=$e", cyExpected[e]!!, g.cy)
            near("hw e=$e", 0.42 * 20.0 * wMul[e]!!, g.halfWidth); near("hh e=$e", 0.14 * 20.0, g.halfHeight)
            near("rot e=$e", 0.0, g.rotationDeg)
        }
        // Closed mouth at openness 0: halfHeight = 0.05 * r * 0.5; WIDE shape: 0.44 x 0.28.
        near("closed hh", 0.05 * 20.0 * 0.5, FaceGeometry.mouth(hx, hy, nx, ny, r, MouthShape.CLOSED, 0f, Expression.NORMAL, 1f).halfHeight)
        near("wide hw", 0.44 * 20.0, FaceGeometry.mouth(hx, hy, nx, ny, r, MouthShape.WIDE, 1f, Expression.NORMAL, 1f).halfWidth)
        near("wide hh", 0.28 * 20.0, FaceGeometry.mouth(hx, hy, nx, ny, r, MouthShape.WIDE, 1f, Expression.NORMAL, 1f).halfHeight)
    }

    @Test
    fun eyeSteadyStateNumbersForEverySixExpressions() {
        // Read from the legacy code: eye centre y = hy + (ny-hy) * -0.03 = 47; the perpendicular of an
        // upright bone is (-1, 0), so left = cx - perpX*spacing = 100 + 0.34*20 = 106.8 and right = 93.2.
        // Base radius by expression: NORMAL/WORRIED/ANGRY 0.15 r = 3, WIDE/HAPPY 0.20 r = 4, SQUINT 0.11 r = 2.2.
        // Open (openness 1): halfHeight = baseRadius * 1.2; closed (0): baseRadius * 1.2 * 0.12.
        val radius = mapOf(Expression.NORMAL to 3.0, Expression.WIDE to 4.0, Expression.SQUINT to 2.2,
            Expression.WORRIED to 3.0, Expression.ANGRY to 3.0, Expression.HAPPY to 4.0)
        val a = AppearanceSettings()
        for (e in expressions) {
            val open = FaceGeometry.eyes(hx, hy, nx, ny, r, 1f, e, 1f, a)
            near("left.cx e=$e", 106.8, open.left.cx); near("right.cx e=$e", 93.2, open.right.cx)
            near("cy e=$e", 47.0, open.left.cy); near("cy right e=$e", 47.0, open.right.cy)
            near("hw e=$e", radius[e]!!, open.left.halfWidth); near("hh open e=$e", radius[e]!! * 1.2, open.left.halfHeight)
            near("rot e=$e", 0.0, open.left.rotationDeg)
            val shut = FaceGeometry.eyes(hx, hy, nx, ny, r, 0f, e, 1f, a)
            near("hh shut e=$e", radius[e]!! * 1.2 * 0.12, shut.left.halfHeight)
        }
    }

    @Test
    fun browSteadyStateNumbers() {
        // Read from the legacy code: only WORRIED and ANGRY have brows (null for the other four).
        // eyeRadius = 3, browHeight = 1.8*3 = 5.4, half length 3, inner lift +-0.55*3 = +-1.65.
        // Upright head: perp = (-1, 0), up = (0, -1) (y grows downward), left eye centre (106.8, 47).
        // Left brow starts at the outer end (x 109.8, y 47-5.4 = 41.6) and ends at the inner end (x 103.8):
        // WORRIED inner end is higher (y 47-7.05 = 39.95), ANGRY inner end is lower (y 47-3.75 = 43.25).
        val a = AppearanceSettings()
        for (e in expressions) {
            val eyes = FaceGeometry.eyes(hx, hy, nx, ny, r, 1f, e, 1f, a)
            val b = FaceGeometry.brows(eyes, e)
            if (e != Expression.WORRIED && e != Expression.ANGRY) { assertTrue("e=$e", b == null); continue }
            assertTrue(b != null)
            near("sx e=$e", 109.8, b!!.left.sx); near("sy e=$e", 41.6, b.left.sy)
            near("ex e=$e", 103.8, b.left.ex)
            near("ey e=$e", if (e == Expression.WORRIED) 39.95 else 43.25, b.left.ey)
            near("halfWidth e=$e", 3.0 * 0.175, b.halfWidth)
        }
    }

    @Test
    fun secondaryFaceEqualsTheLegacyInlineMath() {
        // The legacy secondary face (RigRenderer.drawSecondaryFace and the GLES overlay-figure face), inline:
        // eyeRx = r*0.12; eyeRy = eyeRx*0.35 (SQUINT), eyeRx*1.3 (WIDE/HAPPY), else eyeRx; offsets (r*0.35, -r*0.1);
        // mouthY = cy + r*0.4, half width r*0.3; HAPPY: quad from (cx-w, mouthY) via (cx, mouthY + r*0.35);
        // ANGRY/WORRIED: ends at mouthY + r*0.15, control at mouthY - r*0.2; otherwise a straight line.
        for (rr in radii) for (e in expressions) {
            val f = FaceGeometry.secondary(rr, e)
            val eyeRx = rr * 0.12f
            val eyeRy = when (e) { Expression.SQUINT -> eyeRx * 0.35f; Expression.WIDE, Expression.HAPPY -> eyeRx * 1.3f; else -> eyeRx }
            same("rx", f.eyeRx, eyeRx); same("ry", f.eyeRy, eyeRy)
            same("ox", f.eyeOffsetX, rr * 0.35f); same("oy", f.eyeOffsetY, -rr * 0.1f)
            same("mdy", f.mouthDy, rr * 0.4f); same("mhw", f.mouthHalfW, rr * 0.3f)
            val cy = 217.25f; val mouthY = cy + rr * 0.4f
            when (e) {
                Expression.HAPPY -> {
                    assertTrue(f.mouthCurved)
                    same("happy end", mouthY + f.curveEndDy, mouthY); same("happy ctrl", mouthY + f.curveCtrlDy, mouthY + rr * 0.35f)
                }
                Expression.ANGRY, Expression.WORRIED -> {
                    assertTrue(f.mouthCurved)
                    same("angry end", mouthY + f.curveEndDy, mouthY + rr * 0.15f); same("angry ctrl", mouthY + f.curveCtrlDy, mouthY - rr * 0.2f)
                }
                else -> assertTrue(!f.mouthCurved)
            }
        }
    }
}
