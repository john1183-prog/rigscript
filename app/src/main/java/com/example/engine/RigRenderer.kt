package com.example.engine

import android.graphics.*
import com.example.data.AppearanceSettings
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

    fun draw(
        canvas: Canvas,
        angles: FloatArray,
        appearance: AppearanceSettings,
        canvasW: Int,
        canvasH: Int,
        forExport: Boolean = false,
        mouthShape: Int = MouthShape.CLOSED,
        mouthOpenness: Float = 0f
    ) {
        val minDim  = min(canvasW, canvasH).toFloat()
        val scale   = minDim * appearance.characterScale
        val rootX   = canvasW * appearance.rootAnchorX
        val rootY   = canvasH * appearance.rootAnchorY

        if (appearance.showGrid && !forExport) drawGrid(canvas, canvasW, canvasH, appearance)

        bonePaint.color       = appearance.boneColor.toInt()
        bonePaint.strokeWidth = appearance.boneStrokeNormalized * minDim
        headPaint.color       = appearance.headColor.toInt()
        jointPaint.color      = appearance.jointColor.toInt()
        mouthPaint.color      = appearance.mouthColor.toInt()
        val jointR            = appearance.jointRadiusNormalized * minDim
        val showJoints        = if (forExport) appearance.showJointsOnExport else appearance.showJoints

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
                val r = bone.headNormalizedRadius * scale
                canvas.drawCircle(endX, endY, r, headPaint)
                if (appearance.showMouth) {
                    drawMouth(canvas, endX, endY, startX, startY, r, mouthShape, mouthOpenness)
                }
            } else {
                canvas.drawLine(startX, startY, endX, endY, bonePaint)
            }

            if (showJoints && !bone.isHeadBone) {
                canvas.drawCircle(startX, startY, jointR, jointPaint)
            }
        }
    }

    /**
     * Audio-reactive mouth drawn between the head centre and the neck point,
     * so it rotates naturally with head tilt. [mouthShape] sets the base
     * proportions; [mouthOpenness] (0..1 smoothed amplitude) scales height.
     */
    private fun drawMouth(
        canvas: Canvas,
        hx: Float, hy: Float,   // head centre (tip)
        nx: Float, ny: Float,   // neck point (origin)
        r: Float,
        mouthShape: Int, mouthOpenness: Float
    ) {
        val cx = hx + (nx - hx) * 0.42f
        val cy = hy + (ny - hy) * 0.42f

        val (wFrac, hFrac) = when (mouthShape) {
            MouthShape.WIDE   -> 0.44f to 0.28f
            MouthShape.NARROW -> 0.32f to 0.10f
            MouthShape.CLOSED -> 0.44f to 0.05f
            else              -> 0.42f to 0.14f   // MID
        }
        val hw = wFrac * r
        val hh = hFrac * r * (0.5f + 0.5f * mouthOpenness.coerceIn(0f, 1f))
        canvas.drawOval(cx - hw, cy - hh, cx + hw, cy + hh, mouthPaint)
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int, a: AppearanceSettings) {
        gridPaint.color       = a.gridColor.toInt()
        gridPaint.strokeWidth = 1f
        val step = 80f
        var x = 0f; while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint); x += step }
        var y = 0f; while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, gridPaint); y += step }
    }
}
