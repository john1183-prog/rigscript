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
 * guarantee. Phase 4 added background/scene shapes/atmosphere — see
 * [SceneDrawCommand]/[AtmosphereDrawCommand] doc comments for why those are
 * separate lists rather than folded into [drawCommands]. Overlays and
 * captions remain later phases — see V2_DECISIONS.md.
 */
data class GlesFigureFrame(
    /** Canvas pixel width this frame was computed for. */
    val canvasW: Int,
    /** Canvas pixel height this frame was computed for. */
    val canvasH: Int,
    /** ARGB packed int (same representation as Android Color). Plain solid background — used when neither sky/ground nor gradient apply. */
    val bgColor: Int,
    /** Non-null together with [groundColor] when scripted sky/ground bands are active — see [RigRenderer.draw]'s own `skyColor != null || groundColor != null` check, mirrored exactly. */
    val skyColor: Int?,
    val groundColor: Int?,
    /** "gradient" or anything else — same string appearance/overrides field [RigRenderer.draw] itself checks, only consulted when sky/ground are both null. */
    val backgroundStyle: String,
    /** Gradient end color (top = [bgColor], bottom = this) — only used when [backgroundStyle] == "gradient" and sky/ground are null. */
    val backgroundGradientColor: Int,
    /** Fraction of canvasH where sky meets ground / where scene-shape elements sit on — resolved the same `horizonY ?: groundLineYFraction` way [RigRenderer.draw] resolves it. NOT the same value the ground line itself uses — see [groundLineYFraction]. */
    val horizonYFraction: Float,
    /** World-space background elements, in draw order (scene shape shapes, then stars if active) — see [SceneDrawCommand] doc comment. */
    val sceneCommands: List<SceneDrawCommand>,
    val showGroundLine: Boolean,
    val groundLineColor: Int,
    /**
     * Fraction of canvasH for the ground LINE specifically — plain
     * `groundLineYFraction`, deliberately NOT falling back through
     * [horizonYFraction]'s `horizonY ?:` resolution. [RigRenderer.draw]
     * keeps these genuinely independent: the ground line's own y-position
     * (`canvasH * groundLineYFraction`, see that call site) never consults
     * a scripted `horizonY` override the way the sky/ground bands and scene
     * shapes do — so a script setting `horizonY` without also moving
     * `groundLineYFraction` is a deliberately-supported case (a background
     * horizon that doesn't match a separately-positioned ground line), not
     * a bug to be normalised away here.
     */
    val groundLineYFraction: Float,

    /** Ordered exactly like the Canvas path's per-bone-index loop — see class doc comment. */
    val drawCommands: List<DrawCommand>,

    /** 0f = fully transparent, 1f = fully opaque. Applied to the whole figure (all commands), not per-command. */
    val figureAlpha: Float,

    /** Screen-space, drawn after the figure — see [AtmosphereDrawCommand] doc comment. */
    val atmosphereCommands: List<AtmosphereDrawCommand>
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

    /**
     * World-space background/scene elements (mountains/city/trees/clouds,
     * plus stars if [com.example.engine.SceneAtmosphere.STARS] is active) —
     * see [RigRenderer.computeMountainPolygon] etc. Kept as a SEPARATE list
     * from [DrawCommand] rather than merged into it: [DrawCommand]'s
     * ordering guarantee is specifically about bone/head/face interleaving
     * (see that class's doc comment), and scene elements have no such
     * per-bone relationship — they're drawn as one block, entirely before
     * the figure, matching [RigRenderer.draw]'s own separate code block for
     * this (background → scene shape → stars, all before the FK/bone loop).
     */
    sealed class SceneDrawCommand {
        /**
         * A filled arbitrary polygon, drawn as a GL_TRIANGLE_FAN from
         * [points]'s first vertex — see [RigRenderer.computeMountainPolygon]'s
         * doc comment for why that's a correct triangulation for THIS shape
         * specifically (star-shaped from its first point), not a general
         * polygon fill. [points] is a flat (x0,y0,x1,y1,...) array. Not a
         * data class — FloatArray breaks structural equals/hashCode, and
         * this is only ever constructed-then-consumed, never compared.
         */
        class Polygon(val points: FloatArray, val color: Int) : SceneDrawCommand()

        data class Rect(val l: Float, val t: Float, val r: Float, val b: Float, val color: Int) : SceneDrawCommand()

        data class Circle(val cx: Float, val cy: Float, val radius: Float, val color: Int) : SceneDrawCommand()
    }

    /**
     * Screen-space weather overlay (fog/rain/snow) — drawn AFTER the figure,
     * matching [RigRenderer.drawAtmosphere]'s own "after canvas.restore()"
     * placement. Kept separate from [SceneDrawCommand] because it's drawn at
     * a different point in the overall frame, not because the shapes differ.
     */
    sealed class AtmosphereDrawCommand {
        /** Fog only — always the full canvas, so no coordinates needed. */
        data class FullscreenTint(val color: Int) : AtmosphereDrawCommand()

        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val halfWidth: Float, val color: Int) : AtmosphereDrawCommand()

        data class Circle(val cx: Float, val cy: Float, val radius: Float, val color: Int) : AtmosphereDrawCommand()
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
            expression: Int,
            skyColor: Long?,
            groundColor: Long?,
            horizonY: Float?,
            sceneShape: String,
            sceneAtmosphere: String,
            timeSec: Float
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
            // These two are DELIBERATELY independent — see groundLineYFraction's
            // own doc comment on the data class for why conflating them would
            // be a real (if subtle) behavior change from the Canvas path.
            val plainGroundLineYFraction = overrides.groundLineYFraction ?: appearance.groundLineYFraction
            val horizonYFraction = horizonY ?: plainGroundLineYFraction

            // Scene shapes + stars — see SceneDrawCommand's doc comment for
            // why this is one flat list computed here rather than folded
            // into the bone loop above. Mirrors RigRenderer.draw's own
            // separate "background/scene" code block, including its exact
            // draw order: scene shape shapes, THEN stars.
            val sceneCommands = ArrayList<SceneDrawCommand>()
            run {
                val currentBoneColor = overrides.boneColor ?: appearance.boneColor
                val sceneColor = RigRenderer.constrainSceneColor(currentBoneColor, currentBoneColor, alpha = 0x66)
                when (sceneShape) {
                    SceneShape.MOUNTAINS -> {
                        val pts = RigRenderer.computeMountainPolygon(canvasW, canvasH, horizonYFraction, timeSec)
                        sceneCommands += SceneDrawCommand.Polygon(pts, sceneColor)
                    }
                    SceneShape.CITY -> {
                        for (b in RigRenderer.computeCityBuildings(canvasW, canvasH, horizonYFraction, timeSec)) {
                            sceneCommands += SceneDrawCommand.Rect(b.l, b.t, b.r, b.b, sceneColor)
                        }
                    }
                    SceneShape.TREES -> {
                        for (t in RigRenderer.computeTreePositions(canvasW, canvasH, horizonYFraction, timeSec)) {
                            sceneCommands += SceneDrawCommand.Circle(t.canopy.cx, t.canopy.cy, t.canopy.halfWidth, sceneColor)
                            sceneCommands += SceneDrawCommand.Rect(t.trunk.l, t.trunk.t, t.trunk.r, t.trunk.b, sceneColor)
                        }
                    }
                    SceneShape.CLOUDS -> {
                        for (cloud in RigRenderer.computeCloudPositions(canvasW, canvasH, horizonYFraction, timeSec)) {
                            for (puff in cloud) sceneCommands += SceneDrawCommand.Circle(puff.cx, puff.cy, puff.halfWidth, sceneColor)
                        }
                    }
                }
                if (sceneAtmosphere == SceneAtmosphere.STARS) {
                    for (s in RigRenderer.computeStarPositions(canvasW, canvasH, timeSec)) {
                        val starColor = (s.alpha.toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                        sceneCommands += SceneDrawCommand.Circle(s.cx, s.cy, s.r, starColor)
                    }
                }
            }

            // Atmosphere (fog/rain/snow) — screen-space, drawn AFTER the
            // figure by the renderer, see AtmosphereDrawCommand doc comment.
            val atmosphereCommands = ArrayList<AtmosphereDrawCommand>()
            when (sceneAtmosphere) {
                SceneAtmosphere.FOG -> {
                    atmosphereCommands += AtmosphereDrawCommand.FullscreenTint(0x33FFFFFFL.toInt())
                }
                SceneAtmosphere.RAIN -> {
                    for (d in RigRenderer.computeRainDrops(canvasW, canvasH, timeSec)) {
                        atmosphereCommands += AtmosphereDrawCommand.Line(d.x1, d.y1, d.x2, d.y2, 1f, 0x66AACCFFL.toInt())
                    }
                }
                SceneAtmosphere.SNOW -> {
                    for (f in RigRenderer.computeSnowFlakes(canvasW, canvasH, timeSec)) {
                        atmosphereCommands += AtmosphereDrawCommand.Circle(f.cx, f.cy, f.halfWidth, 0xCCFFFFFFL.toInt())
                    }
                }
            }

            return GlesFigureFrame(
                canvasW                 = canvasW,
                canvasH                 = canvasH,
                bgColor                 = bgColor,
                skyColor                = skyColor?.toInt(),
                groundColor             = groundColor?.toInt(),
                backgroundStyle         = overrides.backgroundStyle ?: appearance.backgroundStyle,
                backgroundGradientColor = (overrides.backgroundGradientColor ?: appearance.backgroundGradientColor).toInt(),
                horizonYFraction        = horizonYFraction,
                sceneCommands           = sceneCommands,
                showGroundLine          = overrides.showGroundLine ?: appearance.showGroundLine,
                groundLineColor         = (overrides.groundLineColor ?: appearance.groundLineColor).toInt(),
                groundLineYFraction     = plainGroundLineYFraction,
                drawCommands            = commands,
                figureAlpha             = (overrides.opacity ?: 1f).coerceIn(0f, 1f),
                atmosphereCommands      = atmosphereCommands
            )
        }
    }
}
