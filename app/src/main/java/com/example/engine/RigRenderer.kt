package com.example.engine

import android.graphics.*
import com.example.data.AppearanceSettings
import kotlin.math.min

/**
 * Stateless renderer. Accepts pre-computed [angles] from [PlaybackEngine] and
 * draws the stick figure onto any [Canvas].
 *
 * Forward-kinematic pass uses Android's [Matrix] chain so the math is handled
 * by the platform's optimised native matrix code.
 *
 * Pre-allocates all working objects at construction to keep [draw] allocation-free.
 */
object RigRenderer {

    private val rig    = StickFigureRig
    private val bones  = rig.BONES
    private val n      = rig.BONE_COUNT

    // Pre-allocated FK matrices, one per bone
    private val matrices = Array(n) { Matrix() }

    // Pre-allocated point array for mapping bone endpoints
    private val pts = FloatArray(4)   // [startX, startY, endX, endY]

    // Paints — re-configured per draw call from AppearanceSettings
    private val bonePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val headPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Main draw call.
     *
     * @param canvas        Target canvas (SurfaceView or off-screen Bitmap for export).
     * @param angles        [PlaybackEngine.currentAngles] — total angles per bone.
     * @param appearance    Visual configuration.
     * @param canvasW       Canvas pixel width.
     * @param canvasH       Canvas pixel height.
     * @param forExport     When true, respects [AppearanceSettings.showJointsOnExport].
     */
    fun draw(
        canvas: Canvas,
        angles: FloatArray,
        appearance: AppearanceSettings,
        canvasW: Int,
        canvasH: Int,
        forExport: Boolean = false
    ) {
        val minDim  = min(canvasW, canvasH).toFloat()
        val scale   = minDim * appearance.characterScale
        val rootX   = canvasW * appearance.rootAnchorX
        val rootY   = canvasH * appearance.rootAnchorY

        // ── Optional grid ──────────────────────────────────────────────────────
        if (appearance.showGrid && !forExport) {
            drawGrid(canvas, canvasW, canvasH, appearance)
        }

        // ── Configure paints ───────────────────────────────────────────────────
        bonePaint.color      = appearance.boneColor.toInt()
        bonePaint.strokeWidth = appearance.boneStrokeNormalized * minDim
        headPaint.color      = appearance.headColor.toInt()
        jointPaint.color     = appearance.jointColor.toInt()
        val jointR           = appearance.jointRadiusNormalized * minDim
        val showJoints       = if (forExport) appearance.showJointsOnExport else appearance.showJoints

        // ── FK pass: build matrix chain ────────────────────────────────────────
        for (i in 0 until n) {
            val bone   = bones[i]
            val angle  = angles[i]
            val matrix = matrices[i]

            if (bone.parentId == null) {
                // Root bone: place at rig anchor
                matrix.reset()
                matrix.postTranslate(rootX, rootY)
                matrix.preRotate(angle)
            } else {
                // Child bone: attach at parent's tip
                val pIdx = rig.BONE_INDEX[bone.parentId] ?: continue
                val parentBone   = bones[pIdx]
                val parentLength = parentBone.normalizedLength * scale
                matrix.set(matrices[pIdx])
                matrix.preTranslate(parentLength, 0f)
                matrix.preRotate(angle)
            }
        }

        // ── Draw pass ─────────────────────────────────────────────────────────
        for (i in 0 until n) {
            val bone   = bones[i]
            val length = bone.normalizedLength * scale

            // Map bone origin and tip through its FK matrix
            pts[0] = 0f; pts[1] = 0f         // bone origin in local space
            pts[2] = length; pts[3] = 0f      // bone tip in local space
            matrices[i].mapPoints(pts)

            val startX = pts[0]; val startY = pts[1]
            val endX   = pts[2]; val endY   = pts[3]

            if (bone.isHeadBone) {
                // Draw head circle at the bone's START (which is the neck tip)
                val r = bone.headNormalizedRadius * scale
                canvas.drawCircle(startX, startY, r, headPaint)
            } else {
                canvas.drawLine(startX, startY, endX, endY, bonePaint)
            }

            if (showJoints && !bone.isHeadBone) {
                canvas.drawCircle(startX, startY, jointR, jointPaint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int, appearance: AppearanceSettings) {
        gridPaint.color       = appearance.gridColor.toInt()
        gridPaint.strokeWidth = 1f
        val step = 80f
        var x = 0f; while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint); x += step }
        var y = 0f; while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, gridPaint); y += step }
    }
}
