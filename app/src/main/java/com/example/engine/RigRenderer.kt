package com.example.engine

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.AppearanceSettings
import com.example.data.ReferenceOverlay
import kotlin.math.min

/**
 * Forward-kinematic stick-figure renderer.
 *
 * Deliberately a [class], NOT an `object` — the live preview and VideoExporter
 * run on different threads simultaneously. Each caller owns its own instance so
 * the pre-allocated [matrices]/[pts]/paints are never shared across threads.
 */
class RigRenderer {

    private val rig    = StickFigureRig
    private val bones  = rig.BONES
    private val n      = rig.BONE_COUNT

    private val matrices = Array(n) { Matrix() }
    private val pts      = FloatArray(4)   // [startX, startY, endX, endY]

    private val bonePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val headPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // V2
    private val eyePaint        = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eyebrowPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val groundPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }

    // V2 — scene shapes / atmosphere / caption / reference overlay
    private val sceneShapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val atmospherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val captionBgPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x99000000L.toInt() }
    private val captionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
    private val overlayTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val overlayBgPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x88000000L.toInt() }
    private val overlayImageSrcRect = Rect()
    private val overlayImageDstRect = RectF()

    fun draw(
        canvas: Canvas,
        angles: FloatArray,
        appearance: AppearanceSettings,
        canvasW: Int,
        canvasH: Int,
        forExport: Boolean = false,
        mouthShape: Int = MouthShape.CLOSED,
        mouthOpenness: Float = 0f,
        expression: Int = Expression.NORMAL,
        eyeOpenness: Float = 1f,
        cameraZoom: Float = 1f,
        cameraPanX: Float = 0f,
        cameraPanY: Float = 0f,
        cameraShakeIntensity: Float = 0f,
        // V2 — scene, resolved by PlaybackEngine, null = no scripted override
        skyColor: Long? = null,
        groundColor: Long? = null,
        horizonY: Float? = null,
        sceneShape: String = SceneShape.NONE,
        sceneAtmosphere: String = SceneAtmosphere.NONE,
        currentTimeSec: Float = 0f,
        // V2 — caption (bounded-window text resolved by PlaybackEngine)
        captionText: String? = null,
        // V2 — manual reference overlay + its pre-decoded bitmap (image variant only)
        referenceOverlay: ReferenceOverlay? = null,
        referenceOverlayBitmap: Bitmap? = null
    ) {
        val minDim  = min(canvasW, canvasH).toFloat()
        val scale   = minDim * appearance.characterScale
        val rootX   = canvasW * appearance.rootAnchorX
        val rootY   = canvasH * appearance.rootAnchorY

        // ── Camera transform (V2, purely AI-JSON-driven — no automatic
        // behaviour derives this from amplitude). Wraps EVERYTHING below —
        // background, ground line, grid, and the figure — so a zoom/pan/shake
        // reads as an actual camera move over the whole scene, not just the
        // character scaling in place against a fixed backdrop.
        val zoom = cameraZoom.coerceAtLeast(0.1f)
        val shakeMag = cameraShakeIntensity.coerceIn(0f, 1f) * minDim * 0.03f
        val shakeX = if (shakeMag > 0f) (kotlin.random.Random.nextFloat() * 2f - 1f) * shakeMag else 0f
        val shakeY = if (shakeMag > 0f) (kotlin.random.Random.nextFloat() * 2f - 1f) * shakeMag else 0f

        canvas.save()
        canvas.scale(zoom, zoom, canvasW / 2f, canvasH / 2f)
        // translate() composes in the coordinate space canvas.scale() just
        // established, so an unscaled pan distance here would visually travel
        // `zoom` times further than cameraPanX's documented "fraction of
        // canvas width" implies — a real bug caught on review, worked through
        // via Canvas's transform composition rules rather than seen rendered.
        // Dividing by zoom here cancels that back out.
        canvas.translate(
            (cameraPanX * canvasW + shakeX) / zoom,
            (cameraPanY * canvasH + shakeY) / zoom
        )

        // ── Background / scene ───────────────────────────────────────────────
        val bgColor = if (forExport) appearance.exportBgColor else appearance.previewBgColor
        // Oversized (3x canvas, centred) so a zoomed-in or panned camera never
        // exposes an edge — filling only the literal (0,0,w,h) rect would leave
        // gaps once the transform above is applied. A fully-transparent
        // bgColor (WebM alpha export) still behaves correctly: Canvas draws
        // under SRC_OVER by default, so alpha=0 here is a no-op and preserves
        // whatever transparency the caller already established on the Bitmap.
        // V2 — scripted sky/ground scene bands take priority over the plain
        // solid/gradient background WHEN either is actually set. Null-for-both
        // (no scene events in the script) falls straight through to the
        // original behaviour below, unchanged.
        if (skyColor != null || groundColor != null) {
            val sky    = (skyColor ?: bgColor).toInt()
            val ground = (groundColor ?: bgColor).toInt()
            val hz     = canvasH * (horizonY ?: appearance.groundLineYFraction)
            backgroundPaint.shader = null
            backgroundPaint.color = sky
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, hz, backgroundPaint)
            backgroundPaint.color = ground
            canvas.drawRect(-canvasW.toFloat(), hz, canvasW * 2f, canvasH * 2f, backgroundPaint)
        } else if (appearance.backgroundStyle == "gradient") {
            // Bounded to the VISIBLE frame (0..canvasH), not the oversized
            // safety rect below — CLAMP already extends the start/end colours
            // flat across the oversized margin on its own. Spanning the
            // gradient across the oversized rect instead would mean the
            // visible viewport only ever shows the middle third of the
            // configured colour transition, which was a real bug caught on
            // review (reasoned through the maths, not seen rendered).
            backgroundPaint.shader = LinearGradient(
                0f, 0f, 0f, canvasH.toFloat(),
                bgColor.toInt(), appearance.backgroundGradientColor.toInt(),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, canvasH * 2f, backgroundPaint)
        } else {
            backgroundPaint.shader = null
            backgroundPaint.color = bgColor.toInt()
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, canvasH * 2f, backgroundPaint)
        }

        if (sceneShape != SceneShape.NONE) {
            drawSceneShape(canvas, canvasW, canvasH, horizonY ?: appearance.groundLineYFraction, sceneShape, appearance)
        }

        if (appearance.showGroundLine) {
            groundPaint.color = appearance.groundLineColor.toInt()
            groundPaint.strokeWidth = 2f
            val groundY = canvasH * appearance.groundLineYFraction
            canvas.drawLine(-canvasW.toFloat(), groundY, canvasW * 2f, groundY, groundPaint)
        }

        if (appearance.showGrid && !forExport) drawGrid(canvas, canvasW, canvasH, appearance)

        val overlayVisible = referenceOverlay != null && referenceOverlay.isVisibleAt(currentTimeSec)
        if (overlayVisible && referenceOverlay != null && !referenceOverlay.inFrontOfFigure) {
            drawReferenceOverlay(canvas, canvasW, canvasH, referenceOverlay, referenceOverlayBitmap)
        }

        bonePaint.color          = appearance.boneColor.toInt()
        bonePaint.strokeWidth    = appearance.boneStrokeNormalized * minDim
        headPaint.color          = appearance.headColor.toInt()
        jointPaint.color         = appearance.jointColor.toInt()
        mouthPaint.color         = appearance.mouthColor.toInt()
        eyePaint.color           = appearance.eyeColor.toInt()
        eyebrowPaint.color       = appearance.eyebrowColor.toInt()
        eyebrowPaint.strokeWidth = appearance.boneStrokeNormalized * minDim * 0.7f
        val jointR               = appearance.jointRadiusNormalized * minDim
        val showJoints           = if (forExport) appearance.showJointsOnExport else appearance.showJoints

        // ── FK pass ───────────────────────────────────────────────────────────
        for (i in 0 until n) {
            val bone   = bones[i]
            val matrix = matrices[i]
            if (bone.parentId == null) {
                matrix.reset()
                matrix.postTranslate(rootX, rootY)
                matrix.preRotate(angles[i])
            } else {
                val pIdx = rig.BONE_INDEX[bone.parentId] ?: continue
                matrix.set(matrices[pIdx])
                matrix.preTranslate(bones[pIdx].normalizedLength * scale, 0f)
                matrix.preRotate(angles[i])
            }
        }

        // ── Draw pass ─────────────────────────────────────────────────────────
        for (i in 0 until n) {
            val bone   = bones[i]
            val length = bone.normalizedLength * scale

            pts[0] = 0f;    pts[1] = 0f       // bone origin (local)
            pts[2] = length; pts[3] = 0f       // bone tip   (local)
            matrices[i].mapPoints(pts)

            val startX = pts[0]; val startY = pts[1]   // neck / joint end
            val endX   = pts[2]; val endY   = pts[3]   // head position

            if (bone.isHeadBone) {
                // Draw head circle at the bone's TIP (endX, endY).
                // The bone's own angle now visibly tilts the head in an arc
                // around the neck — head nods and pose offsets are finally
                // visible. Previously it was drawn at startX/startY (the
                // origin), where rotation has no effect on position.
                val r = bone.headNormalizedRadius * scale * appearance.headScaleMultiplier
                canvas.drawCircle(endX, endY, r, headPaint)
                if (appearance.showMouth) {
                    drawMouth(canvas, endX, endY, startX, startY, r, mouthShape, mouthOpenness, expression)
                }
                if (appearance.showEyes) {
                    drawEyes(canvas, endX, endY, startX, startY, r, eyeOpenness, expression)
                }
            } else {
                canvas.drawLine(startX, startY, endX, endY, bonePaint)
            }

            if (showJoints && !bone.isHeadBone) {
                canvas.drawCircle(startX, startY, jointR, jointPaint)
            }
        }

        if (overlayVisible && referenceOverlay != null && referenceOverlay.inFrontOfFigure) {
            drawReferenceOverlay(canvas, canvasW, canvasH, referenceOverlay, referenceOverlayBitmap)
        }

        canvas.restore()

        // ── Screen-space layers (unaffected by camera zoom/pan/shake) ─────────
        // Atmosphere is a whole-viewport weather/mood filter — panning or
        // zooming the camera shouldn't make rain streaks slide relative to the
        // frame, so it's drawn after restore(), same reasoning as the caption
        // being a fixed subtitle rather than something that scrolls with the
        // scene.
        if (sceneAtmosphere != SceneAtmosphere.NONE) {
            drawAtmosphere(canvas, canvasW, canvasH, sceneAtmosphere, currentTimeSec)
        }
        if (!captionText.isNullOrBlank()) {
            drawCaption(canvas, canvasW, canvasH, captionText)
        }
    }

    /**
     * Cheap procedural background silhouette drawn behind the figure, in the
     * band just above [horizonYFraction]. Deliberately simple shapes (no
     * imported art) so they scale to any canvas size for free. Colour is
     * derived from the figure's own bone colour via [constrainSceneColor] so
     * the AI never has to reason about a colour clash — see
     * [com.example.data.AppearanceSettings] doc + V2_DECISIONS.md.
     */
    private fun drawSceneShape(canvas: Canvas, w: Int, h: Int, horizonYFraction: Float, shape: String, appearance: AppearanceSettings) {
        val horizonPx = h * horizonYFraction
        sceneShapePaint.color = constrainSceneColor(appearance.boneColor, appearance.boneColor, alpha = 0x66)
        when (shape) {
            SceneShape.MOUNTAINS -> {
                val peakCount = 4
                val peakW = w.toFloat() / (peakCount - 1)
                val path = Path()
                path.moveTo(-w * 0.2f, horizonPx)
                for (i in 0 until peakCount) {
                    val x = -w * 0.2f + i * peakW * 1.4f
                    val peakHeight = horizonPx * (0.35f + 0.15f * ((i * 37) % 3))
                    path.lineTo(x + peakW * 0.7f, horizonPx - peakHeight)
                    path.lineTo(x + peakW * 1.4f, horizonPx)
                }
                path.lineTo(w * 1.2f, horizonPx)
                path.lineTo(w * 1.2f, horizonPx + h * 0.01f)
                path.lineTo(-w * 0.2f, horizonPx + h * 0.01f)
                path.close()
                canvas.drawPath(path, sceneShapePaint)
            }
            SceneShape.CITY -> {
                val buildingCount = 8
                val bw = w.toFloat() / buildingCount
                for (i in 0 until buildingCount) {
                    val bh = horizonPx * (0.25f + 0.35f * ((i * 53) % 5) / 5f)
                    val x = i * bw
                    canvas.drawRect(x, horizonPx - bh, x + bw * 0.8f, horizonPx, sceneShapePaint)
                }
            }
            SceneShape.TREES -> {
                val treeCount = 6
                val spacing = w.toFloat() / treeCount
                for (i in 0 until treeCount) {
                    val cx = spacing * i + spacing * 0.5f
                    val r = horizonPx * (0.10f + 0.04f * ((i * 29) % 3))
                    canvas.drawCircle(cx, horizonPx - r, r, sceneShapePaint)
                    canvas.drawRect(cx - r * 0.08f, horizonPx - r, cx + r * 0.08f, horizonPx, sceneShapePaint)
                }
            }
            SceneShape.CLOUDS -> {
                val cloudCount = 4
                val spacing = w.toFloat() / cloudCount
                for (i in 0 until cloudCount) {
                    val cx = spacing * i + spacing * 0.5f
                    val cy = horizonPx * (0.15f + 0.10f * ((i * 41) % 3))
                    val r = w * 0.05f
                    canvas.drawCircle(cx - r, cy, r, sceneShapePaint)
                    canvas.drawCircle(cx + r * 0.6f, cy - r * 0.3f, r * 0.8f, sceneShapePaint)
                    canvas.drawCircle(cx + r * 1.4f, cy, r * 0.7f, sceneShapePaint)
                }
            }
        }
    }

    /**
     * Enforces a minimum 50° hue separation from [figureColor] and caps
     * saturation at 0.45 — a scripted scene colour should never visually
     * compete with or blend into the figure. Not reliant on AI prompt
     * guidance; enforced here unconditionally. See V2_DECISIONS.md.
     */
    private fun constrainSceneColor(base: Long, figureColor: Long, alpha: Int = 0xFF): Int {
        val baseHsv = FloatArray(3)
        Color.colorToHSV((base or 0xFF000000L).toInt(), baseHsv)
        val figHsv = FloatArray(3)
        Color.colorToHSV((figureColor or 0xFF000000L).toInt(), figHsv)
        var hueDiff = kotlin.math.abs(baseHsv[0] - figHsv[0])
        if (hueDiff > 180f) hueDiff = 360f - hueDiff
        if (hueDiff < 50f) baseHsv[0] = (figHsv[0] + 50f) % 360f
        baseHsv[1] = baseHsv[1].coerceAtMost(0.45f)
        return Color.HSVToColor(alpha, baseHsv)
    }

    /**
     * Cheap procedural weather/atmosphere overlay, drawn in screen space
     * (after the camera transform is restored) so it reads as a filter over
     * the whole frame rather than an object in the scene. Positions are a
     * deterministic function of [timeSec] (not a live random source) so
     * preview and export produce identical frames for the same timestamp —
     * same determinism reasoning as [PlaybackEngine]'s blink/fidget schedules.
     */
    private fun drawAtmosphere(canvas: Canvas, w: Int, h: Int, atmosphere: String, timeSec: Float) {
        when (atmosphere) {
            SceneAtmosphere.FOG -> {
                atmospherePaint.color = 0x33FFFFFFL.toInt()
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), atmospherePaint)
            }
            SceneAtmosphere.RAIN -> {
                atmospherePaint.color = 0x66AACCFFL.toInt()
                atmospherePaint.strokeWidth = 2f
                val cols = 24
                val speed = 900f // px/sec, purely deterministic from timeSec
                for (i in 0 until cols) {
                    val baseX = (i.toFloat() / cols) * w
                    val x = (baseX + (i * 17) % 40) 
                    val y = ((timeSec * speed) + i * 53f).let { it % (h + 40f) } - 20f
                    canvas.drawLine(x, y, x - 8f, y + 20f, atmospherePaint)
                }
            }
            SceneAtmosphere.SNOW -> {
                atmospherePaint.color = 0xCCFFFFFFL.toInt()
                val flakes = 30
                val speed = 120f
                for (i in 0 until flakes) {
                    val baseX = (i.toFloat() / flakes) * w
                    val drift = kotlin.math.sin(timeSec * 0.6f + i) * 15f
                    val x = baseX + drift
                    val y = ((timeSec * speed) + i * 71f).let { it % (h + 20f) } - 10f
                    canvas.drawCircle(x, y, 3f, atmospherePaint)
                }
            }
            SceneAtmosphere.STARS -> {
                atmospherePaint.color = 0xDDFFFFFFL.toInt()
                val stars = 40
                for (i in 0 until stars) {
                    // Fixed pseudo-random grid — stars don't move, just a light static-time twinkle.
                    val x = ((i * 6151) % w.coerceAtLeast(1)).toFloat()
                    val y = ((i * 3079) % (h / 2).coerceAtLeast(1)).toFloat()
                    val twinkle = 0.5f + 0.5f * kotlin.math.sin(timeSec * 2f + i)
                    atmospherePaint.alpha = (140 + 100 * twinkle).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, 2f, atmospherePaint)
                }
                atmospherePaint.alpha = 255
            }
        }
    }

    /**
     * Bottom-anchored subtitle-style caption with a semi-opaque backdrop for
     * legibility over any scene. Screen-space (drawn after camera restore) —
     * captions should read like burned-in subtitles, not an object the camera
     * can pan away from.
     */
    private fun drawCaption(canvas: Canvas, w: Int, h: Int, text: String) {
        captionTextPaint.textSize = h * 0.045f
        val maxWidth = (w * 0.88f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, captionTextPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .build()

        val padding = h * 0.02f
        val bottomMargin = h * 0.06f
        val boxLeft = (w - maxWidth) / 2f - padding
        val boxRight = boxLeft + maxWidth + padding * 2f
        val boxBottom = h - bottomMargin
        val boxTop = boxBottom - layout.height - padding * 2f

        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, padding, padding, captionBgPaint)
        canvas.save()
        canvas.translate((w - maxWidth) / 2f, boxTop + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Manual reference overlay (image or text), positioned by fraction of
     * canvas per [ReferenceOverlay.posX]/[posY]/[sizeFraction]. Drawn either
     * before or after the figure's FK pass depending on [ReferenceOverlay.inFrontOfFigure]
     * — see the two call sites in [draw].
     */
    private fun drawReferenceOverlay(canvas: Canvas, w: Int, h: Int, overlay: ReferenceOverlay, bitmap: Bitmap?) {
        val minDim = min(w, h).toFloat()
        val sizePx = minDim * overlay.sizeFraction
        val cx = w * overlay.posX
        val cy = h * overlay.posY

        when (overlay.type) {
            ReferenceOverlay.OverlayType.IMAGE -> {
                if (bitmap == null) return
                val cropL = (overlay.cropLeft.coerceIn(0f, 1f) * bitmap.width).toInt()
                val cropT = (overlay.cropTop.coerceIn(0f, 1f) * bitmap.height).toInt()
                val cropR = (overlay.cropRight.coerceIn(0f, 1f) * bitmap.width).toInt().coerceAtLeast(cropL + 1)
                val cropB = (overlay.cropBottom.coerceIn(0f, 1f) * bitmap.height).toInt().coerceAtLeast(cropT + 1)
                overlayImageSrcRect.set(cropL, cropT, cropR, cropB)
                val aspect = (cropR - cropL).toFloat() / (cropB - cropT).toFloat()
                val dw = if (aspect >= 1f) sizePx else sizePx * aspect
                val dh = if (aspect >= 1f) sizePx / aspect else sizePx
                overlayImageDstRect.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
                canvas.drawBitmap(bitmap, overlayImageSrcRect, overlayImageDstRect, null)
            }
            ReferenceOverlay.OverlayType.TEXT -> {
                val text = overlay.text ?: return
                overlayTextPaint.color = overlay.textColor.toInt()
                overlayTextPaint.textSize = sizePx * 0.3f
                val maxWidth = (minDim * 0.6f).toInt().coerceAtLeast(1)
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, overlayTextPaint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
                if (overlay.showBackdrop) {
                    val pad = sizePx * 0.08f
                    canvas.drawRoundRect(
                        cx - maxWidth / 2f - pad, cy - layout.height / 2f - pad,
                        cx + maxWidth / 2f + pad, cy + layout.height / 2f + pad,
                        pad, pad, overlayBgPaint
                    )
                }
                canvas.save()
                canvas.translate(cx - maxWidth / 2f, cy - layout.height / 2f)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    /**
     * Audio-reactive mouth drawn between the head centre and the neck point,
     * so it rotates naturally with head tilt. [mouthShape] sets the base
     * proportions; [mouthOpenness] (0..1 smoothed amplitude) scales height.
     */
    /**
     * [expression] biases the RESTING mouth shape/position underneath
     * whatever the audio-driven [mouthShape]/[mouthOpenness] is already
     * doing — it does not replace the lip-sync logic. Implemented as simple
     * width/position offsets on the existing oval rather than a curved
     * smile/frown path: the oval is already proven correct for lip-sync, and
     * a Bezier "smile arc" combined correctly with an open, talking mouth is
     * a meaningfully bigger geometry change than this — not something worth
     * risking without being able to see it rendered.
     */
    private fun drawMouth(
        canvas: Canvas,
        hx: Float, hy: Float,   // head centre (tip)
        nx: Float, ny: Float,   // neck point (origin)
        r: Float,
        mouthShape: Int, mouthOpenness: Float,
        expression: Int
    ) {
        val cx = hx + (nx - hx) * 0.42f
        var cy = hy + (ny - hy) * 0.42f

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
        canvas.drawOval(cx - hw, cy - hh, cx + hw, cy + hh, mouthPaint)
    }

    /**
     * Eyes, positioned/rotated using the same head-tip/neck-axis technique as
     * [drawMouth] — they follow head tilt for free with no extra bookkeeping.
     *
     * [openness] is resolved analytically upstream in [PlaybackEngine] from
     * BOTH natural idle blinking and any AI-scripted
     * [com.example.data.AnimScript.blinkEvents]; this function doesn't know or
     * care which triggered it, it just draws the current openness value.
     *
     * Eyebrows are NOT rig bones (the rig has none) — they're synthetic lines
     * drawn only for [Expression.WORRIED]/[Expression.ANGRY], per
     * [Expression]'s own doc comment. Their tilt direction was derived
     * analytically from the perpendicular/"up" basis below, the same way the
     * three newest poses (present/point_self/open_hands) were computed —
     * this has NOT been visually confirmed on-device and may need a sign flip.
     */
    private fun drawEyes(
        canvas: Canvas,
        hx: Float, hy: Float,
        nx: Float, ny: Float,
        r: Float,
        openness: Float,
        expression: Int
    ) {
        // Toward-neck interpolation, same technique as drawMouth but a smaller
        // fraction — eyes sit higher on the face than the mouth's 0.42f.
        val cx = hx + (nx - hx) * 0.12f
        val cy = hy + (ny - hy) * 0.12f

        // Perpendicular to the head-neck axis, normalised — gives left/right
        // eye separation that automatically follows head TILT, not just
        // position, exactly like the mouth already does.
        val rawPx = -(ny - hy)
        val rawPy = (nx - hx)
        val plen = kotlin.math.sqrt(rawPx * rawPx + rawPy * rawPy).let { if (it < 0.0001f) 1f else it }
        val perpX = rawPx / plen
        val perpY = rawPy / plen
        val upX = -perpY
        val upY = perpX

        val eyeSpacing = 0.34f * r
        val baseEyeRadius = when (expression) {
            Expression.WIDE, Expression.HAPPY -> 0.20f * r
            Expression.SQUINT                  -> 0.11f * r
            else                                -> 0.15f * r
        }
        // Blink flattens toward a thin closed line rather than vanishing to
        // zero height — same reasoning as MouthShape.CLOSED using a thin oval.
        val openFactor = openness.coerceIn(0f, 1f)
        val eyeRadiusY = baseEyeRadius * (0.12f + 0.88f * openFactor)

        val leftX  = cx - perpX * eyeSpacing
        val leftY  = cy - perpY * eyeSpacing
        val rightX = cx + perpX * eyeSpacing
        val rightY = cy + perpY * eyeSpacing

        canvas.drawOval(leftX - baseEyeRadius, leftY - eyeRadiusY, leftX + baseEyeRadius, leftY + eyeRadiusY, eyePaint)
        canvas.drawOval(rightX - baseEyeRadius, rightY - eyeRadiusY, rightX + baseEyeRadius, rightY + eyeRadiusY, eyePaint)

        if (expression == Expression.WORRIED || expression == Expression.ANGRY) {
            drawEyebrows(canvas, leftX, leftY, rightX, rightY, perpX, perpY, upX, upY, baseEyeRadius, expression)
        }
    }

    private fun drawEyebrows(
        canvas: Canvas,
        leftX: Float, leftY: Float, rightX: Float, rightY: Float,
        perpX: Float, perpY: Float,
        upX: Float, upY: Float,
        eyeRadius: Float,
        expression: Int
    ) {
        val browHeight = eyeRadius * 1.8f
        val browHalfLen = eyeRadius * 1.0f
        // WORRIED: inner end (toward the other eye) lifts — concerned slant.
        // ANGRY: inner end drops — furrowed/stern slant.
        val innerLift = eyeRadius * (if (expression == Expression.WORRIED) 0.55f else -0.55f)

        canvas.drawLine(
            leftX - perpX * browHalfLen + upX * browHeight,
            leftY - perpY * browHalfLen + upY * browHeight,
            leftX + perpX * browHalfLen + upX * (browHeight + innerLift),
            leftY + perpY * browHalfLen + upY * (browHeight + innerLift),
            eyebrowPaint
        )
        canvas.drawLine(
            rightX + perpX * browHalfLen + upX * browHeight,
            rightY + perpY * browHalfLen + upY * browHeight,
            rightX - perpX * browHalfLen + upX * (browHeight + innerLift),
            rightY - perpY * browHalfLen + upY * (browHeight + innerLift),
            eyebrowPaint
        )
    }

    /**
     * Computes world-space [startX, startY, endX, endY] for every bone using
     * the same FK matrix chain as [draw]. Called from touch-event handling in
     * [AnimationSurfaceView] so drag pivots and nearest-bone detection use the
     * exact same geometry as what's visually rendered — previously the touch
     * handler re-derived positions with a single-hop formula that was wrong for
     * any bone that isn't a direct child of a root bone (lower arms, lower legs).
     *
     * Allocates one Array per call — fine for touch events, not for the render loop.
     */
    fun worldEndpoints(
        angles: FloatArray, scale: Float, rootX: Float, rootY: Float
    ): Array<FloatArray> {
        val mats   = Array(n) { Matrix() }
        val pts    = FloatArray(4)
        val result = Array(n) { FloatArray(4) }
        for (i in 0 until n) {
            val bone = bones[i]
            val mat  = mats[i]
            if (bone.parentId == null) {
                mat.reset()
                mat.postTranslate(rootX, rootY)
                mat.preRotate(angles[i])
            } else {
                val pIdx = rig.BONE_INDEX[bone.parentId] ?: continue
                mat.set(mats[pIdx])
                mat.preTranslate(bones[pIdx].normalizedLength * scale, 0f)
                mat.preRotate(angles[i])
            }
            pts[0] = 0f; pts[1] = 0f
            pts[2] = bone.normalizedLength * scale; pts[3] = 0f
            mat.mapPoints(pts)
            result[i][0] = pts[0]; result[i][1] = pts[1]
            result[i][2] = pts[2]; result[i][3] = pts[3]
        }
        return result
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int, a: AppearanceSettings) {
        gridPaint.color       = a.gridColor.toInt()
        gridPaint.strokeWidth = 1f
        val step = 80f
        var x = 0f; while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint); x += step }
        var y = 0f; while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, gridPaint); y += step }
    }
}
