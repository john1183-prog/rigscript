package com.example.engine

import com.example.data.AppearanceSettings

/**
 * WP2.3 step 1: the one shared, pure place for face geometry (eyes, brows, mouth and the
 * secondary figure's simple face). Before this file the main figure's math lived in
 * `RigRenderer.Companion` and the secondary figure's constants were copied into both
 * `RigRenderer.drawSecondaryFace` and `GlesFigureFrame` (overlay figures). This is a PURE
 * REFACTOR: every expression below is the legacy expression, moved verbatim, so both renderers
 * draw exactly what they drew before (`FaceGeometryTest` keeps a copy of the legacy math as an
 * oracle and asserts exact equality). Step 2 (`ResolvedFace`, `ExpressionBlender`, gaze) builds
 * on these functions; steady state must keep equalling them (I1).
 *
 * No Canvas, Paint or Android types: JVM-testable, and the two renderers only draw the result (I3).
 * The result types are top-level on purpose: a class declared inside a companion object cannot be
 * named from another file (recurring Kotlin pitfall in this repo, see `RigRenderer.Companion`).
 */

/** An oval in canvas-pixel space; [rotationDeg] follows `Canvas.rotate` (clockwise positive, Y down). */
data class FaceOval(
    val cx: Float, val cy: Float, val halfWidth: Float, val halfHeight: Float,
    val rotationDeg: Float = 0f
)

/** One straight segment, in canvas-pixel space. */
data class FaceLine(val sx: Float, val sy: Float, val ex: Float, val ey: Float)

/** Both eyes plus the perpendicular/"up" basis eyebrow geometry needs. */
data class FaceEyes(
    val left: FaceOval, val right: FaceOval,
    val perpX: Float, val perpY: Float, val upX: Float, val upY: Float,
    /** Gaze offset (pixels) already included in [left] and [right]; the brows subtract it (they stay on the face). 0 in the legacy path. */
    val gazeDx: Float = 0f, val gazeDy: Float = 0f
)

/** Both eyebrow line segments plus their shared half-width. */
data class FaceBrows(val left: FaceLine, val right: FaceLine, val halfWidth: Float)

/**
 * The secondary (overlay) figure's static face, relative to its head centre (cx, cy) and head
 * radius r. Offsets are returned (not absolute positions) so each renderer evaluates the same
 * float additions as before.
 *
 * Eyes sit at (cx -/+ [eyeOffsetX], cy + [eyeOffsetY]) with radii [eyeRx] x [eyeRy]. The mouth
 * baseline is `mouthY = cy + mouthDy`, half-width [mouthHalfW]. When [mouthCurved] the mouth is a
 * quadratic from (cx - w, mouthY + [curveEndDy]) through control (cx, mouthY + [curveCtrlDy]) to
 * (cx + w, mouthY + [curveEndDy]); otherwise a straight line at mouthY.
 */
data class SecondaryFaceGeometry(
    val eyeRx: Float, val eyeRy: Float, val eyeOffsetX: Float, val eyeOffsetY: Float,
    val mouthDy: Float, val mouthHalfW: Float,
    val mouthCurved: Boolean, val curveEndDy: Float, val curveCtrlDy: Float
)

object FaceGeometry {

    /**
     * How far the head bone (hx,hy = tip, nx,ny = origin) has rotated from its own upright rest
     * pose, in degrees, in the same sense as `Canvas.rotate` (positive = clockwise, Y-down pixel
     * space). Shared by [mouth] and [eyes] so both rotate identically with the head. At rest (bone
     * pointing straight up, hy<ny, hx==nx) this is 0.
     */
    fun headRotationDeg(hx: Float, hy: Float, nx: Float, ny: Float): Float {
        val restDeg = -90.0
        val boneDeg = Math.toDegrees(kotlin.math.atan2((hy - ny).toDouble(), (hx - nx).toDouble()))
        return (boneDeg - restDeg).toFloat()
    }

    /**
     * Pure geometry for the mouth oval. hx,hy = head circle centre (bone tip); nx,ny = neck point
     * (bone origin); r = head's drawn radius (already includes headScaleMultiplier).
     * headScaleMultiplier is also applied to the anchor offset, not just to r: the anchor is
     * derived from the head bone's fixed FK length, which does not scale with the drawn radius.
     *
     * Anchor coefficient history: 0.42 (original) -> 0.36 -> 0.24 (tried, reverted after
     * overlapping the eyes) -> 0.24 again; the overlap was fixed by moving the EYES up
     * (`AppearanceSettings.eyeVerticalOffsetNormalized`), not by moving the mouth back down.
     */
    fun mouth(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        mouthShape: Int, mouthOpenness: Float, expression: Int,
        headScaleMultiplier: Float
    ): FaceOval {
        val widthMul: Float
        val cyBiasFrac: Float
        when (expression) {
            Expression.HAPPY -> { widthMul = 1.12f; cyBiasFrac = -0.06f }
            Expression.WORRIED, Expression.ANGRY -> { widthMul = 0.90f; cyBiasFrac = 0.05f }
            else -> { widthMul = 1f; cyBiasFrac = 0f }
        }
        return mouthCore(hx, hy, nx, ny, r, mouthShape, mouthOpenness, widthMul, cyBiasFrac, headScaleMultiplier)
    }

    /** The mouth for a [ResolvedFace] (acting path). With [ResolvedFace.setLegacy] it equals [mouth] bit for bit. */
    fun mouth(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        mouthShape: Int, mouthOpenness: Float, face: ResolvedFace,
        headScaleMultiplier: Float
    ): FaceOval =
        if (face.mouthActed) mouthActed(hx, hy, nx, ny, r, face, headScaleMultiplier)
        else mouthCore(hx, hy, nx, ny, r, mouthShape, mouthOpenness, face.mouthWidthMul, face.mouthCyBiasFrac, headScaleMultiplier)

    /**
     * WP2.4: the mouth from the continuous speech parameters (see `MouthTrack`). Same anchor and head rotation as
     * [mouthCore]; half-width and half-height are fractions of the head radius, so steady state at a class and amplitude
     * equals the legacy class oval (`MouthMotionTest`, except the roundness of a loud WIDE vowel).
     */
    private fun mouthActed(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float, face: ResolvedFace, headScaleMultiplier: Float
    ): FaceOval {
        val cx = hx + (nx - hx) * 0.24f * headScaleMultiplier
        val cy = hy + (ny - hy) * 0.24f * headScaleMultiplier + face.mouthCyBiasFrac * r
        val round = face.mouthRound.coerceIn(0f, 1f)
        val wFrac = (MOUTH_W_MIN + (MOUTH_W_MAX - MOUTH_W_MIN) * face.mouthWidth.coerceIn(0f, 1f)) * (1f - MOUTH_ROUND_W_CUT * round)
        val hFrac = (MOUTH_H0 + (MOUTH_HMAX - MOUTH_H0) * face.mouthOpen.coerceIn(0f, 1f)) * (1f + MOUTH_ROUND_H_GAIN * round)
        return FaceOval(cx, cy, wFrac * r * face.mouthWidthMul, hFrac * r, headRotationDeg(hx, hy, nx, ny))
    }

    private const val MOUTH_W_MIN = 0.32f
    private const val MOUTH_W_MAX = 0.46f
    private const val MOUTH_H0 = 0.025f
    private const val MOUTH_HMAX = 0.28f
    private const val MOUTH_ROUND_W_CUT = 0.12f
    private const val MOUTH_ROUND_H_GAIN = 0.25f

    private fun mouthCore(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        mouthShape: Int, mouthOpenness: Float, widthMul: Float, cyBiasFrac: Float,
        headScaleMultiplier: Float
    ): FaceOval {
        val cx = hx + (nx - hx) * 0.24f * headScaleMultiplier
        var cy = hy + (ny - hy) * 0.24f * headScaleMultiplier

        val (wFrac, hFrac) = when (mouthShape) {
            MouthShape.WIDE   -> 0.44f to 0.28f
            MouthShape.NARROW -> 0.32f to 0.10f
            MouthShape.CLOSED -> 0.44f to 0.05f
            else              -> 0.42f to 0.14f   // MID
        }

        cy += cyBiasFrac * r

        val hw = wFrac * r * widthMul
        val hh = hFrac * r * (0.5f + 0.5f * mouthOpenness.coerceIn(0f, 1f))
        return FaceOval(cx, cy, hw, hh, headRotationDeg(hx, hy, nx, ny))
    }

    /**
     * Pure geometry for both eyes, including the perpendicular-to-head-neck-axis technique that
     * makes eye separation follow head tilt for free. Each oval carries the head's rotation, so
     * the SHAPE turns rigidly with the head (a screen-axis-aligned shape made one eye collapse to
     * a sliver under real rotation; confirmed on device video, see V2_DECISIONS.md).
     */
    fun eyes(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        openness: Float, expression: Int, headScaleMultiplier: Float,
        appearance: AppearanceSettings
    ): FaceEyes {
        val baseFrac = when (expression) {
            Expression.WIDE, Expression.HAPPY -> 0.20f
            Expression.SQUINT -> 0.11f
            else -> 0.15f
        }
        return eyesCore(hx, hy, nx, ny, r, openness, baseFrac, 1f, 0f, 0f, headScaleMultiplier, appearance)
    }

    /** The eyes for a [ResolvedFace] (acting path): its size, height multiplier, blink and gaze. With [ResolvedFace.setLegacy] it equals [eyes] bit for bit. */
    fun eyes(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        face: ResolvedFace, headScaleMultiplier: Float,
        appearance: AppearanceSettings
    ): FaceEyes = eyesCore(hx, hy, nx, ny, r, face.eyeOpenness, face.eyeRadiusFrac, face.eyeHeightMul, face.gazeX, face.gazeY, headScaleMultiplier, appearance)

    private fun eyesCore(
        hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
        openness: Float, baseFrac: Float, heightMul: Float, gazeX: Float, gazeY: Float,
        headScaleMultiplier: Float, appearance: AppearanceSettings
    ): FaceEyes {
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
        val baseEyeRadius = baseFrac * r
        val openFactor = openness.coerceIn(0f, 1f)
        var eyeRadiusY = baseEyeRadius * appearance.eyeAspectRatio * (0.12f + 0.88f * openFactor)
        if (heightMul != 1f) eyeRadiusY *= heightMul

        var leftX  = cx - perpX * eyeSpacing
        var leftY  = cy - perpY * eyeSpacing
        var rightX = cx + perpX * eyeSpacing
        var rightY = cy + perpY * eyeSpacing

        // Gaze: +x is screen right and +y is up for an upright head; (-perp) points screen right and (up) screen up there,
        // so the offset turns with the head. Skipped when zero so the legacy path stays bit-equal.
        var gdx = 0f
        var gdy = 0f
        if (gazeX != 0f || gazeY != 0f) {
            gdx = -perpX * gazeX * r + upX * gazeY * r
            gdy = -perpY * gazeX * r + upY * gazeY * r
            leftX += gdx; leftY += gdy; rightX += gdx; rightY += gdy
        }

        val rotationDeg = headRotationDeg(hx, hy, nx, ny)

        return FaceEyes(
            left  = FaceOval(leftX, leftY, baseEyeRadius, eyeRadiusY, rotationDeg),
            right = FaceOval(rightX, rightY, baseEyeRadius, eyeRadiusY, rotationDeg),
            perpX = perpX, perpY = perpY, upX = upX, upY = upY,
            gazeDx = gdx, gazeDy = gdy
        )
    }

    /**
     * Pure geometry for the eyebrows. Returns null for any expression other than WORRIED/ANGRY
     * (legacy: eyebrows are synthetic, not rig bones, and only drawn for those two).
     */
    fun brows(eyes: FaceEyes, expression: Int): FaceBrows? {
        if (expression != Expression.WORRIED && expression != Expression.ANGRY) return null
        return browsCore(eyes, 1.8f, if (expression == Expression.WORRIED) 0.55f else -0.55f)
    }

    /** The brows for a [ResolvedFace]: null when it says none are drawn. With [ResolvedFace.setLegacy] it equals [brows] bit for bit. */
    fun brows(eyes: FaceEyes, face: ResolvedFace): FaceBrows? =
        if (!face.browsVisible) null else browsCore(eyes, face.browHeight, face.browInnerLift)

    private fun browsCore(eyes: FaceEyes, heightEyeRadii: Float, innerLiftEyeRadii: Float): FaceBrows {
        val eyeRadius = eyes.left.halfWidth   // baseEyeRadius, same magnitude for both eyes
        val browHeight  = eyeRadius * heightEyeRadii
        val browHalfLen = eyeRadius * 1.0f
        val innerLift = eyeRadius * innerLiftEyeRadii
        val perpX = eyes.perpX; val perpY = eyes.perpY
        val upX = eyes.upX; val upY = eyes.upY
        // The brows belong to the face, not to the eyeballs: they stay where the eyes would be without gaze.
        val lcx = eyes.left.cx - eyes.gazeDx;  val lcy = eyes.left.cy - eyes.gazeDy
        val rcx = eyes.right.cx - eyes.gazeDx; val rcy = eyes.right.cy - eyes.gazeDy

        val left = FaceLine(
            lcx - perpX * browHalfLen + upX * browHeight,
            lcy - perpY * browHalfLen + upY * browHeight,
            lcx + perpX * browHalfLen + upX * (browHeight + innerLift),
            lcy + perpY * browHalfLen + upY * (browHeight + innerLift)
        )
        val right = FaceLine(
            rcx + perpX * browHalfLen + upX * browHeight,
            rcy + perpY * browHalfLen + upY * browHeight,
            rcx - perpX * browHalfLen + upX * (browHeight + innerLift),
            rcy - perpY * browHalfLen + upY * (browHeight + innerLift)
        )
        // The Canvas brow paint uses strokeWidth = eyeRadius * 0.35f: half of that for a half-width.
        return FaceBrows(left, right, eyeRadius * 0.175f)
    }

    /**
     * The secondary figure's static, expression-driven face (no audio channel to sync to, so not
     * the main figure's mouth/eye system). Constants are the legacy ones from
     * `RigRenderer.drawSecondaryFace` and `GlesFigureFrame`'s overlay-figure face.
     */
    fun secondary(r: Float, expression: Int): SecondaryFaceGeometry {
        val eyeRx = r * 0.12f
        val eyeRy = when (expression) {
            Expression.SQUINT -> eyeRx * 0.35f
            Expression.WIDE, Expression.HAPPY -> eyeRx * 1.3f
            else -> eyeRx
        }
        return when (expression) {
            Expression.HAPPY -> SecondaryFaceGeometry(
                eyeRx, eyeRy, r * 0.35f, -r * 0.1f, r * 0.4f, r * 0.3f,
                mouthCurved = true, curveEndDy = 0f, curveCtrlDy = r * 0.35f
            )
            Expression.ANGRY, Expression.WORRIED -> SecondaryFaceGeometry(
                eyeRx, eyeRy, r * 0.35f, -r * 0.1f, r * 0.4f, r * 0.3f,
                mouthCurved = true, curveEndDy = r * 0.15f, curveCtrlDy = -r * 0.2f
            )
            else -> SecondaryFaceGeometry(
                eyeRx, eyeRy, r * 0.35f, -r * 0.1f, r * 0.4f, r * 0.3f,
                mouthCurved = false, curveEndDy = 0f, curveCtrlDy = 0f
            )
        }
    }
}
