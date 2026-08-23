package com.example.engine

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ReferenceOverlay

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
 * separate lists rather than folded into [drawCommands]. OVERLAY SHAPES
 * (V2_DECISIONS.md) added `type == "shape"` overlay layers (rect/circle/
 * line/arrow/cross), parented-to-bone or not, with true two-pass Gaussian
 * blur glow — see [OverlayShapeDraw] doc comment. The CAMERA phase added
 * pan/zoom/shake, applied to everything above except atmosphere — see
 * [cameraZoom]'s doc comment. The TEXT phase added captions (see
 * [captionText]'s doc comment) and `type == "text"` overlay layers (see
 * [OverlayTextDraw]/[OverlayDraw] doc comments) — the latter in the SAME
 * ordered [behindOverlays]/[frontOverlays] lists as shape overlays, not
 * separate ones, so a script mixing shape and text overlays in one group
 * keeps their relative Z-order. Scoped deliberately narrow still: `type ==
 * "figure"` overlays, particle trails, and reference overlay (image or
 * text) all remain unimplemented in GLES — see V2_DECISIONS.md for the
 * exact boundary and why.
 */
data class GlesFigureFrame(
    /** Canvas pixel width this frame was computed for. */
    val canvasW: Int,
    /** Canvas pixel height this frame was computed for. */
    val canvasH: Int,
    /**
     * Camera zoom/pan/shake (V2_DECISIONS.md, camera phase), already
     * resolved into [RigRenderer.CameraTransform]'s zoom/offsetX/offsetY
     * form. Every coordinate in [drawCommands]/[sceneCommands]/
     * [behindOverlays]/[frontOverlays]/[groundLineYFraction] below is
     * ALREADY camera-transformed by [fromFkMatrices] — these three raw
     * values are NOT for re-transforming any of that. They exist only so
     * [GlesFrameRenderer] can build its own oversized background quad
     * (mirroring [RigRenderer.draw]'s `-canvasW..2*canvasW` safety margin,
     * which has no equivalent field here since it isn't geometry this class
     * otherwise resolves). [atmosphereCommands] deliberately stay
     * untouched by any of this — screen-space, matching
     * [RigRenderer.drawAtmosphere]'s own placement.
     */
    val cameraZoom: Float,
    val cameraOffsetX: Float,
    val cameraOffsetY: Float,
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

    /** Behind the figure — see [OverlayDraw] doc comment. Drawn after the ground line, before [drawCommands]. */
    val behindOverlays: List<OverlayDraw>,

    /** Ordered exactly like the Canvas path's per-bone-index loop — see class doc comment. */
    val drawCommands: List<DrawCommand>,

    /** 0f = fully transparent, 1f = fully opaque. Applied to the whole figure (all commands), not per-command. */
    val figureAlpha: Float,

    /** In front of the figure — see [OverlayDraw] doc comment. Drawn after [drawCommands], before [atmosphereCommands]. */
    val frontOverlays: List<OverlayDraw>,

    /** Screen-space, drawn after the figure — see [AtmosphereDrawCommand] doc comment. */
    val atmosphereCommands: List<AtmosphereDrawCommand>,

    /**
     * Text phase (V2_DECISIONS.md) — raw, UNRESOLVED text, deliberately.
     * Unlike everything else in this class, [GlesFrameRenderer] does the
     * actual rasterization/positioning itself (needs an Android
     * `Bitmap`/`Canvas`/`StaticLayout`, which this class has zero
     * dependency on by design, same reasoning as why the background quad
     * bounds live there too — see [cameraZoom]'s doc comment). Screen-space,
     * NOT camera-transformed, matching [RigRenderer.drawCaption]'s own
     * "after canvas.restore()" placement.
     */
    val captionText: String?,

    /**
     * Reference overlay (text phase, sub-phase 3 — V2_DECISIONS.md), or
     * null if none is configured or not visible at this frame's timeSec
     * (per [ReferenceOverlay.isVisibleAt], called directly rather than
     * reimplemented). UNLIKE [OverlayDraw]'s shape/text list, this is a
     * single global per-project entity, not a time-windowed script layer
     * — so there's no "original list order" to preserve. [GlesFrameRenderer]
     * draws it at the exact same fixed point [RigRenderer.draw]'s own two
     * call sites do: always the FIRST thing in its behind/front group,
     * immediately before that group's ordinary overlay list, never
     * interleaved within it.
     */
    val referenceOverlayDraw: ReferenceOverlayDraw?
) {
    /**
     * See [referenceOverlayDraw]'s own doc comment. [originX]/[originY]/
     * [sizePx] are already camera-transformed by the time
     * [GlesFrameRenderer] sees them (via [transformReferenceOverlayDraw])
     * — there's no rotation to worry about passing through unchanged,
     * unlike [OverlayTextDraw]'s `rotationDeg`, since
     * [com.example.data.ReferenceOverlay] has no rotation field at all.
     * [bitmap] is a genuine, if narrow, exception to this class's
     * "zero Android Bitmap/Canvas dependency" rule — carried through as a
     * plain reference, never touched (measured/drawn/rasterized) here,
     * only by [GlesFrameRenderer]; holding a reference is a much lesser
     * dependency than the active rasterization [captionText]/
     * [OverlayTextDraw] avoid needing in this class.
     */
    data class ReferenceOverlayDraw(
        val type: String,           // ReferenceOverlay.OverlayType.IMAGE or TEXT
        val bitmap: Bitmap?,        // IMAGE only
        val cropLeft: Float, val cropTop: Float, val cropRight: Float, val cropBottom: Float,
        val text: String?,          // TEXT only
        val textColorArgb: Int,
        val showBackdrop: Boolean,
        val originX: Float, val originY: Float,
        val sizePx: Float,
        val inFrontOfFigure: Boolean
    )

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

    /**
     * One piece of a resolved overlay SHAPE's geometry, already transformed
     * into WORLD canvas-pixel space (position/rotation/scale baked in via
     * [RigRenderer.localToWorld]) — unlike [RigRenderer.LocalShapePart],
     * which [RigRenderer.computeOverlayShapeParts] returns in LOCAL space.
     * [Polygon.colors] is per-vertex (not a single color) specifically so a
     * gradient rect/cross can be represented the same way the Phase 4
     * background gradient is — see [GlesFrameRenderer.SOLID_FRAG]'s doc
     * comment. Circle/Line overlay shapes do NOT support a gradient in this
     * phase (a known, documented simplification — see the "shape overlay
     * scope" note in V2_DECISIONS.md); [Circle] only carries one color.
     */
    sealed class OverlayDrawCommand {
        /** Not a data class — see [SceneDrawCommand.Polygon]'s identical reasoning (FloatArray breaks structural equals/hashCode; never compared, only consumed). */
        class Polygon(val points: FloatArray, val colors: IntArray) : OverlayDrawCommand()

        data class Circle(val cx: Float, val cy: Float, val radius: Float, val color: Int) : OverlayDrawCommand()

        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val halfWidth: Float, val color: Int) : OverlayDrawCommand()
    }

    /**
     * One resolved overlay shape layer, ready to draw. [commands] is the
     * CRISP geometry (base color/gradient already baked in per-command) —
     * drawn directly to screen. [glow] mirrors [RigRenderer.drawGmsShape]'s
     * `if (layer.glow)` branch: when true, the SAME geometry is drawn a
     * second time, colored [glowColor] (opacity already folded in — see
     * [RigRenderer.combinedAlphaChannel]'s `opacity * 0.6f` call site),
     * blurred by [glowRadiusPx] (canvas pixels, matching the Canvas path's
     * `BlurMaskFilter` radius exactly), and composited BEHIND [commands] —
     * see [GlesFrameRenderer]'s two-pass Gaussian blur implementation for
     * why this needs to be a separate draw pass through an offscreen
     * texture rather than something the SDF shaders can do inline.
     */
    data class OverlayShapeDraw(
        val commands: List<OverlayDrawCommand>,
        val glow: Boolean,
        val glowColor: Int,
        val glowRadiusPx: Float
    )

    /**
     * One resolved `type == "text"` overlay layer (text phase —
     * V2_DECISIONS.md), ready for [GlesFrameRenderer] to rasterize+draw.
     * UNLIKE [OverlayShapeDraw], this carries only STYLE inputs and the
     * resolved world-space placement — NOT pre-built geometry — because
     * the geometry itself (the rasterized bitmap's pixel bounds) can't be
     * known until [GlesFrameRenderer] actually measures the text with a
     * real `TextPaint`, and that measurement/rasterization needs Android
     * `Bitmap`/`Canvas` APIs this class deliberately has zero dependency
     * on (same reasoning as the camera phase's background quad, and this
     * class's own [captionText] field).
     *
     * [originX]/[originY]/[scale] are ALREADY camera-transformed by the
     * time [GlesFrameRenderer] sees them (via [transformOverlayTextDraw],
     * called from the same final pass as every other overlay) —
     * [rotationDeg] passes through unchanged, since camera zoom/pan/shake
     * has no rotation component. [colorArgb]/[glowColorArgb] already have
     * opacity baked into their alpha channel (matching
     * [RigRenderer.combinedAlphaChannel]'s exact `opacity` /
     * `opacity * 0.8f` split for text vs. its glow — see
     * [RigRenderer.drawGmsText]), so [GlesFrameRenderer] never needs a
     * separate opacity multiplier at draw time.
     */
    data class OverlayTextDraw(
        val text: String,
        val fontSizeFraction: Float,
        val bold: Boolean,
        val align: String,
        val colorArgb: Int,
        val gradientColorArgb: Int?,
        val glow: Boolean,
        val glowColorArgb: Int,
        val glowRadiusFraction: Float,
        val originX: Float,
        val originY: Float,
        val rotationDeg: Float,
        val scale: Float
    )

    /**
     * One item in [behindOverlays]/[frontOverlays] — a SINGLE ordered list
     * per group, deliberately NOT two separate lists (one for shapes, one
     * for text). [RigRenderer.draw]'s own overlay loop dispatches each
     * layer to `drawGmsShape`/`drawGmsText` from ONE loop over the
     * original script layer order — meaning a shape and a text overlay in
     * the same group can be Z-ordered relative to EACH OTHER, not just
     * relative to the figure. Two separate lists (all shapes first, then
     * all text) would silently break that relative order for any script
     * mixing the two types in one group — DEMO's own `intro_badge`
     * (shape) + `wordmark_intro` (text) pairing is exactly this scenario.
     */
    sealed class OverlayDraw {
        data class Shape(val draw: OverlayShapeDraw) : OverlayDraw()
        data class Text(val draw: OverlayTextDraw) : OverlayDraw()
    }

    companion object {
        // TEMPORARY — GLES Phase 3 mouth-position bug (V2_DECISIONS.md).
        // Static analysis of computeMouthGeometry, the FK matrix sourcing,
        // toClipY, drawOval/drawCircle, and the oval fragment shader found
        // no structural asymmetry between the (working) head circle and
        // the (broken) mouth oval — same shared geometry function, same
        // clip-space conversion, same vertex/shader pipeline for both.
        // Rather than guess a sign flip blindly (the exact risk this was
        // deferred to avoid), logging the actual resolved values once so
        // the next device run gives real numbers instead of another
        // guess. Remove this flag + the Log.i call below once the bug's
        // resolved.
        private var loggedMouthDiagnostic = false

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
            timeSec: Float,
            // Camera phase (V2_DECISIONS.md) — defaults match RigRenderer.draw's
            // own defaults exactly, so a caller that doesn't pass these renders
            // identically to before this phase existed.
            cameraZoom: Float = 1f,
            cameraPanX: Float = 0f,
            cameraPanY: Float = 0f,
            cameraShakeIntensity: Float = 0f,
            overlays: List<TimeResolvedOverlay> = emptyList(),
            // Text phase (V2_DECISIONS.md) — see the class-level field's own doc comment.
            captionText: String? = null,
            // Text phase, sub-phase 3 (V2_DECISIONS.md) — see ReferenceOverlayDraw's own doc comment.
            referenceOverlay: ReferenceOverlay? = null,
            referenceOverlayBitmap: Bitmap? = null
        ): GlesFigureFrame {
            val rig    = StickFigureRig
            val bones  = rig.BONES
            val n      = rig.BONE_COUNT
            val minDim = minOf(canvasW, canvasH).toFloat()
            val pts    = FloatArray(4)

            // Camera transform (V2_DECISIONS.md, camera phase) — computed
            // once, applied at the very end to every already-resolved
            // command list below (NOT threaded through the bone/overlay
            // loops themselves, which stay in pre-camera "world" space
            // exactly as before this phase — see transformDrawCommand's
            // doc comment for why that ordering matters for overlay
            // parenting specifically).
            val (shakeX, shakeY) = RigRenderer.computeCameraShakeOffset(timeSec, cameraShakeIntensity, minDim)
            val camera = RigRenderer.computeCameraTransform(canvasW, canvasH, cameraZoom, cameraPanX, cameraPanY, shakeX, shakeY)

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
            // Only built when something actually needs it (a parentBone-
            // attached overlay) — mirrors RigRenderer.draw's own
            // needsBoneAnchors optimization exactly, just folded into this
            // existing loop instead of a separate pre-pass, since endX/endY
            // are already being computed here for every bone regardless.
            val needsBoneAnchors = overlays.any { it.parentBone != null }
            val boneAnchors: MutableMap<String, Pair<Float, Float>>? =
                if (needsBoneAnchors) HashMap(n) else null

            for (i in 0 until n) {
                val bone = bones[i]
                val lengthMultiplier = if (bone.id == "head") appearance.neckLengthMultiplier else 1f
                val length = bone.normalizedLength * scale * lengthMultiplier

                pts[0] = 0f; pts[1] = 0f
                pts[2] = length; pts[3] = 0f
                matrices[i].mapPoints(pts)

                val startX = pts[0]; val startY = pts[1]
                val endX   = pts[2]; val endY   = pts[3]

                if (boneAnchors != null) boneAnchors[bone.id] = (endX / canvasW) to (endY / canvasH)

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
                        if (!loggedMouthDiagnostic) {
                            loggedMouthDiagnostic = true
                            // Extensive static re-check (V2_DECISIONS.md — "GLES
                            // Phase 3 mouth y-offset direction") found computeMouthGeometry,
                            // computeFkMatrices, rootX/Y/scale, drawOval, and the
                            // oval SDF shader all genuinely identical/shared between
                            // Canvas and GLES for the mouth. No asymmetric bug found
                            // by reading alone. Added this log instead of guessing a
                            // fix blind: diagClipY duplicates GlesFrameRenderer.toClipY (no GL context in this class
                            // to call the real one — keep numerically identical to
                            // it or this log is misleading) so the actual clip-space
                            // ordering is visible directly, and logs an eye's
                            // clipY on the same line for a same-frame comparison —
                            // eyes are the one other head feature reported working,
                            // so if the eye's clipY looks equally "off" once this is
                            // read against what's seen on-device, the bug isn't
                            // clip-space at all and is further downstream than this
                            // function.
                            fun diagClipY(py: Float) = 1f - (py / canvasH) * 2f
                            val eyeForDiag = RigRenderer.computeEyeGeometry(
                                endX, endY, startX, startY, r,
                                eyeOpenness, expression, headScaleMultiplier, appearance
                            )
                            Log.i(
                                "GlesFigureFrame",
                                "MOUTH DIAGNOSTIC — head tip(hx=$endX,hy=$endY,clipY=${diagClipY(endY)}) " +
                                    "neck(nx=$startX,ny=$startY,clipY=${diagClipY(startY)}) r=$r " +
                                    "mouth center(cx=${g.cx},cy=${g.cy},clipY=${diagClipY(g.cy)}) " +
                                    "eye center, same frame, for comparison(cx=${eyeForDiag.left.cx},cy=${eyeForDiag.left.cy},clipY=${diagClipY(eyeForDiag.left.cy)}) " +
                                    "halfW=${g.halfWidth} halfH=${g.halfHeight} " +
                                    "canvasW=$canvasW canvasH=$canvasH — " +
                                    "expect: head clipY is the LARGEST of the three (toClipY " +
                                    "decreases as py increases), neck clipY the SMALLEST, mouth " +
                                    "clipY between them, closer to head's. If mouth clipY instead " +
                                    "falls outside [neck,head] or below neck's, that confirms a " +
                                    "clip-space bug here. If it looks correct here but still wrong " +
                                    "on-screen, the bug is downstream of this log (SDF shader or " +
                                    "the actual draw call), not the position math."
                            )
                        }
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

            // Overlay shapes — see OverlayShapeDraw doc comment for scope
            // (type=="shape" only) and V2_DECISIONS.md for the full boundary.
            // applyParenting mirrors RigRenderer.draw's own call exactly,
            // same function, same boneAnchors semantics (built above, inline
            // in the bone loop rather than as a separate pre-pass).
            val resolvedOverlays = if (overlays.isNotEmpty())
                OverlayResolver.applyParenting(overlays, boneAnchors ?: emptyMap())
            else emptyList()
            val (behindResolved, frontResolved) = resolvedOverlays.partition { !it.inFrontOfFigure }
            val behindOverlays = behindResolved.mapNotNull { buildOverlayDraw(it, canvasW, canvasH, minDim) }
            val frontOverlays  = frontResolved.mapNotNull { buildOverlayDraw(it, canvasW, canvasH, minDim) }

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

            val referenceOverlayDraw = buildReferenceOverlayDraw(referenceOverlay, referenceOverlayBitmap, canvasW, canvasH, timeSec)
                ?.let { transformReferenceOverlayDraw(it, camera) }

            return GlesFigureFrame(
                canvasW                 = canvasW,
                canvasH                 = canvasH,
                cameraZoom              = camera.zoom,
                cameraOffsetX           = camera.offsetX,
                cameraOffsetY           = camera.offsetY,
                bgColor                 = bgColor,
                skyColor                = skyColor?.toInt(),
                groundColor             = groundColor?.toInt(),
                backgroundStyle         = overrides.backgroundStyle ?: appearance.backgroundStyle,
                backgroundGradientColor = (overrides.backgroundGradientColor ?: appearance.backgroundGradientColor).toInt(),
                horizonYFraction        = horizonYFraction,
                // Camera-transformed here — everything drawn from these
                // lists is already in final screen space by the time
                // GlesFrameRenderer sees it. See the cameraZoom/offsetX/
                // offsetY doc comment above for the one exception
                // (the background quad).
                sceneCommands           = sceneCommands.map { transformSceneCommand(it, camera) },
                showGroundLine          = overrides.showGroundLine ?: appearance.showGroundLine,
                groundLineColor         = (overrides.groundLineColor ?: appearance.groundLineColor).toInt(),
                groundLineYFraction     = plainGroundLineYFraction,
                behindOverlays          = behindOverlays.map { transformOverlayDraw(it, camera) },
                drawCommands            = commands.map { transformDrawCommand(it, camera) },
                figureAlpha             = (overrides.opacity ?: 1f).coerceIn(0f, 1f),
                frontOverlays           = frontOverlays.map { transformOverlayDraw(it, camera) },
                // NOT camera-transformed — screen-space atmosphere, unchanged
                // from before this phase. See class doc comment.
                atmosphereCommands      = atmosphereCommands,
                captionText             = captionText,
                referenceOverlayDraw    = referenceOverlayDraw
            )
        }

        /**
         * Applies [cam] to one already-resolved [DrawCommand]. Called once,
         * at the very end of [fromFkMatrices], over the whole figure —
         * NOT threaded through the bone loop itself, which stays in
         * pre-camera "world" pixel space exactly as it was before the
         * camera phase. See [transformOverlayShapeDraw]'s doc comment for
         * why that ordering (resolve everything in world space, apply
         * camera once at the end) matters beyond just being tidy.
         */
        private fun transformDrawCommand(cmd: DrawCommand, cam: RigRenderer.CameraTransform): DrawCommand = when (cmd) {
            is DrawCommand.BoneLine -> cmd.copy(
                sx = cam.tx(cmd.sx), sy = cam.ty(cmd.sy),
                ex = cam.tx(cmd.ex), ey = cam.ty(cmd.ey),
                halfWidth = cam.tLen(cmd.halfWidth)
            )
            is DrawCommand.Joint -> cmd.copy(cx = cam.tx(cmd.cx), cy = cam.ty(cmd.cy), radius = cam.tLen(cmd.radius))
            is DrawCommand.Head  -> cmd.copy(cx = cam.tx(cmd.cx), cy = cam.ty(cmd.cy), radius = cam.tLen(cmd.radius))
            is DrawCommand.Oval  -> cmd.copy(
                cx = cam.tx(cmd.cx), cy = cam.ty(cmd.cy),
                halfWidth = cam.tLen(cmd.halfWidth), halfHeight = cam.tLen(cmd.halfHeight)
            )
            is DrawCommand.Eyebrow -> cmd.copy(
                sx = cam.tx(cmd.sx), sy = cam.ty(cmd.sy),
                ex = cam.tx(cmd.ex), ey = cam.ty(cmd.ey),
                halfWidth = cam.tLen(cmd.halfWidth)
            )
        }

        /** Same reasoning as [transformDrawCommand] — applied once, at the end, over the whole list. */
        private fun transformSceneCommand(cmd: SceneDrawCommand, cam: RigRenderer.CameraTransform): SceneDrawCommand = when (cmd) {
            is SceneDrawCommand.Polygon -> {
                val pts = FloatArray(cmd.points.size)
                var i = 0
                while (i < cmd.points.size) {
                    pts[i] = cam.tx(cmd.points[i]); pts[i + 1] = cam.ty(cmd.points[i + 1]); i += 2
                }
                SceneDrawCommand.Polygon(pts, cmd.color)
            }
            is SceneDrawCommand.Rect   -> SceneDrawCommand.Rect(cam.tx(cmd.l), cam.ty(cmd.t), cam.tx(cmd.r), cam.ty(cmd.b), cmd.color)
            is SceneDrawCommand.Circle -> SceneDrawCommand.Circle(cam.tx(cmd.cx), cam.ty(cmd.cy), cam.tLen(cmd.radius), cmd.color)
        }

        /**
         * Applies [cam] to one already-built [OverlayShapeDraw] — called
         * AFTER [buildOverlayShapeDraw] has already resolved parenting and
         * done its own local-to-world transform (position/rotation/scale),
         * never before. Parenting anchors ([RigRenderer.localToWorld]'s
         * `originX`/`originY` in that function) are deliberately computed
         * from PRE-camera bone-anchor fractions — mirroring how, in
         * [RigRenderer.draw], an overlay's world position is worked out in
         * plain canvas-pixel space and only THEN implicitly carried through
         * whatever camera transform is active on the canvas at actual draw
         * time. Applying camera here, once, after all of that, keeps GLES
         * doing the same two-stage resolution in the same order — parent
         * first in world space, then move the whole world together — rather
         * than risking a parented overlay's offset itself getting
         * double-transformed if camera were threaded any earlier.
         * [glowRadiusPx] is scaled by zoom too, for the same "camera zoom
         * scales everything uniformly" reasoning as [DrawCommand] sizes —
         * NOT yet verified on-device in combination with an actual zoomed
         * shot (glow itself isn't on-device-confirmed as of this phase
         * either — see V2_DECISIONS.md's Deferred section).
         */
        /** Dispatches to [transformOverlayShapeDraw] or [transformOverlayTextDraw] — see [OverlayDraw] doc comment for why this is one list, not two. */
        private fun transformOverlayDraw(item: OverlayDraw, cam: RigRenderer.CameraTransform): OverlayDraw = when (item) {
            is OverlayDraw.Shape -> OverlayDraw.Shape(transformOverlayShapeDraw(item.draw, cam))
            is OverlayDraw.Text  -> OverlayDraw.Text(transformOverlayTextDraw(item.draw, cam))
        }

        /**
         * Same reasoning as [transformOverlayTextDraw] — no rotation to pass
         * through, since [ReferenceOverlay] has no rotation field.
         */
        private fun transformReferenceOverlayDraw(draw: ReferenceOverlayDraw, cam: RigRenderer.CameraTransform): ReferenceOverlayDraw =
            draw.copy(originX = cam.tx(draw.originX), originY = cam.ty(draw.originY), sizePx = cam.tLen(draw.sizePx))

        /**
         * Same reasoning as [transformOverlayShapeDraw] — [originX]/[originY]
         * and [OverlayTextDraw.scale] get the camera transform;
         * [OverlayTextDraw.rotationDeg] passes through unchanged (camera
         * zoom/pan/shake has no rotation component).
         */
        private fun transformOverlayTextDraw(draw: OverlayTextDraw, cam: RigRenderer.CameraTransform): OverlayTextDraw =
            draw.copy(originX = cam.tx(draw.originX), originY = cam.ty(draw.originY), scale = draw.scale * cam.zoom)

        private fun transformOverlayShapeDraw(overlay: OverlayShapeDraw, cam: RigRenderer.CameraTransform): OverlayShapeDraw {
            val transformed = overlay.commands.map { cmd ->
                when (cmd) {
                    is OverlayDrawCommand.Polygon -> {
                        val pts = FloatArray(cmd.points.size)
                        var i = 0
                        while (i < cmd.points.size) {
                            pts[i] = cam.tx(cmd.points[i]); pts[i + 1] = cam.ty(cmd.points[i + 1]); i += 2
                        }
                        OverlayDrawCommand.Polygon(pts, cmd.colors)
                    }
                    is OverlayDrawCommand.Circle -> OverlayDrawCommand.Circle(cam.tx(cmd.cx), cam.ty(cmd.cy), cam.tLen(cmd.radius), cmd.color)
                    is OverlayDrawCommand.Line   -> OverlayDrawCommand.Line(
                        cam.tx(cmd.x1), cam.ty(cmd.y1), cam.tx(cmd.x2), cam.ty(cmd.y2), cam.tLen(cmd.halfWidth), cmd.color
                    )
                }
            }
            return overlay.copy(commands = transformed, glowRadiusPx = cam.tLen(overlay.glowRadiusPx))
        }

        /**
         * Builds one [ReferenceOverlayDraw], or null if [overlay] is null,
         * not visible at [timeSec] (delegates straight to
         * [ReferenceOverlay.isVisibleAt] — reused, not reimplemented), or
         * (IMAGE case) has no [bitmap] to draw. `originX`/`originY`/`sizePx`
         * are pre-camera "world" values here (`canvasW * overlay.posX` etc,
         * matching [RigRenderer.drawReferenceOverlay]'s own `cx`/`cy`/`sizePx`
         * exactly) — [transformReferenceOverlayDraw] applies camera after.
         */
        private fun buildReferenceOverlayDraw(
            overlay: ReferenceOverlay?, bitmap: Bitmap?, canvasW: Int, canvasH: Int, timeSec: Float
        ): ReferenceOverlayDraw? {
            if (overlay == null || !overlay.isVisibleAt(timeSec)) return null
            if (overlay.type == ReferenceOverlay.OverlayType.IMAGE && bitmap == null) return null

            val minDim = minOf(canvasW, canvasH).toFloat()
            return ReferenceOverlayDraw(
                type = overlay.type,
                bitmap = bitmap,
                cropLeft = overlay.cropLeft, cropTop = overlay.cropTop,
                cropRight = overlay.cropRight, cropBottom = overlay.cropBottom,
                text = overlay.text,
                textColorArgb = overlay.textColor.toInt(),
                showBackdrop = overlay.showBackdrop,
                originX = canvasW * overlay.posX,
                originY = canvasH * overlay.posY,
                sizePx = minDim * overlay.sizeFraction,
                inFrontOfFigure = overlay.inFrontOfFigure
            )
        }

        /** Dispatches to [buildOverlayShapeDraw] or [buildOverlayTextDraw] by [ResolvedOverlay.type] — see [OverlayDraw] doc comment for why this is one list, not two. */
        private fun buildOverlayDraw(layer: ResolvedOverlay, canvasW: Int, canvasH: Int, minDim: Float): OverlayDraw? = when (layer.type) {
            "shape" -> buildOverlayShapeDraw(layer, canvasW, canvasH, minDim)?.let { OverlayDraw.Shape(it) }
            "text"  -> buildOverlayTextDraw(layer, canvasW, canvasH)?.let { OverlayDraw.Text(it) }
            else    -> null   // "figure" overlays, particle trails — still unimplemented, see class doc comment.
        }

        /**
         * Builds one [OverlayTextDraw] from a fully-resolved overlay, or
         * null if it's invisible (`opacity <= 0.001f`, matching
         * [buildOverlayShapeDraw]'s own early-return), not a `type ==
         * "text"` layer, or has no text to show. Unlike
         * [buildOverlayShapeDraw], this does NOT call
         * [RigRenderer.localToWorld] itself — it can't, without knowing the
         * rasterized text's local bounding box first, which only
         * [GlesFrameRenderer] can measure (see [OverlayTextDraw]'s own doc
         * comment). [originX]/[originY]/[layer.rotationDeg]/[layer.scale]
         * are passed through as-is (pre-camera — the camera transform is
         * applied later, in [transformOverlayTextDraw]) for
         * [GlesFrameRenderer] to combine with the measured bounding box
         * itself.
         */
        private fun buildOverlayTextDraw(layer: ResolvedOverlay, canvasW: Int, canvasH: Int): OverlayTextDraw? {
            val text = layer.text
            if (layer.opacity <= 0.001f || layer.type != "text" || text.isNullOrBlank()) return null

            val baseColor = layer.color.toInt()
            val colorArgb = (RigRenderer.combinedAlphaChannel(baseColor, layer.opacity) shl 24) or (baseColor and 0xFFFFFF)
            val gradientColorArgb = layer.gradientColor?.toInt()?.let { g ->
                (RigRenderer.combinedAlphaChannel(g, layer.opacity) shl 24) or (g and 0xFFFFFF)
            }
            // 0.8f matches RigRenderer.drawGmsText's own glow-alpha call site exactly — not reused/re-derived, mirrored.
            val glowBase = layer.glowColor.toInt()
            val glowColorArgb = (RigRenderer.combinedAlphaChannel(glowBase, layer.opacity * 0.8f) shl 24) or (glowBase and 0xFFFFFF)

            return OverlayTextDraw(
                text = text,
                fontSizeFraction = layer.fontSize,
                bold = layer.bold,
                align = layer.align,
                colorArgb = colorArgb,
                gradientColorArgb = gradientColorArgb,
                glow = layer.glow,
                glowColorArgb = glowColorArgb,
                glowRadiusFraction = layer.glowRadius,
                originX = canvasW * layer.x,
                originY = canvasH * layer.y,
                rotationDeg = layer.rotationDeg,
                scale = layer.scale
            )
        }

        /**
         * Builds one [OverlayShapeDraw] from a fully-resolved overlay, or
         * null if it's invisible (`opacity <= 0.001f`, matching
         * [RigRenderer.drawGmsOverlay]'s own early-return) or not a
         * `type == "shape"` layer.
         *
         * Applies the position/rotation/scale transform explicitly via
         * [RigRenderer.localToWorld] — the GLES equivalent of
         * [RigRenderer.drawGmsOverlay]'s `canvas.translate`/`rotate`/`scale`.
         *
         * GRADIENT SCOPE: only "rect" and "cross" shapes get a gradient here
         * (per-vertex color on the [OverlayDrawCommand.Polygon], same
         * technique as the Phase 4 background gradient) — "circle"/"line"/
         * "arrow" render solid-color only even if [ResolvedOverlay.gradientColor]
         * is set. A documented simplification, not an oversight: circles
         * have no natural "top/bottom" for a linear gradient the way a rect
         * does without a second shader capability, and a gradient across a
         * thin stroke (line/arrow) would be barely visible in practice —
         * see V2_DECISIONS.md for the full reasoning.
         */
        private fun buildOverlayShapeDraw(layer: ResolvedOverlay, canvasW: Int, canvasH: Int, minDim: Float): OverlayShapeDraw? {
            if (layer.opacity <= 0.001f || layer.type != "shape") return null

            val originX = canvasW * layer.x
            val originY = canvasH * layer.y
            val baseColor = layer.color.toInt()
            val crispAlpha = RigRenderer.combinedAlphaChannel(baseColor, layer.opacity)
            val crispColor = (crispAlpha shl 24) or (baseColor and 0xFFFFFF)
            val gradientEndColor = layer.gradientColor?.toInt()?.let { g ->
                (RigRenderer.combinedAlphaChannel(g, layer.opacity) shl 24) or (g and 0xFFFFFF)
            }
            val supportsGradient = layer.shape == "rect" || layer.shape == "cross" || layer.shape.isEmpty()

            val parts = RigRenderer.computeOverlayShapeParts(layer, canvasW, canvasH, minDim)
            val commands = ArrayList<OverlayDrawCommand>(parts.size)
            for (part in parts) {
                when (part) {
                    is RigRenderer.LocalShapePart.Circle -> {
                        val (wx, wy) = RigRenderer.localToWorld(part.cx, part.cy, originX, originY, layer.rotationDeg, layer.scale)
                        commands += OverlayDrawCommand.Circle(wx, wy, part.radius * layer.scale, crispColor)
                    }
                    is RigRenderer.LocalShapePart.Line -> {
                        val (wx1, wy1) = RigRenderer.localToWorld(part.x1, part.y1, originX, originY, layer.rotationDeg, layer.scale)
                        val (wx2, wy2) = RigRenderer.localToWorld(part.x2, part.y2, originX, originY, layer.rotationDeg, layer.scale)
                        commands += OverlayDrawCommand.Line(wx1, wy1, wx2, wy2, part.halfWidth * layer.scale, crispColor)
                    }
                    is RigRenderer.LocalShapePart.Triangle -> {
                        val (wx1, wy1) = RigRenderer.localToWorld(part.x1, part.y1, originX, originY, layer.rotationDeg, layer.scale)
                        val (wx2, wy2) = RigRenderer.localToWorld(part.x2, part.y2, originX, originY, layer.rotationDeg, layer.scale)
                        val (wx3, wy3) = RigRenderer.localToWorld(part.x3, part.y3, originX, originY, layer.rotationDeg, layer.scale)
                        commands += OverlayDrawCommand.Polygon(
                            floatArrayOf(wx1, wy1, wx2, wy2, wx3, wy3),
                            intArrayOf(crispColor, crispColor, crispColor)
                        )
                    }
                    is RigRenderer.LocalShapePart.Rect -> {
                        val corners = floatArrayOf(
                            part.cx - part.halfW, part.cy - part.halfH,
                            part.cx + part.halfW, part.cy - part.halfH,
                            part.cx + part.halfW, part.cy + part.halfH,
                            part.cx - part.halfW, part.cy + part.halfH
                        )
                        val worldPts = FloatArray(8)
                        val colors = IntArray(4)
                        // halfSpan matches RigRenderer.drawGmsShape's own
                        // non-circle gradient branch exactly: (layer.height ?:
                        // 0.15f) * canvasH / 2f — canvasH, not minDim. The
                        // gradient's LinearGradient(0,-halfSpan,0,halfSpan) is
                        // set ONCE per overlay and reused for BOTH rects of a
                        // "cross" (verified against that exact call site, not
                        // assumed), so a crossbar sitting in a narrow slice
                        // away from y=0 should show a proportionally SUBTLE
                        // color shift, not a full base-to-gradient sweep
                        // across its own small height — a per-vertex lerp
                        // against the correct halfSpan gets this right; an
                        // earlier draft did a binary "which side of y=0" split
                        // per-corner instead, which would have made the
                        // crossbar show a much stronger transition than
                        // Canvas actually renders. Caught on review, not
                        // shipped.
                        val halfSpan = (layer.height ?: 0.15f) * canvasH / 2f
                        for (v in 0 until 4) {
                            val (wx, wy) = RigRenderer.localToWorld(corners[v * 2], corners[v * 2 + 1], originX, originY, layer.rotationDeg, layer.scale)
                            worldPts[v * 2] = wx; worldPts[v * 2 + 1] = wy
                            colors[v] = if (gradientEndColor != null && supportsGradient && halfSpan > 0.001f) {
                                val t = ((corners[v * 2 + 1] + halfSpan) / (2f * halfSpan)).coerceIn(0f, 1f)
                                lerpArgb(crispColor, gradientEndColor, t)
                            } else crispColor
                        }
                        commands += OverlayDrawCommand.Polygon(worldPts, colors)
                    }
                }
            }

            return OverlayShapeDraw(
                commands = commands,
                glow = layer.glow,
                glowColor = (RigRenderer.combinedAlphaChannel(layer.glowColor.toInt(), layer.opacity * 0.6f) shl 24) or (layer.glowColor.toInt() and 0xFFFFFF),
                glowRadiusPx = (layer.glowRadius * minDim).coerceAtLeast(1f)
            )
        }

        /** Per-channel linear interpolation between two ARGB packed ints, t in [0,1]. */
        private fun lerpArgb(c1: Int, c2: Int, t: Float): Int {
            val ct = t.coerceIn(0f, 1f)
            fun lerpChannel(shift: Int): Int {
                val a = (c1 ushr shift) and 0xFF
                val b = (c2 ushr shift) and 0xFF
                return (a + (b - a) * ct + 0.5f).toInt().coerceIn(0, 255)
            }
            return (lerpChannel(24) shl 24) or (lerpChannel(16) shl 16) or (lerpChannel(8) shl 8) or lerpChannel(0)
        }
    }
}
