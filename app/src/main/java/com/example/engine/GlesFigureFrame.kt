package com.example.engine

/**
 * Resolved per-frame figure geometry and style, ready to hand to
 * [GlesFrameRenderer.drawFigureFrame].
 *
 * All positional values are in PIXEL COORDINATES in the target canvas's
 * space — the FK pass already multiplied out the normalised lengths by
 * `scale` and the canvas dimensions, so the GLES renderer doesn't need to
 * redo any of that maths.
 *
 * DRAW ORDER MATTERS: [drawCommands] is built in exactly the same
 * per-bone-index iteration order as the draw pass in [RigRenderer.draw] —
 * NOT bones-then-joints-then-head grouped by type. [StickFigureRig.BONES]
 * is ordered `[torso, head, upper_arm_r, ...]`, so the Canvas path draws
 * the head SECOND, before any limb — meaning a limb can visually occlude
 * the head/face in poses where an arm crosses in front of it (e.g.
 * "think", "explain"). A first draft of this class grouped all bone
 * lines, then all joints, then the head last, which silently inverted
 * that z-order — caught on review before it shipped, not on a device.
 * [DrawCommand] preserves the interleaving so [GlesFrameRenderer] can
 * just replay it faithfully rather than re-deriving the ordering.
 *
 * Phase 2 scope was bones + head + joints only. Phase 3 (V2_DECISIONS.md)
 * added mouth/eyes/eyebrows, interleaved right after [DrawCommand.Head] in
 * this same list, before the next bone, preserving this same z-order
 * guarantee. Overlays, captions, scene shapes, and atmosphere remain later
 * phases — see V2_DECISIONS.md.
 */
data class GlesFigureFrame(
    /** Canvas pixel width this frame was computed for. */
    val canvasW: Int,
    /** Canvas pixel height this frame was computed for. */
    val canvasH: Int,
    /** ARGB packed int (same representation as Android Color). */
    val bgColor: Int,

    /** Ordered exactly like the Canvas path's per-bone-index loop — see class doc comment. */
    val drawCommands: List<DrawCommand>,

    /** 0f = fully transparent, 1f = fully opaque. Applied to the whole figure (all commands), not per-command. */
    val figureAlpha: Float
) {
    sealed class DrawCommand {
        data class BoneLine(
            val sx: Float, val sy: Float, val ex: Float, val ey: Float,
            val halfWidth: Float, val color: Int
        ) : DrawCommand()

        data class Joint(val cx: Float, val cy: Float, val radius: Float, val color: Int) : DrawCommand()

        data class Head(val cx: Float, val cy: Float, val radius: Float, val color: Int) : DrawCommand()

        /**
         * Shared by the mouth and each eye — all three are the same shape
         * (an axis-aligned oval), just different sizes/positions/colors, so
         * one command type covers all of them rather than three near-
         * identical ones. See [RigRenderer.computeMouthGeometry] /
         * [RigRenderer.computeEyeGeometry] for how cx/cy/halfWidth/
         * halfHeight are derived — this class only replays already-resolved
         * geometry, same as every other [DrawCommand].
         */
        data class Oval(
            val cx: Float, val cy: Float,
            val halfWidth: Float, val halfHeight: Float,
            val color: Int
        ) : DrawCommand()

        /**
         * Geometrically identical to [BoneLine] (a round-capped line) but
         * kept as its own type rather than reusing BoneLine — an eyebrow
         * isn't a bone, and this class already draws that same distinction
         * between [Joint] and [Head] despite both being circles. Only
         * emitted for [Expression.WORRIED]/[Expression.ANGRY] — see
         * [RigRenderer.computeEyebrowGeometry].
         */
        data class Eyebrow(
            val sx: Float, val sy: Float, val ex: Float, val ey: Float,
            val halfWidth: Float, val color: Int
        ) : DrawCommand()
    }

    companion object {
        /**
         * Builds a [GlesFigureFrame] from the already-computed [matrices] array
         * (one [android.graphics.Matrix] per bone, in [StickFigureRig.BONES]
         * order), mirroring [RigRenderer.draw]'s draw-pass geometry AND its
         * z-order exactly — one [DrawCommand] emitted per loop iteration, in
         * the same order that loop runs in.
         *
         * Called after the FK pass — the matrices array must already be fully
         * populated before this is invoked.
         */
        fun fromFkMatrices(
            matrices: Array<android.graphics.Matrix>,
            appearance: com.example.data.AppearanceSettings,
            overrides: FigureOverrides,
            canvasW: Int,
            canvasH: Int,
            scale: Float,
            showJoints: Boolean,
            mouthShape: Int,
            mouthOpenness: Float,
            eyeOpenness: Float,
            expression: Int
        ): GlesFigureFrame {
            val rig    = StickFigureRig
            val bones  = rig.BONES
            val n      = rig.BONE_COUNT
            val minDim = minOf(canvasW, canvasH).toFloat()
            val pts    = FloatArray(4)

            val boneColor    = (overrides.boneColor ?: appearance.boneColor).toInt()
            val headColor    = (overrides.headColor ?: overrides.boneColor ?: appearance.headColor).toInt()
            val jointColor   = (overrides.jointColor ?: overrides.boneColor ?: appearance.jointColor).toInt()
            // No boneColor fallback for these three — matches RigRenderer's
            // own mouthPaint/eyePaint/eyebrowPaint.color assignments exactly.
            val mouthColor   = (overrides.mouthColor ?: appearance.mouthColor).toInt()
            val eyeColor     = (overrides.eyeColor ?: appearance.eyeColor).toInt()
            val eyebrowColor = (overrides.eyebrowColor ?: appearance.eyebrowColor).toInt()
            val boneHalfWidth = appearance.boneStrokeNormalized * minDim * 0.5f
            val jointRadius   = appearance.jointRadiusNormalized * minDim
            val headScaleMultiplier = overrides.headScale ?: appearance.headScaleMultiplier

            val commands = ArrayList<DrawCommand>(n * 2)

            for (i in 0 until n) {
                val bone = bones[i]
                val lengthMultiplier = if (bone.id == "head") appearance.neckLengthMultiplier else 1f
                val length = bone.normalizedLength * scale * lengthMultiplier

                pts[0] = 0f; pts[1] = 0f
                pts[2] = length; pts[3] = 0f
                matrices[i].mapPoints(pts)

                val startX = pts[0]; val startY = pts[1]
                val endX   = pts[2]; val endY   = pts[3]

                if (bone.isHeadBone) {
                    val r = bone.headNormalizedRadius * scale * headScaleMultiplier
                    commands += DrawCommand.Head(endX, endY, r, headColor)

                    // Mouth/eyes/eyebrows, in that order — mirrors RigRenderer.draw's
                    // showMouth-then-showEyes ordering exactly (see that function's
                    // call site), inserted here so they land immediately after Head
                    // and before the next bone, preserving the same z-order guarantee
                    // this class's own doc comment calls out.
                    if (appearance.showMouth) {
                        val g = RigRenderer.computeMouthGeometry(
                            endX, endY, startX, startY, r,
                            mouthShape, mouthOpenness, expression, headScaleMultiplier
                        )
                        commands += DrawCommand.Oval(g.cx, g.cy, g.halfWidth, g.halfHeight, mouthColor)
                    }
                    if (appearance.showEyes) {
                        val eyes = RigRenderer.computeEyeGeometry(
                            endX, endY, startX, startY, r,
                            eyeOpenness, expression, headScaleMultiplier, appearance
                        )
                        commands += DrawCommand.Oval(eyes.left.cx, eyes.left.cy, eyes.left.halfWidth, eyes.left.halfHeight, eyeColor)
                        commands += DrawCommand.Oval(eyes.right.cx, eyes.right.cy, eyes.right.halfWidth, eyes.right.halfHeight, eyeColor)

                        val brows = RigRenderer.computeEyebrowGeometry(eyes, expression)
                        if (brows != null) {
                            commands += DrawCommand.Eyebrow(brows.left.sx, brows.left.sy, brows.left.ex, brows.left.ey, brows.halfWidth, eyebrowColor)
                            commands += DrawCommand.Eyebrow(brows.right.sx, brows.right.sy, brows.right.ex, brows.right.ey, brows.halfWidth, eyebrowColor)
                        }
                    }
                } else {
                    commands += DrawCommand.BoneLine(startX, startY, endX, endY, boneHalfWidth, boneColor)
                }

                if (showJoints && !bone.isHeadBone) {
                    commands += DrawCommand.Joint(startX, startY, jointRadius, jointColor)
                }
            }

            val bgColor = (overrides.bgColor ?: appearance.exportBgColor).toInt()

            return GlesFigureFrame(
                canvasW      = canvasW,
                canvasH      = canvasH,
                bgColor      = bgColor,
                drawCommands = commands,
                figureAlpha  = (overrides.opacity ?: 1f).coerceIn(0f, 1f)
            )
        }
    }
}
