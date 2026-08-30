package com.example.engine

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.AppearanceSettings
import com.example.data.ReferenceOverlay
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.roundToInt

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

    // Motion-graphics overlay layers (V2 — text/shape, see OverlayResolver).
    // Named distinctly from the referenceOverlay* paints above (which are
    // for the manually-configured reference image, a different feature).
    private val gmsShapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gmsTextPaint  = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val gmsGlowPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // "figure" overlay layers (character variants) — deliberately SEPARATE
    // Paint objects and a per-call LOCAL matrix array (see drawSecondaryFigure),
    // not shared with the main figure's own bonePaint/headPaint/matrices —
    // full isolation from the already-working main rendering path was the
    // explicit point of this design, not an oversight.
    private val figureBonePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val figureHeadPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val figureEyePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val figureMouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // Reused across frames/calls rather than allocated fresh each time —
    // same reasoning as the main figure's own `matrices` field above.
    // Every element gets fully overwritten (.reset() or .set()) before use
    // each call, so reuse across calls is safe with no stale state.
    private val secondaryFigureMatrices = Array(StickFigureRig.BONE_COUNT) { Matrix() }
    // See cachedShrunkTextSize's doc comment.
    private val textShrinkCache = HashMap<String, Float>()

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
        // Walk-cycle foot-plant correction — see
        // StickFigureRig.WALK_STANCE_TARGET_Y_NORMALIZED's doc comment. Same
        // normalized-length units as bone length, so it's scaled by `scale`
        // below, not minDim/canvasH directly. 0f (the default) reproduces
        // today's behavior exactly for every project with no walk_a/walk_b
        // pair active in its timeline.
        hipBobNormalized: Float = 0f,
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
        referenceOverlayBitmap: Bitmap? = null,
        // V2 — motion-graphics overlay layers. TIME-RESOLVED only (not yet
        // parented) — this function itself does the parenting step, via
        // OverlayResolver.applyParenting, using bone anchor positions
        // captured during ITS OWN FK pass below. See OverlayResolver's doc
        // comment for why parenting can't be hoisted out to PlaybackEngine
        // the way the rest of this resolution is (bone positions are
        // canvas-size-dependent, and dual-aspect export's two targets have
        // different canvas sizes). Drawn INSIDE the camera transform
        // (before canvas.restore() below) — unlike captionText/atmosphere,
        // which are deliberately screen-space, these are meant to pan/zoom/
        // shake along with the figure, per V2_DECISIONS.md.
        overlays: List<TimeResolvedOverlay> = emptyList(),
        // Per-frame script-driven overrides of otherwise-static AppearanceSettings
        // fields (figure position/scale, figure/scene colors) — see
        // FigureOverrides' doc comment. Defaulting to an all-null instance
        // means a project with no scripted overrides renders exactly as it
        // did before this feature existed.
        overrides: FigureOverrides = FigureOverrides()
    ) {
        val minDim  = min(canvasW, canvasH).toFloat()
        val scale   = minDim * (overrides.scale ?: appearance.characterScale)
        val rootX   = canvasW * (overrides.x ?: appearance.rootAnchorX)
        val rootY   = canvasH * (overrides.y ?: appearance.rootAnchorY) + hipBobNormalized * scale

        // ── Camera transform (V2, purely AI-JSON-driven — no automatic
        // behaviour derives this from amplitude). Wraps EVERYTHING below —
        // background, ground line, grid, and the figure — so a zoom/pan/shake
        // reads as an actual camera move over the whole scene, not just the
        // character scaling in place against a fixed backdrop.
        val zoom = cameraZoom.coerceAtLeast(0.1f)
        // Extracted to the shared computeCameraShakeOffset (companion object,
        // camera phase — V2_DECISIONS.md) so the GLES path computes this
        // identical jitter instead of risking its own copy drifting from
        // this one — see that function's doc comment for why this
        // particular piece of math has to match bit-for-bit. Pure
        // extract-method: same formula, same inputs, same outputs as before.
        val (shakeX, shakeY) = computeCameraShakeOffset(currentTimeSec, cameraShakeIntensity, minDim)

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
        val bgColor = overrides.bgColor ?: if (forExport) appearance.exportBgColor else appearance.previewBgColor
        val groundLineYFraction = overrides.groundLineYFraction ?: appearance.groundLineYFraction
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
            val hz     = canvasH * (horizonY ?: groundLineYFraction)
            backgroundPaint.shader = null
            backgroundPaint.color = sky
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, hz, backgroundPaint)
            backgroundPaint.color = ground
            canvas.drawRect(-canvasW.toFloat(), hz, canvasW * 2f, canvasH * 2f, backgroundPaint)
        } else if ((overrides.backgroundStyle ?: appearance.backgroundStyle) == "gradient") {
            // Bounded to the VISIBLE frame (0..canvasH), not the oversized
            // safety rect below — CLAMP already extends the start/end colours
            // flat across the oversized margin on its own. Spanning the
            // gradient across the oversized rect instead would mean the
            // visible viewport only ever shows the middle third of the
            // configured colour transition, which was a real bug caught on
            // review (reasoned through the maths, not seen rendered).
            val gradientEnd = overrides.backgroundGradientColor ?: appearance.backgroundGradientColor
            backgroundPaint.shader = LinearGradient(
                0f, 0f, 0f, canvasH.toFloat(),
                bgColor.toInt(), gradientEnd.toInt(),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, canvasH * 2f, backgroundPaint)
        } else {
            backgroundPaint.shader = null
            backgroundPaint.color = bgColor.toInt()
            canvas.drawRect(-canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, canvasH * 2f, backgroundPaint)
        }

        if (sceneShape != SceneShape.NONE) {
            drawSceneShape(canvas, canvasW, canvasH, horizonY ?: groundLineYFraction, sceneShape, appearance, currentTimeSec, overrides)
        }

        // Stars specifically (not rain/snow/fog — see drawAtmosphere's own
        // doc comment for why those stay screen-space) are drawn HERE,
        // inside the camera transform and before the figure's own FK pass
        // below — a background-sky element like stars reading as being IN
        // FRONT of the figure was a real, reported bug. Panning/zooming
        // the camera now moves the stars along with the rest of the scene,
        // which is the correct behavior for a sky element (unlike rain/
        // snow, which are meant to feel like they're between the camera
        // and the whole scene, not part of it).
        if (sceneAtmosphere == SceneAtmosphere.STARS) {
            drawStars(canvas, canvasW, canvasH, currentTimeSec)
        }

        if (overrides.showGroundLine ?: appearance.showGroundLine) {
            groundPaint.color = (overrides.groundLineColor ?: appearance.groundLineColor).toInt()
            groundPaint.strokeWidth = 2f
            val groundY = canvasH * groundLineYFraction
            canvas.drawLine(-canvasW.toFloat(), groundY, canvasW * 2f, groundY, groundPaint)
        }

        if (appearance.showGrid && !forExport) drawGrid(canvas, canvasW, canvasH, appearance)

        val overlayVisible = referenceOverlay != null && referenceOverlay.isVisibleAt(currentTimeSec)
        if (overlayVisible && referenceOverlay != null && !referenceOverlay.inFrontOfFigure) {
            drawReferenceOverlay(canvas, canvasW, canvasH, referenceOverlay, referenceOverlayBitmap)
        }

        bonePaint.color          = (overrides.boneColor ?: appearance.boneColor).toInt()
        bonePaint.strokeWidth    = appearance.boneStrokeNormalized * minDim
        headPaint.color          = (overrides.headColor ?: overrides.boneColor ?: appearance.headColor).toInt()
        jointPaint.color         = (overrides.jointColor ?: overrides.boneColor ?: appearance.jointColor).toInt()
        mouthPaint.color         = (overrides.mouthColor ?: appearance.mouthColor).toInt()
        eyePaint.color           = (overrides.eyeColor ?: appearance.eyeColor).toInt()
        eyebrowPaint.color       = (overrides.eyebrowColor ?: appearance.eyebrowColor).toInt()
        // strokeWidth is NOT set here — it needs to scale with the head's
        // current drawn size (headScaleMultiplier), so it's set per-draw
        // inside drawEyebrows() instead of once with a fixed absolute value.
        // A fixed value here looked disproportionately thick on a shrunk head
        // and disproportionately thin on an enlarged one.
        val jointR               = appearance.jointRadiusNormalized * minDim
        val showJoints           = if (forExport) appearance.showJointsOnExport else appearance.showJoints
        val headScaleMultiplier  = overrides.headScale ?: appearance.headScaleMultiplier
        // Resolved 0..1 figure opacity — see FigureOverrides.opacity's doc
        // comment. Used below to (a) skip the figure's own draw pass
        // entirely near zero, genuinely absent rather than just invisible,
        // and (b) wrap it in a single saveLayerAlpha otherwise, rather than
        // multiplying every individual Paint's alpha (bone/head/joint/
        // mouth/eye/eyebrow) and risking missing one.
        val figureAlpha = (overrides.opacity ?: 1f).coerceIn(0f, 1f)

        // ── FK pass ───────────────────────────────────────────────────────────
        // Delegates to the companion's computeFkMatrices — see that function's
        // doc comment for why this is now a standalone, Canvas-independent
        // function rather than inline here: the GLES export path (Phase 2,
        // V2_DECISIONS.md) needs this exact computation too, and duplicating
        // it there would be exactly the parity risk the whole "export-only,
        // not a preview rewrite" decision was contingent on mitigating.
        computeFkMatrices(angles, rootX, rootY, scale, matrices)

        // ── Bone anchors + overlay resolution ───────────────────────────────
        // Moved ahead of the actual draw pass below (previously computed as
        // a side effect of it) so overlays with inFrontOfFigure = false can
        // draw BEFORE the figure using real anchor positions. Genuinely free
        // vs. the old ordering — this reads off the SAME matrices the FK
        // pass above already computed; no new forward-kinematics work, just
        // an earlier readout of numbers that already exist. See
        // OverlayLayer.inFrontOfFigure's doc comment for the reasoning.
        //
        // Only built when something actually needs it (a parentBone-
        // attached layer) — most frames have no overlay layers at all, or
        // none that attach to a bone.
        val needsBoneAnchors = overlays.any { it.parentBone != null }
        val boneAnchors: MutableMap<String, Pair<Float, Float>>? =
            if (needsBoneAnchors) HashMap(n) else null

        if (boneAnchors != null) {
            for (i in 0 until n) {
                val bone = bones[i]
                val lengthMultiplier = if (bone.id == "head") appearance.neckLengthMultiplier else 1f
                val length = bone.normalizedLength * scale * lengthMultiplier
                pts[0] = 0f; pts[1] = 0f
                pts[2] = length; pts[3] = 0f
                matrices[i].mapPoints(pts)
                boneAnchors[bone.id] = (pts[2] / canvasW) to (pts[3] / canvasH)
            }
        }

        val resolvedOverlays = if (overlays.isNotEmpty())
            OverlayResolver.applyParenting(overlays, boneAnchors ?: emptyMap())
        else emptyList()
        // partition{} predicate is "match goes to first list" — matching
        // !inFrontOfFigure (i.e. behind) first reads more naturally at the
        // call site below than the inverted alternative would.
        val (behindOverlays, frontOverlays) = resolvedOverlays.partition { !it.inFrontOfFigure }

        for (layer in behindOverlays) {
            if (layer.trailPoints.size >= 2) drawGmsTrail(canvas, canvasW, canvasH, layer)
            drawGmsOverlay(canvas, canvasW, canvasH, layer, appearance)
        }

        // ── Draw pass ─────────────────────────────────────────────────────────
        // Skipped entirely near figureAlpha == 0 (genuinely absent, not
        // just invisible) and wrapped in a single saveLayerAlpha otherwise
        // — see figureAlpha's own doc comment above for why one control
        // point here beats multiplying every individual Paint's alpha.
        if (figureAlpha > 0.001f) {
        val figureLayer = if (figureAlpha < 0.999f) {
            canvas.saveLayerAlpha(
                -canvasW.toFloat(), -canvasH.toFloat(), canvasW * 2f, canvasH * 2f,
                (figureAlpha * 255f).roundToInt().coerceIn(0, 255)
            )
        } else null

        for (i in 0 until n) {
            val bone   = bones[i]
            // neckLengthMultiplier only affects the head bone's own length
            // (how far the head CENTER sits above the torso tip) — it has
            // no children, so this can't cascade into any other bone's
            // position. See AppearanceSettings.neckLengthMultiplier's doc
            // comment for why this needed to be separate from
            // headScaleMultiplier (which controls head SIZE, not position).
            val lengthMultiplier = if (bone.id == "head") appearance.neckLengthMultiplier else 1f
            val length = bone.normalizedLength * scale * lengthMultiplier

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
                val r = bone.headNormalizedRadius * scale * headScaleMultiplier
                canvas.drawCircle(endX, endY, r, headPaint)
                if (appearance.showMouth) {
                    drawMouth(canvas, endX, endY, startX, startY, r, mouthShape, mouthOpenness, expression, headScaleMultiplier)
                }
                if (appearance.showEyes) {
                    drawEyes(canvas, endX, endY, startX, startY, r, eyeOpenness, expression, headScaleMultiplier, appearance)
                }
            } else {
                canvas.drawLine(startX, startY, endX, endY, bonePaint)
            }

            if (showJoints && !bone.isHeadBone) {
                canvas.drawCircle(startX, startY, jointR, jointPaint)
            }
        }

        if (figureLayer != null) canvas.restoreToCount(figureLayer)
        } // end if (figureAlpha > 0.001f)

        if (overlayVisible && referenceOverlay != null && referenceOverlay.inFrontOfFigure) {
            drawReferenceOverlay(canvas, canvasW, canvasH, referenceOverlay, referenceOverlayBitmap)
        }

        for (layer in frontOverlays) {
            if (layer.trailPoints.size >= 2) drawGmsTrail(canvas, canvasW, canvasH, layer)
            drawGmsOverlay(canvas, canvasW, canvasH, layer, appearance)
        }

        canvas.restore()

        // ── Screen-space layers (unaffected by camera zoom/pan/shake) ─────────
        // Rain/snow/fog are a whole-viewport weather filter — panning or
        // zooming the camera shouldn't make rain streaks slide relative to
        // the frame, so these stay screen-space, same reasoning as the
        // caption being a fixed subtitle rather than something that
        // scrolls with the scene. STARS is handled separately, in
        // world-space before the figure — see that call site's own
        // comment for why a background-sky element needed different
        // treatment.
        if (sceneAtmosphere != SceneAtmosphere.NONE && sceneAtmosphere != SceneAtmosphere.STARS) {
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
    /**
     * Scene shapes are never fully static — each type gets a small,
     * continuous, deterministic motion (a function of [timeSec], same
     * determinism reasoning as the atmosphere effects) so the backdrop reads
     * as alive rather than a frozen cutout, without competing with the
     * figure for attention.
     *
     * Mountains/city sway very slightly (a distant-parallax "breathing"
     * feel) rather than scroll — both shapes already overdraw past the
     * canvas edges for mountains, or sit comfortably inside a small sway
     * margin for city, so this needs no wraparound handling. Trees sway
     * individually with a per-tree phase offset so they don't all move in
     * lockstep, which would look mechanical. Clouds are the one shape that
     * conventionally drifts continuously in a single direction rather than
     * swaying in place, so they alone use real wraparound motion via
     * [wrapCoord] — drawn across an extended span so a cloud re-entering
     * from the opposite edge is never visible popping in.
     */
    private fun drawSceneShape(canvas: Canvas, w: Int, h: Int, horizonYFraction: Float, shape: String, appearance: AppearanceSettings, timeSec: Float, overrides: FigureOverrides) {
        // Clash-avoidance clamps scene-shape color against the figure's
        // CURRENT color, not the project's static default — otherwise, once
        // boneColor becomes script-overridable, this check would keep
        // constraining against a stale reference the moment an event
        // changes the figure's actual color, silently defeating the safety
        // clamp for the rest of the video.
        val currentBoneColor = overrides.boneColor ?: appearance.boneColor
        sceneShapePaint.color = constrainSceneColor(currentBoneColor, currentBoneColor, alpha = 0x66)
        // Geometry now lives in the compute* companion functions below —
        // shared with the GLES export path (Phase 4, V2_DECISIONS.md), same
        // reasoning as computeMouthGeometry's doc comment (Phase 3).
        when (shape) {
            SceneShape.MOUNTAINS -> {
                val pts = computeMountainPolygon(w, h, horizonYFraction, timeSec)
                val path = Path()
                path.moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
                path.close()
                canvas.drawPath(path, sceneShapePaint)
            }
            SceneShape.CITY -> {
                for (b in computeCityBuildings(w, h, horizonYFraction, timeSec)) {
                    canvas.drawRect(b.l, b.t, b.r, b.b, sceneShapePaint)
                }
            }
            SceneShape.TREES -> {
                for (t in computeTreePositions(w, h, horizonYFraction, timeSec)) {
                    canvas.drawCircle(t.canopy.cx, t.canopy.cy, t.canopy.halfWidth, sceneShapePaint)
                    canvas.drawRect(t.trunk.l, t.trunk.t, t.trunk.r, t.trunk.b, sceneShapePaint)
                }
            }
            SceneShape.CLOUDS -> {
                for (c in computeCloudPositions(w, h, horizonYFraction, timeSec)) {
                    for (puff in c) canvas.drawCircle(puff.cx, puff.cy, puff.halfWidth, sceneShapePaint)
                }
            }
            SceneShape.ROOM -> {
                for (f in computeRoomFurniture(w, h, horizonYFraction, timeSec)) {
                    canvas.drawRect(f.l, f.t, f.r, f.b, sceneShapePaint)
                }
            }
            SceneShape.BEACH -> {
                val beach = computeBeachElements(w, h, horizonYFraction, timeSec)
                canvas.drawCircle(beach.sun.cx, beach.sun.cy, beach.sun.halfWidth, sceneShapePaint)
                for (u in beach.umbrellas) {
                    canvas.drawCircle(u.canopy.cx, u.canopy.cy, u.canopy.halfWidth, sceneShapePaint)
                    canvas.drawRect(u.trunk.l, u.trunk.t, u.trunk.r, u.trunk.b, sceneShapePaint)
                }
            }
        }
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
                // Was a flat 2f — see computeStarPositions' doc comment.
                atmospherePaint.strokeWidth = min(w, h).toFloat() * 0.0018f
                for (d in computeRainDrops(w, h, timeSec)) {
                    canvas.drawLine(d.x1, d.y1, d.x2, d.y2, atmospherePaint)
                }
            }
            SceneAtmosphere.SNOW -> {
                atmospherePaint.color = 0xCCFFFFFFL.toInt()
                for (f in computeSnowFlakes(w, h, timeSec)) {
                    canvas.drawCircle(f.cx, f.cy, f.halfWidth, atmospherePaint)
                }
            }
        }
    }

    /**
     * Stars specifically — split out from [drawAtmosphere] because it's
     * called from a DIFFERENT place in [draw] (world-space, before the
     * figure) than rain/snow/fog (screen-space, after). See the call
     * site's own comment for why. Same fixed pseudo-random grid + gentle
     * twinkle as before this split, unchanged.
     */
    private fun drawStars(canvas: Canvas, w: Int, h: Int, timeSec: Float) {
        for (s in computeStarPositions(w, h, timeSec)) {
            atmospherePaint.color = 0xFFFFFFFF.toInt()
            atmospherePaint.alpha = s.alpha.toInt().coerceIn(0, 255)
            canvas.drawCircle(s.cx, s.cy, s.r, atmospherePaint)
        }
        atmospherePaint.alpha = 255
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
     * Fading motion trail for a physics-driven layer with [OverlayLayer.trail]
     * set — [layer.trailPoints] are already-resolved world-fraction points,
     * oldest first, drawn as progressively more transparent line segments
     * ending at the current position. Called BEFORE [drawGmsOverlay] for
     * the same layer so the trail sits visually behind it.
     */
    private fun drawGmsTrail(canvas: Canvas, w: Int, h: Int, layer: ResolvedOverlay) {
        val pts = layer.trailPoints
        val baseColor = layer.color.toInt()
        val n = pts.size
        for (i in 0 until n - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            // Oldest segment most transparent, newest (closest to current
            // position) most opaque, capped below the layer's own opacity.
            val segProgress = (i + 1).toFloat() / (n - 1).toFloat()
            gmsShapePaint.shader = null
            gmsShapePaint.color = baseColor
            gmsShapePaint.alpha = combinedAlpha(baseColor, layer.opacity * segProgress * 0.6f)
            gmsShapePaint.style = Paint.Style.STROKE
            gmsShapePaint.strokeCap = Paint.Cap.ROUND
            gmsShapePaint.strokeWidth = (layer.radius ?: (layer.height ?: 0.015f)) * min(w, h) * 0.6f
            canvas.drawLine(x0 * w, y0 * h, x1 * w, y1 * h, gmsShapePaint)
        }
        gmsShapePaint.style = Paint.Style.FILL
    }

    /**
     * Draws one resolved motion-graphics overlay layer (text or shape).
     * Called once per active [ResolvedOverlay], inside the camera transform
     * — see the call site in [draw] for why these pan/zoom/shake with the
     * figure rather than staying screen-space like captions.
     *
     * [Paint.setShadowLayer]/[android.graphics.BlurMaskFilter]-based glow
     * draws correctly on a plain software `Canvas(Bitmap)` (what
     * [com.example.engine.VideoExporter] uses) and on [android.view.SurfaceView]'s
     * default `lockCanvas()` (what the live preview uses) — hardware
     * acceleration, where these APIs are unsupported, only applies to
     * `lockHardwareCanvas()`/View-layer rendering, neither of which is in
     * use here. Still flagged as NOT yet visually confirmed on-device —
     * same "believe the device over the reasoning" discipline as this
     * file's eyebrow-tilt note above.
     */
    private fun drawGmsOverlay(canvas: Canvas, w: Int, h: Int, layer: ResolvedOverlay, appearance: AppearanceSettings) {
        if (layer.opacity <= 0.001f) return
        val minDim = min(w, h).toFloat()

        canvas.save()
        canvas.translate(w * layer.x, h * layer.y)
        if (layer.rotationDeg != 0f) canvas.rotate(layer.rotationDeg)
        if (layer.scale != 1f) canvas.scale(layer.scale, layer.scale)

        when (layer.type) {
            "shape" -> drawGmsShape(canvas, w, h, minDim, layer)
            "text"  -> drawGmsText(canvas, w, h, layer)
            "figure" -> drawSecondaryFigure(canvas, w, h, layer, appearance)
        }

        canvas.restore()
    }

    private fun combinedAlpha(baseColor: Int, opacity: Float): Int =
        combinedAlphaChannel(baseColor, opacity)

    private fun drawGmsShape(canvas: Canvas, w: Int, h: Int, minDim: Float, layer: ResolvedOverlay) {
        val baseColor = layer.color.toInt()
        // Geometry now lives in computeOverlayShapeParts (companion, below) —
        // shared with the GLES export path (overlay shapes — V2_DECISIONS.md),
        // same reasoning as computeMouthGeometry's doc comment (Phase 3).
        val parts = computeOverlayShapeParts(layer, w, h, minDim)

        if (layer.glow) {
            gmsGlowPaint.color = layer.glowColor.toInt()
            gmsGlowPaint.alpha = combinedAlpha(layer.glowColor.toInt(), layer.opacity * 0.6f)
            gmsGlowPaint.maskFilter = android.graphics.BlurMaskFilter(
                (layer.glowRadius * minDim).coerceAtLeast(1f), android.graphics.BlurMaskFilter.Blur.NORMAL
            )
            for (part in parts) drawShapePart(canvas, part, gmsGlowPaint)
            gmsGlowPaint.maskFilter = null
        }

        gmsShapePaint.shader = if (layer.gradientColor != null) {
            val halfSpan = when (layer.shape) {
                "circle" -> (layer.radius ?: 0.1f) * minDim
                else     -> (layer.height ?: 0.15f) * h / 2f
            }
            LinearGradient(0f, -halfSpan, 0f, halfSpan, baseColor, layer.gradientColor.toInt(), Shader.TileMode.CLAMP)
        } else null
        gmsShapePaint.color = baseColor
        gmsShapePaint.alpha = combinedAlpha(baseColor, layer.opacity)
        for (part in parts) drawShapePart(canvas, part, gmsShapePaint)
    }

    /** Draws one already-computed [LocalShapePart] — the Canvas-specific half of what used to be [drawShapeGeometry]'s per-shape when-branch. */
    private fun drawShapePart(canvas: Canvas, part: LocalShapePart, paint: Paint) {
        when (part) {
            is LocalShapePart.Circle -> canvas.drawCircle(part.cx, part.cy, part.radius, paint)
            is LocalShapePart.Rect -> canvas.drawRect(
                part.cx - part.halfW, part.cy - part.halfH, part.cx + part.halfW, part.cy + part.halfH, paint
            )
            is LocalShapePart.Line -> {
                val prevStyle = paint.style
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = part.halfWidth * 2f
                canvas.drawLine(part.x1, part.y1, part.x2, part.y2, paint)
                paint.style = prevStyle
            }
            is LocalShapePart.Triangle -> {
                val prevStyle = paint.style
                paint.style = Paint.Style.FILL
                val path = android.graphics.Path()
                path.moveTo(part.x1, part.y1)
                path.lineTo(part.x2, part.y2)
                path.lineTo(part.x3, part.y3)
                path.close()
                canvas.drawPath(path, paint)
                paint.style = prevStyle
            }
        }
    }

    /**
     * Caches the auto-shrink computation from [drawGmsText]'s own doc
     * comment — a static text layer's final on-screen size can't change
     * frame-to-frame (text/fontSize/bold and the canvas's own dimensions
     * are all fixed for that layer's whole lifetime), so recomputing
     * [Paint.measureText] every single frame it was on screen was pure
     * waste. Keyed on every input the computation actually depends on;
     * bounded to guard against unbounded growth in the unlikely event a
     * script has an unusually large number of distinct text layers.
     */
    private fun cachedShrunkTextSize(text: String, fontSizeFraction: Float, bold: Boolean, w: Int, h: Int): Float {
        val key = "$text|$fontSizeFraction|$bold|$w|$h"
        textShrinkCache[key]?.let { return it }
        if (textShrinkCache.size > 200) textShrinkCache.clear()

        var textSize = h * fontSizeFraction
        gmsTextPaint.textSize = textSize
        val measuredWidth = gmsTextPaint.measureText(text)
        val maxWidth = w * 0.92f
        if (measuredWidth > maxWidth && measuredWidth > 0f) {
            textSize *= maxWidth / measuredWidth
        }
        textShrinkCache[key] = textSize
        return textSize
    }

    private fun drawGmsText(canvas: Canvas, w: Int, h: Int, layer: ResolvedOverlay) {
        val text = layer.text
        if (text.isNullOrBlank()) return
        val baseColor = layer.color.toInt()

        gmsTextPaint.isFakeBoldText = layer.bold
        gmsTextPaint.textAlign = when (layer.align) {
            "left"  -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else    -> Paint.Align.CENTER
        }

        // fontSize is a fraction of canvas HEIGHT (see OverlayLayer's doc
        // comment — deliberate, so text reads at a consistent relative
        // size across dual-aspect export's two resolutions). But height
        // alone doesn't bound WIDTH: the same height-fraction is a much
        // bigger fraction of a narrow portrait canvas's width than of a
        // wide landscape one, so a long word sized purely off height can
        // overflow portrait's edges even though it fits landscape fine —
        // confirmed on-device for "AMAZING!" specifically. Measure at the
        // height-driven size first, then shrink proportionally (never
        // enlarge) if it would exceed a safe margin of the actual canvas
        // width, so it never overflows on EITHER aspect ratio. Cached —
        // see cachedShrunkTextSize's doc comment for why recomputing this
        // every single frame was pure waste for a layer whose text/
        // fontSize/bold never change over its own lifetime.
        val textSize = cachedShrunkTextSize(text, layer.fontSize, layer.bold, w, h)
        gmsTextPaint.textSize = textSize

        gmsTextPaint.shader = if (layer.gradientColor != null) {
            val halfH = textSize / 2f
            LinearGradient(0f, -halfH, 0f, halfH, baseColor, layer.gradientColor.toInt(), Shader.TileMode.CLAMP)
        } else null
        gmsTextPaint.color = baseColor
        gmsTextPaint.alpha = combinedAlpha(baseColor, layer.opacity)

        if (layer.glow) {
            gmsTextPaint.setShadowLayer(
                (layer.glowRadius * h).coerceAtLeast(1f), 0f, 0f,
                Color.argb(combinedAlpha(layer.glowColor.toInt(), layer.opacity * 0.8f),
                    Color.red(layer.glowColor.toInt()), Color.green(layer.glowColor.toInt()), Color.blue(layer.glowColor.toInt()))
            )
        } else {
            gmsTextPaint.clearShadowLayer()
        }

        // drawText anchors at the baseline, not the visual vertical center —
        // offset so (0,0) (the layer's translated anchor point) reads as the
        // text's visual center, same reasoning as drawCaption's box math.
        val metrics = gmsTextPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, 0f, baselineOffset, gmsTextPaint)
    }

    /**
     * Draws a supporting, illustrative second figure for a `type: "figure"`
     * overlay layer — see [OverlayLayer]'s doc comment for why this is
     * deliberately simplified and fully self-contained (own local matrix
     * array, own Paints, no state shared with the main figure's FK pass
     * earlier in [draw]) rather than refactoring that function to be
     * reusable. Static pose (no interpolation), static expression-driven
     * face (no audio-driven mouth, no independent blink schedule).
     *
     * Drawn in LOCAL space — [drawGmsOverlay] has ALREADY applied
     * `canvas.translate(x,y)`/rotate/scale for this layer before
     * dispatching here, same as [drawGmsShape]/[drawGmsText] — the root
     * bone sits at local (0,0), not an absolute canvas position computed
     * again in here. [layer.scale] is likewise already baked into the
     * active canvas transform, so it is deliberately NOT multiplied in
     * again below.
     */
    private fun drawSecondaryFigure(canvas: Canvas, w: Int, h: Int, layer: ResolvedOverlay, appearance: AppearanceSettings) {
        val angles = layer.figurePoseAngles ?: return
        val minDim = min(w, h).toFloat()
        // Base size at layer.scale==1.0 — deliberately smaller than the
        // main figure's usual footprint, so a supporting figure reads as
        // secondary by default rather than competing with the main one.
        val figScale = minDim * 0.3f

        val bones = StickFigureRig.BONES
        val n = StickFigureRig.BONE_COUNT
        val localMatrices = secondaryFigureMatrices
        val pts = FloatArray(4)

        val baseColor = layer.color.toInt()
        val alpha = combinedAlpha(baseColor, layer.opacity)
        figureBonePaint.color = baseColor
        figureBonePaint.alpha = alpha
        figureBonePaint.strokeWidth = 0.06f * figScale
        figureHeadPaint.color = baseColor
        figureHeadPaint.alpha = alpha

        var headCx = 0f; var headCy = 0f; var headR = 0f

        for (i in 0 until n) {
            val bone = bones[i]
            val matrix = localMatrices[i]
            if (bone.parentId == null) {
                matrix.reset()
                matrix.preRotate(angles[i])
            } else {
                val pIdx = StickFigureRig.BONE_INDEX[bone.parentId] ?: continue
                matrix.set(localMatrices[pIdx])
                matrix.preTranslate(bones[pIdx].normalizedLength * figScale, 0f)
                matrix.preRotate(angles[i])
            }

            val lengthSF = bone.normalizedLength * figScale * (if (bone.isHeadBone) appearance.neckLengthMultiplier else 1f)
            pts[0] = 0f; pts[1] = 0f
            pts[2] = lengthSF; pts[3] = 0f
            matrix.mapPoints(pts)
            val startX = pts[0]; val startY = pts[1]
            val endX = pts[2]; val endY = pts[3]

            if (bone.isHeadBone) {
                headCx = endX; headCy = endY
                headR = bone.headNormalizedRadius * figScale
                canvas.drawCircle(headCx, headCy, headR, figureHeadPaint)
            } else {
                canvas.drawLine(startX, startY, endX, endY, figureBonePaint)
            }
        }

        if (headR > 0f) drawSecondaryFace(canvas, headCx, headCy, headR, layer.figureExpression, baseColor, alpha)
    }

    /**
     * Simple, static, expression-driven face for [drawSecondaryFigure] —
     * NOT a reuse of the main figure's audio-driven mouth/eye system
     * (there is no audio channel for a supporting figure to sync to).
     */
    private fun drawSecondaryFace(canvas: Canvas, cx: Float, cy: Float, r: Float, expression: Int, baseColor: Int, alpha: Int) {
        figureEyePaint.color = baseColor
        figureEyePaint.alpha = alpha
        val eyeRx = r * 0.12f
        val eyeRy = when (expression) {
            Expression.SQUINT -> eyeRx * 0.35f
            Expression.WIDE, Expression.HAPPY -> eyeRx * 1.3f
            else -> eyeRx
        }
        val eyeOffsetX = r * 0.35f
        val eyeOffsetY = -r * 0.1f
        canvas.drawOval(cx - eyeOffsetX - eyeRx, cy + eyeOffsetY - eyeRy, cx - eyeOffsetX + eyeRx, cy + eyeOffsetY + eyeRy, figureEyePaint)
        canvas.drawOval(cx + eyeOffsetX - eyeRx, cy + eyeOffsetY - eyeRy, cx + eyeOffsetX + eyeRx, cy + eyeOffsetY + eyeRy, figureEyePaint)

        figureMouthPaint.color = baseColor
        figureMouthPaint.alpha = alpha
        figureMouthPaint.strokeWidth = r * 0.08f
        val mouthY = cy + r * 0.4f
        val mouthHalfW = r * 0.3f
        when (expression) {
            Expression.HAPPY -> {
                val path = Path()
                path.moveTo(cx - mouthHalfW, mouthY)
                path.quadTo(cx, mouthY + r * 0.35f, cx + mouthHalfW, mouthY)
                canvas.drawPath(path, figureMouthPaint)
            }
            Expression.ANGRY, Expression.WORRIED -> {
                val path = Path()
                path.moveTo(cx - mouthHalfW, mouthY + r * 0.15f)
                path.quadTo(cx, mouthY - r * 0.2f, cx + mouthHalfW, mouthY + r * 0.15f)
                canvas.drawPath(path, figureMouthPaint)
            }
            else -> canvas.drawLine(cx - mouthHalfW, mouthY, cx + mouthHalfW, mouthY, figureMouthPaint)
        }
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
        expression: Int,
        headScaleMultiplier: Float
    ) {
        // Geometry now lives in computeMouthGeometry (companion, below) —
        // shared with the GLES export path (Phase 3, V2_DECISIONS.md) so
        // there's exactly one implementation of this math, not two. See that
        // function's doc comment for the headScaleMultiplier-on-the-anchor
        // reasoning that used to live here.
        val g = computeMouthGeometry(hx, hy, nx, ny, r, mouthShape, mouthOpenness, expression, headScaleMultiplier)
        if (g.rotationDeg != 0f) canvas.save()
        if (g.rotationDeg != 0f) canvas.rotate(g.rotationDeg, g.cx, g.cy)
        canvas.drawOval(g.cx - g.halfWidth, g.cy - g.halfHeight, g.cx + g.halfWidth, g.cy + g.halfHeight, mouthPaint)
        if (g.rotationDeg != 0f) canvas.restore()
    }

    /**
     * Eyes, positioned using the same head-tip/neck-axis technique as
     * [drawMouth]. Their POSITION always rotated correctly with the head via
     * the perpendicular offset below — but until now their SHAPE stayed
     * screen-axis-aligned regardless of head rotation, which is exactly what
     * caused one eye to collapse to a sliver under real rotation (confirmed
     * via device video, not just static analysis — see V2_DECISIONS.md).
     * [OvalGeometry.rotationDeg] ([RigRenderer.headRotationDeg]) now rotates
     * the shape itself to match, so eyes turn rigidly with the head.
     *
     * [openness] is resolved analytically upstream in [PlaybackEngine] from
     * BOTH natural idle blinking and any AI-scripted
     * [com.example.data.AnimScript.blinkEvents]; this function doesn't know or
     * care which triggered it, it just draws the current openness value.
     *
     * [headScaleMultiplier] is needed for the same anchor-scaling reason as
     * [drawMouth] — see that function's doc comment. Eye SIZE/spacing already
     * scaled correctly with [r] before this fix; only the anchor position
     * (where the eyes sit relative to the head circle) was the bug, because
     * it was computed from the head bone's fixed FK length rather than the
     * visually-scaled radius.
     *
     * Eyebrows are NOT rig bones (the rig has none) — they're synthetic lines
     * drawn only for [Expression.WORRIED]/[Expression.ANGRY], per
     * [Expression]'s own doc comment. Their tilt direction was derived
     * analytically from the perpendicular/"up" basis below, the same way the
     * three newest poses (present/point_self/open_hands) were computed — now
     * visually confirmed correct via device video review (worried tilts
     * inner-up, angry tilts inner-down), no sign flip needed.
     */
    private fun drawEyes(
        canvas: Canvas,
        hx: Float, hy: Float,
        nx: Float, ny: Float,
        r: Float,
        openness: Float,
        expression: Int,
        headScaleMultiplier: Float,
        appearance: AppearanceSettings
    ) {
        // Geometry now lives in computeEyeGeometry (companion, below) — see
        // computeMouthGeometry's doc comment for why this is shared with the
        // GLES export path rather than duplicated.
        val eyes = computeEyeGeometry(hx, hy, nx, ny, r, openness, expression, headScaleMultiplier, appearance)
        val rot = eyes.left.rotationDeg   // same for both eyes — one head, one rotation

        if (rot != 0f) canvas.save()
        if (rot != 0f) canvas.rotate(rot, eyes.left.cx, eyes.left.cy)
        canvas.drawOval(
            eyes.left.cx - eyes.left.halfWidth, eyes.left.cy - eyes.left.halfHeight,
            eyes.left.cx + eyes.left.halfWidth, eyes.left.cy + eyes.left.halfHeight, eyePaint
        )
        if (rot != 0f) canvas.restore()

        if (rot != 0f) canvas.save()
        if (rot != 0f) canvas.rotate(rot, eyes.right.cx, eyes.right.cy)
        canvas.drawOval(
            eyes.right.cx - eyes.right.halfWidth, eyes.right.cy - eyes.right.halfHeight,
            eyes.right.cx + eyes.right.halfWidth, eyes.right.cy + eyes.right.halfHeight, eyePaint
        )
        if (rot != 0f) canvas.restore()

        val brows = computeEyebrowGeometry(eyes, expression)
        if (brows != null) drawEyebrows(canvas, brows)
    }

    private fun drawEyebrows(canvas: Canvas, brows: EyebrowGeometry) {
        // Stroke width scales with eyeRadius (which itself scales with the
        // head's current drawn radius) rather than a fixed absolute value —
        // see the note where eyebrowPaint.color is set for why a fixed value
        // looked wrong at non-default headScaleMultiplier. halfWidth*2 here
        // because Paint.strokeWidth is a full width, not a half-width.
        eyebrowPaint.strokeWidth = brows.halfWidth * 2f
        canvas.drawLine(brows.left.sx, brows.left.sy, brows.left.ex, brows.left.ey, eyebrowPaint)
        canvas.drawLine(brows.right.sx, brows.right.sy, brows.right.ex, brows.right.ey, eyebrowPaint)
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
        computeFkMatrices(angles, rootX, rootY, scale, mats)
        for (i in 0 until n) {
            val bone = bones[i]
            pts[0] = 0f; pts[1] = 0f
            pts[2] = bone.normalizedLength * scale; pts[3] = 0f
            mats[i].mapPoints(pts)
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

    /**
     * One piece of a resolved overlay's LOCAL-space shape geometry (before
     * position/rotation/scale is applied) — an overlay shape can be more
     * than one part (e.g. "cross" = 2 [Rect]s, "arrow" = 1 [Line] + 1
     * [Triangle]), which is why [computeOverlayShapeParts] returns a list
     * rather than a single value. All coordinates are canvas-pixel units
     * relative to the layer's own local origin (0,0) — exactly the space
     * [drawGmsOverlay]'s `canvas.translate`/`rotate`/`scale` already puts
     * the Canvas in before calling [drawShapePart]; the GLES path applies
     * the equivalent transform explicitly via [localToWorld] instead.
     *
     * Deliberately a DIRECT nested class of [RigRenderer], NOT nested
     * inside its `companion object` below — a class nested inside a
     * companion object is not accessible as `RigRenderer.LocalShapePart`
     * from another file the way a companion FUNCTION like
     * [computeOverlayShapeParts] is; it needs full `RigRenderer.Companion.
     * LocalShapePart` qualification instead, which every other GLES
     * DrawCommand-style sealed class in this codebase (see
     * `GlesFigureFrame.DrawCommand` etc.) deliberately avoids by living
     * directly in the class, not its companion. This one didn't, at first
     * — CI's compile step (not this sandbox, which has no Kotlin compiler)
     * caught it as "Unresolved reference" from `GlesFigureFrame.kt`. Fixed
     * by moving it here, matching the pattern everywhere else already
     * gets right.
     */
    sealed class LocalShapePart {
        data class Rect(val cx: Float, val cy: Float, val halfW: Float, val halfH: Float) : LocalShapePart()
        data class Circle(val cx: Float, val cy: Float, val radius: Float) : LocalShapePart()
        /** [halfWidth] is HALF the stroke width — see call sites for why (Paint.strokeWidth is a full width, not a half-width). */
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val halfWidth: Float) : LocalShapePart()
        data class Triangle(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : LocalShapePart()
    }

    /**
     * Camera pan/zoom/shake, as a single already-composed affine transform:
     * `screenX = worldX * zoom + offsetX` (same for Y). Pure pixel-space
     * arithmetic, no Canvas/Matrix dependency, so the GLES path (camera
     * phase, V2_DECISIONS.md) can apply it directly to already-resolved
     * geometry instead of relying on a matrix stack the way [draw] does via
     * `canvas.scale`/`canvas.translate`.
     *
     * Verified algebraically equivalent to [draw]'s own
     * `canvas.scale(zoom,zoom,cw/2,ch/2)` + `canvas.translate(tx,ty)`
     * composition (with `tx,ty` divided by zoom, per that call site's own
     * comment) via a Python matrix-stack simulation before this was
     * written — see V2_DECISIONS.md's History entry for the camera phase
     * for the derivation and the cross-check.
     *
     * Deliberately a DIRECT nested class of [RigRenderer], NOT nested
     * inside its `companion object` below, for the exact reason
     * [LocalShapePart]'s own doc comment above already documents in full —
     * that entry existed specifically so this wouldn't happen again, and
     * it happened anyway: this class shipped inside the companion object
     * on the first attempt, CI caught it the same way it caught
     * `LocalShapePart` originally ("Unresolved reference" from
     * `GlesFigureFrame.kt`, `GlesFrameRenderer.kt`), fixed here by the same
     * move. Worth being direct about rather than quietly fixing: reading
     * the lesson isn't the same as checking the file structure against it
     * before placing new code near an existing companion-object-adjacent
     * insertion point.
     */
    data class CameraTransform(val zoom: Float, val offsetX: Float, val offsetY: Float) {
        fun tx(worldX: Float) = worldX * zoom + offsetX
        fun ty(worldY: Float) = worldY * zoom + offsetY
        /** For radii/half-widths/stroke widths — camera zoom scales sizes uniformly, same as it scales position deltas. */
        fun tLen(worldLen: Float) = worldLen * zoom
    }

    companion object {
        // Any class/data class/sealed class declared directly inside this
        // companion object is NOT accessible as `RigRenderer.TypeName` from
        // another file the moment that file needs to name the type
        // explicitly (a function call with an inferred return type is
        // fine; an explicit type annotation elsewhere is not) — see
        // LocalShapePart's and CameraTransform's doc comments above for the
        // two times this has already broken CI. OvalGeometry/LineSegment/
        // EyeGeometry/EyebrowGeometry/RectGeom/TreeGeom/StarGeom/RainDrop
        // below all currently have this same latent placement and are only
        // "safe" today because nothing outside this file names any of them
        // explicitly yet. Not moved out in this pass — none of them are
        // broken right now, and moving all eight is a bigger, separate
        // change — but flagged here so the third occurrence of this isn't
        // a surprise to whoever hits it.
        /**
         * Pure FK matrix computation — no Canvas/Paint dependency, so [draw]
         * and the GLES export path (Phase 2, V2_DECISIONS.md) share exactly
         * one implementation instead of risking the two drifting apart. Also
         * now used by [worldEndpoints], which previously carried its own
         * independent copy of this same loop — a second, pre-existing
         * duplicate found while doing this extraction, fixed at the same time
         * rather than left in place once noticed.
         *
         * Fills [outMatrices] in place (must be sized
         * [StickFigureRig.BONE_COUNT]) — matches the existing "pre-allocate
         * once, reuse every frame" convention this class already follows for
         * its own `matrices` field, so calling this from a frame loop doesn't
         * introduce new per-frame allocation on either side.
         */
        fun computeFkMatrices(
            angles: FloatArray, rootX: Float, rootY: Float, scale: Float,
            outMatrices: Array<Matrix>
        ) {
            val rig = StickFigureRig
            val bones = rig.BONES
            for (i in 0 until rig.BONE_COUNT) {
                val bone = bones[i]
                val matrix = outMatrices[i]
                if (bone.parentId == null) {
                    matrix.reset()
                    matrix.postTranslate(rootX, rootY)
                    matrix.preRotate(angles[i])
                } else {
                    val pIdx = rig.BONE_INDEX[bone.parentId] ?: continue
                    matrix.set(outMatrices[pIdx])
                    matrix.preTranslate(bones[pIdx].normalizedLength * scale, 0f)
                    matrix.preRotate(angles[i])
                }
            }
        }

        /**
         * Deterministic pseudo-random shake jitter as a function of
         * [timeSec] — NOT [kotlin.random.Random], which would make preview
         * and an export diverge on every frame with shake active (export
         * re-samples at its own timestamps, so a stateful/live RNG can
         * never match what preview showed at the same timeSec). Shared by
         * [draw] (Canvas) and the GLES path so both compute the identical
         * offset — unlike most geometry in this file, this one MUST match
         * bit-for-bit, not just visually: any drift here is a preview/export
         * mismatch a user could actually notice on a shaken clip, not just
         * an internal inconsistency.
         */
        fun computeCameraShakeOffset(timeSec: Float, shakeIntensity: Float, minDim: Float): Pair<Float, Float> {
            val shakeMag = shakeIntensity.coerceIn(0f, 1f) * minDim * 0.03f
            if (shakeMag <= 0f) return 0f to 0f
            // Two different frequencies for X/Y so the offset isn't just diagonal.
            return sin(timeSec * 137.5f) * shakeMag to cos(timeSec * 93.7f) * shakeMag
        }

        /**
         * Builds the [CameraTransform] for one frame. [shakeX]/[shakeY] are
         * passed in already-resolved (from [computeCameraShakeOffset])
         * rather than recomputed here, so a caller that also needs the raw
         * shake offset for something else never has to call
         * [computeCameraShakeOffset] twice in the same frame.
         */
        fun computeCameraTransform(
            canvasW: Int, canvasH: Int,
            cameraZoom: Float, cameraPanX: Float, cameraPanY: Float,
            shakeX: Float, shakeY: Float
        ): CameraTransform {
            val zoom = cameraZoom.coerceAtLeast(0.1f)
            val offsetX = canvasW / 2f * (1f - zoom) + cameraPanX * canvasW + shakeX
            val offsetY = canvasH / 2f * (1f - zoom) + cameraPanY * canvasH + shakeY
            return CameraTransform(zoom, offsetX, offsetY)
        }

        /**
         * Center + half-extents of an oval, in the same canvas-pixel space
         * [computeFkMatrices] outputs. [rotationDeg] rotates the oval's own
         * shape around (cx,cy) — same sense as [android.graphics.Canvas.rotate]
         * (positive = clockwise in this Y-down pixel space) so Canvas and GLES
         * consumers can apply it identically. Defaulted to 0 (unrotated) for
         * every caller that has no orientation to track (trees, clouds, snow).
         */
        data class OvalGeometry(val cx: Float, val cy: Float, val halfWidth: Float, val halfHeight: Float, val rotationDeg: Float = 0f)

        /** One straight segment, in canvas-pixel space. */
        data class LineSegment(val sx: Float, val sy: Float, val ex: Float, val ey: Float)

        /** Both eyes plus the perpendicular/"up" basis eyebrow geometry needs — see [computeEyebrowGeometry]. */
        data class EyeGeometry(
            val left: OvalGeometry, val right: OvalGeometry,
            val perpX: Float, val perpY: Float, val upX: Float, val upY: Float
        )

        /** Both eyebrow line segments plus their shared half-width. */
        data class EyebrowGeometry(val left: LineSegment, val right: LineSegment, val halfWidth: Float)

        /**
         * How far the head bone (hx,hy = tip, nx,ny = origin) has rotated
         * from its own upright rest pose, in degrees, in the same sense as
         * [android.graphics.Canvas.rotate] (positive = clockwise, this is
         * Y-down pixel space) — shared by [computeMouthGeometry] and
         * [computeEyeGeometry] so both rotate identically with the head
         * rather than each deriving it separately and risking a mismatch.
         * At rest (bone pointing straight up, hy<ny, hx==nx) this is 0.
         */
        fun headRotationDeg(hx: Float, hy: Float, nx: Float, ny: Float): Float {
            val restDeg = -90.0
            val boneDeg = Math.toDegrees(kotlin.math.atan2((hy - ny).toDouble(), (hx - nx).toDouble()))
            return (boneDeg - restDeg).toFloat()
        }

        /**
         * Pure geometry for the mouth oval — no Canvas/Paint dependency, so
         * [drawMouth] (Canvas path) and [GlesFigureFrame.fromFkMatrices] (GLES
         * path, Phase 3) share exactly one implementation of this math instead
         * of risking the two drifting apart — same reasoning as
         * [computeFkMatrices]'s own doc comment.
         *
         * hx,hy = head circle center (bone tip); nx,ny = neck point (bone
         * origin); r = head's drawn radius (already includes
         * headScaleMultiplier). See [drawMouth]'s own doc comment for why
         * headScaleMultiplier is also applied to the anchor offset below, not
         * just to r.
         *
         * Anchor coefficient nudged from 0.42 to 0.36 — moves the mouth
         * slightly higher in the head (toward hx,hy) per direct feedback
         * that it read too low. Also now carries [OvalGeometry.rotationDeg]
         * ([headRotationDeg]) so it turns rigidly with the head instead of
         * staying screen-axis-aligned.
         */
        fun computeMouthGeometry(
            hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
            mouthShape: Int, mouthOpenness: Float, expression: Int,
            headScaleMultiplier: Float
        ): OvalGeometry {
            // Anchor coefficient history: 0.42 (original) -> 0.36 (first
            // upward nudge) -> 0.24 (tried, reverted) -> back to 0.36.
            //
            // 0.24 was computed from a real exported frame and looked
            // correct on paper, but the NEXT device video showed it
            // overlapping the eyes outright (measured: mouth top above
            // eye bottom by ~12px at that frame's mouth-openness). Traced
            // this to a real constraint, not a bad guess: between the
            // eyes' own bottom edge (~655) and the chin (~702) there's
            // only ~47px of room total, and the mouth itself needs ~37px
            // of that just for its own height at a fairly open (not even
            // fully-open) moment -- leaving only ~10px of slack to
            // position it anywhere in the gap at all. 0.36 sits almost
            // exactly in the middle of that 10px window (measured: +5px
            // clear of the eyes, +5px clear of the chin, at that same
            // moment's mouth height) -- it was already close to the best
            // available position given the mouth's current max size,
            // not actually the source of whatever looked "too low."
            // Going meaningfully higher than ~0.32-0.34 isn't safe
            // without also shrinking the mouth's max height (hFrac below)
            // or the eyes -- a bigger, more deliberate call than
            // repositioning alone, not made unilaterally a third time.
            val cx = hx + (nx - hx) * 0.36f * headScaleMultiplier
            var cy = hy + (ny - hy) * 0.36f * headScaleMultiplier

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
            return OvalGeometry(cx, cy, hw, hh, headRotationDeg(hx, hy, nx, ny))
        }

        /**
         * Pure geometry for both eyes — see [computeMouthGeometry]'s doc
         * comment for why this is shared rather than duplicated. Mirrors
         * [drawEyes] exactly, including the perpendicular-to-head-neck-axis
         * technique that makes eye separation follow head tilt for free.
         */
        fun computeEyeGeometry(
            hx: Float, hy: Float, nx: Float, ny: Float, r: Float,
            openness: Float, expression: Int, headScaleMultiplier: Float,
            appearance: AppearanceSettings
        ): EyeGeometry {
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

            // Root cause of the sliver-eye bug under rotation: this oval's
            // shape was always screen-axis-aligned (halfWidth horizontal,
            // halfHeight vertical) even though leftX/Y and rightX/Y already
            // correctly rotate around the head via perpX/perpY. At rest the
            // two happen to line up, so it looked fine; under real rotation
            // the shape's true (perpX,perpY)-aligned orientation and its
            // drawn (screen-axis) orientation diverge, and the GLES SDF
            // test — which assumes the shape's local axes ARE screen axes —
            // ends up rejecting most of one eye's footprint. Passing
            // rotationDeg through fixes both paths at once, since it's the
            // same [OvalGeometry] both consume.
            val rotationDeg = headRotationDeg(hx, hy, nx, ny)

            return EyeGeometry(
                left  = OvalGeometry(leftX, leftY, baseEyeRadius, eyeRadiusY, rotationDeg),
                right = OvalGeometry(rightX, rightY, baseEyeRadius, eyeRadiusY, rotationDeg),
                perpX = perpX, perpY = perpY, upX = upX, upY = upY
            )
        }

        /**
         * Pure geometry for the eyebrows — see [computeMouthGeometry]'s doc
         * comment for why this is shared. Returns null for any expression
         * other than WORRIED/ANGRY, mirroring [drawEyes]'s own condition for
         * calling [drawEyebrows] at all — eyebrows aren't rig bones, they're
         * synthetic and only drawn for those two expressions.
         */
        fun computeEyebrowGeometry(eyes: EyeGeometry, expression: Int): EyebrowGeometry? {
            if (expression != Expression.WORRIED && expression != Expression.ANGRY) return null

            val eyeRadius = eyes.left.halfWidth   // baseEyeRadius, same magnitude for both eyes
            val browHeight  = eyeRadius * 1.8f
            val browHalfLen = eyeRadius * 1.0f
            val innerLift = eyeRadius * (if (expression == Expression.WORRIED) 0.55f else -0.55f)
            val perpX = eyes.perpX; val perpY = eyes.perpY
            val upX = eyes.upX; val upY = eyes.upY

            val left = LineSegment(
                eyes.left.cx - perpX * browHalfLen + upX * browHeight,
                eyes.left.cy - perpY * browHalfLen + upY * browHeight,
                eyes.left.cx + perpX * browHalfLen + upX * (browHeight + innerLift),
                eyes.left.cy + perpY * browHalfLen + upY * (browHeight + innerLift)
            )
            val right = LineSegment(
                eyes.right.cx + perpX * browHalfLen + upX * browHeight,
                eyes.right.cy + perpY * browHalfLen + upY * browHeight,
                eyes.right.cx - perpX * browHalfLen + upX * (browHeight + innerLift),
                eyes.right.cy - perpY * browHalfLen + upY * (browHeight + innerLift)
            )
            // eyebrowPaint.strokeWidth = eyeRadius * 0.35f in the Canvas path — half of that for a half-width.
            return EyebrowGeometry(left, right, eyeRadius * 0.175f)
        }

        /** Axis-aligned rect, in canvas-pixel space. */
        data class RectGeom(val l: Float, val t: Float, val r: Float, val b: Float)

        /** A tree's canopy (circle) and trunk (thin rect). */
        data class TreeGeom(val canopy: OvalGeometry, val trunk: RectGeom)

        /** Sun (circle) + a few umbrella silhouettes (reusing TreeGeom's own
         *  canopy+pole shape, just tuned proportions — see
         *  computeBeachElements' doc comment for why a new geometry type
         *  wasn't introduced for this). */
        data class BeachGeom(val sun: OvalGeometry, val umbrellas: List<TreeGeom>)

        /** One star: position, radius, and its already-twinkle-resolved alpha (0-255). */
        data class StarGeom(val cx: Float, val cy: Float, val r: Float, val alpha: Float)

        /** One rain streak, as a line segment. */
        data class RainDrop(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

        /**
         * Enforces a minimum 50° hue separation from [figureColor] and caps
         * saturation at 0.45 — a scripted scene colour should never visually
         * compete with or blend into the figure. Not reliant on AI prompt
         * guidance; enforced here unconditionally. See V2_DECISIONS.md. Moved
         * here (Phase 4) from instance scope for the same reason as every
         * other compute* function in this object — shared by the GLES path.
         */
        fun constrainSceneColor(base: Long, figureColor: Long, alpha: Int = 0xFF): Int {
            val baseHsv = FloatArray(3)
            android.graphics.Color.colorToHSV((base or 0xFF000000L).toInt(), baseHsv)
            val figHsv = FloatArray(3)
            android.graphics.Color.colorToHSV((figureColor or 0xFF000000L).toInt(), figHsv)
            var hueDiff = kotlin.math.abs(baseHsv[0] - figHsv[0])
            if (hueDiff > 180f) hueDiff = 360f - hueDiff
            if (hueDiff < 50f) baseHsv[0] = (figHsv[0] + 50f) % 360f
            baseHsv[1] = baseHsv[1].coerceAtMost(0.45f)
            return android.graphics.Color.HSVToColor(alpha, baseHsv)
        }

        /** Positive-only modulo (Kotlin's `%` can return negative for a negative dividend, which a wrap coordinate must never do). */
        fun wrapCoord(x: Float, span: Float): Float {
            val wrapped = x % span
            return if (wrapped < 0f) wrapped + span else wrapped
        }

        /**
         * Mountain skyline as a single closed polygon, returned as a flat
         * (x0,y0,x1,y1,...) array in the exact order the original
         * [android.graphics.Path] built it — a flat bottom rect with a
         * zigzag ridge on top. Both consumers draw it as a FAN from point 0
         * (Canvas: [android.graphics.Path.close] on a moveTo/lineTo chain
         * IS a fan under the hood for a simple polygon; GLES: explicit
         * GL_TRIANGLE_FAN) — this is a correct triangulation because the
         * polygon is star-shaped from its bottom-left corner (a flat base
         * under a monotonic ridge), not a general-purpose polygon
         * triangulator. Worth knowing if a future shape here is ever
         * concave from that corner - this specific shape isn't.
         */
        fun computeMountainPolygon(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): FloatArray {
            val horizonPx = h * horizonYFraction
            val sway = kotlin.math.sin(timeSec * 0.05f) * w * 0.015f
            val peakCount = 4
            val peakW = w.toFloat() / (peakCount - 1)
            val pts = ArrayList<Float>((peakCount * 4 + 6))
            pts.add(-w * 0.2f + sway); pts.add(horizonPx)
            for (i in 0 until peakCount) {
                val x = -w * 0.2f + i * peakW * 1.4f + sway
                val peakHeight = horizonPx * (0.35f + 0.15f * ((i * 37) % 3))
                pts.add(x + peakW * 0.7f); pts.add(horizonPx - peakHeight)
                pts.add(x + peakW * 1.4f); pts.add(horizonPx)
            }
            pts.add(w * 1.2f + sway); pts.add(horizonPx)
            pts.add(w * 1.2f + sway); pts.add(horizonPx + h * 0.01f)
            pts.add(-w * 0.2f + sway); pts.add(horizonPx + h * 0.01f)
            return pts.toFloatArray()
        }

        fun computeCityBuildings(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): List<RectGeom> {
            val horizonPx = h * horizonYFraction
            val sway = kotlin.math.sin(timeSec * 0.08f + 1f) * w * 0.01f
            val buildingCount = 8
            val bw = w.toFloat() / buildingCount
            val list = ArrayList<RectGeom>(buildingCount)
            for (i in 0 until buildingCount) {
                val bh = horizonPx * (0.25f + 0.35f * ((i * 53) % 5) / 5f)
                val x = i * bw + sway
                list += RectGeom(x, horizonPx - bh, x + bw * 0.8f, horizonPx)
            }
            return list
        }

        fun computeTreePositions(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): List<TreeGeom> {
            val horizonPx = h * horizonYFraction
            val treeCount = 6
            val spacing = w.toFloat() / treeCount
            val list = ArrayList<TreeGeom>(treeCount)
            for (i in 0 until treeCount) {
                val r = horizonPx * (0.10f + 0.04f * ((i * 29) % 3))
                // Per-tree phase offset (i * 0.9) so trees sway out of
                // sync with each other rather than all in lockstep.
                val sway = kotlin.math.sin(timeSec * 0.6f + i * 0.9f) * (r * 0.12f)
                val cx = spacing * i + spacing * 0.5f + sway
                list += TreeGeom(
                    canopy = OvalGeometry(cx, horizonPx - r, r, r),
                    trunk  = RectGeom(cx - r * 0.08f, horizonPx - r, cx + r * 0.08f, horizonPx)
                )
            }
            return list
        }

        /** Each cloud is 3 overlapping puffs (circles); outer list = one entry per cloud. */
        fun computeCloudPositions(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): List<List<OvalGeometry>> {
            val horizonPx = h * horizonYFraction
            val cloudCount = 4
            val spacing = w.toFloat() / cloudCount
            val driftSpeed = w * 0.008f   // px/sec — slow, continuous, one direction
            val span = w + spacing        // wrap span leaves one cloud-spacing of margin off-screen on each side
            val list = ArrayList<List<OvalGeometry>>(cloudCount)
            for (i in 0 until cloudCount) {
                val baseCx = spacing * i + spacing * 0.5f
                val cx = wrapCoord(baseCx + timeSec * driftSpeed, span) - spacing * 0.5f
                val cy = horizonPx * (0.15f + 0.10f * ((i * 41) % 3))
                val r = w * 0.05f
                list += listOf(
                    OvalGeometry(cx - r, cy, r, r),
                    OvalGeometry(cx + r * 0.6f, cy - r * 0.3f, r * 0.8f, r * 0.8f),
                    OvalGeometry(cx + r * 1.4f, cy, r * 0.7f, r * 0.7f)
                )
            }
            return list
        }

        /**
         * A handful of rectangular furniture silhouettes sitting at the
         * floor line — same "objects standing at horizonPx" composition as
         * [computeCityBuildings], just shorter/wider proportions (furniture,
         * not a skyline) and fewer of them (3, not 8) so they read as
         * discrete pieces rather than a repeating pattern. Widths/heights
         * are literal per-index fractions rather than a formula like
         * city's — there's no natural single curve for "a room's worth of
         * furniture" the way there is for a repeating skyline, so this is
         * just 3 explicitly-placed pieces, checked by hand (and in Python,
         * before writing this) to not overlap and to stay on-canvas across
         * landscape/portrait/ultrawide/square and horizon fractions 0.3-0.9.
         * Tiny sway added purely for consistency with every other scene
         * shape here having *some* motion (see PROMPT_CONSIDERATIONS.md's
         * "constant subtle motion" line) — furniture doesn't really sway,
         * but literally rigid furniture was a bigger departure from every
         * other shape's behavior than a barely-perceptible common sway is.
         */
        fun computeRoomFurniture(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): List<RectGeom> {
            val horizonPx = h * horizonYFraction
            val sway = kotlin.math.sin(timeSec * 0.04f + 2f) * w * 0.005f
            val heightFractions = floatArrayOf(0.35f, 0.55f, 0.30f)
            val widthFractions  = floatArrayOf(0.22f, 0.12f, 0.28f)
            val leftFractions   = floatArrayOf(0.08f, 0.42f, 0.62f)
            val list = ArrayList<RectGeom>(3)
            for (i in 0 until 3) {
                val fh = horizonPx * heightFractions[i]
                val x = w * leftFractions[i] + sway
                val fw = w * widthFractions[i]
                list += RectGeom(x, horizonPx - fh, x + fw, horizonPx)
            }
            return list
        }

        /**
         * Sun (fixed, no sway/drift — reads as a distant fixed object,
         * unlike clouds' continuous drift) + 3 umbrella silhouettes at the
         * horizon, built from [TreeGeom] itself (canopy circle + thin pole)
         * rather than a new geometry type: [drawSceneShape]'s Canvas path
         * draws a tree's canopy with `canvas.drawCircle(..., halfWidth,
         * ...)` and GLES's `SceneDrawCommand.Circle` similarly only reads
         * `halfWidth` — [OvalGeometry.halfHeight] is silently ignored by
         * both, so an attempt at a flatter/wider elliptical umbrella canopy
         * (tried first) would've silently rendered as a plain circle
         * anyway. A plain circle reads fine as an umbrella top over a
         * pole, so this reuses TreeGeom's exact composition instead of
         * introducing a new geometry type with its own from-scratch
         * Canvas+GLES parity work.
         *
         * Sizing is deliberately based on `min(w, h)`, not `horizonPx` the
         * way city/tree height is — verified numerically (Python) that
         * scaling the umbrella canopy off horizonPx overflows canvas width
         * in portrait orientation at a low horizon line (horizonPx, based
         * on the LARGER dimension h, can exceed w there), which a
         * horizontally-scaled quantity must never be derived from.
         */
        fun computeBeachElements(w: Int, h: Int, horizonYFraction: Float, timeSec: Float): BeachGeom {
            val horizonPx = h * horizonYFraction
            val minDim = min(w, h).toFloat()
            val sunR = minDim * 0.05f
            val sun = OvalGeometry(w * 0.80f, horizonPx * 0.30f, sunR, sunR)
            val umbrellaCount = 3
            val spacing = w.toFloat() / umbrellaCount
            val list = ArrayList<TreeGeom>(umbrellaCount)
            for (i in 0 until umbrellaCount) {
                val poleH = horizonPx * 0.12f
                val canopyR = minDim * (0.05f + 0.02f * ((i * 31) % 3))
                val sway = kotlin.math.sin(timeSec * 0.5f + i * 1.1f) * (canopyR * 0.08f)
                val cx = spacing * i + spacing * 0.5f + sway
                list += TreeGeom(
                    canopy = OvalGeometry(cx, horizonPx - poleH - canopyR, canopyR, canopyR),
                    trunk  = RectGeom(cx - canopyR * 0.06f, horizonPx - poleH, cx + canopyR * 0.06f, horizonPx)
                )
            }
            return BeachGeom(sun, list)
        }

        /**
         * Radius/width constants for stars, snow, and rain (here and in
         * [computeSnowFlakes] / [drawAtmosphere]'s RAIN case / GLES's
         * matching atmosphere block) were flat hardcoded pixel values until
         * this fix — invisible at real export resolution (1920x1088: a 2-3px
         * circle reads as a barely-visible speck, indistinguishable from
         * noise), and never checked against the canvas size at all. Tied to
         * minDim now, the same way every other proportional size in this
         * file already works (see boneStrokeNormalized/jointRadiusNormalized
         * usage elsewhere). Found while fixing snow: rain's stroke width was
         * ALSO a flat constant, AND Canvas/GLES disagreed with each other on
         * its value (2f vs 1f) — an unrelated pre-existing mismatch, fixed
         * alongside this since it's the same root cause. The fraction values
         * chosen (0.003 stars, 0.005 snow, 0.0018 rain) are a reasoned
         * guess, not a device-confirmed size — worth a look on the next
         * device check, not assumed correct on sight.
         */
        fun computeStarPositions(w: Int, h: Int, timeSec: Float): List<StarGeom> {
            val stars = 40
            val starRadius = min(w, h).toFloat() * 0.003f
            val list = ArrayList<StarGeom>(stars)
            for (i in 0 until stars) {
                // Fixed pseudo-random grid — stars don't move, just a light static-time twinkle.
                val x = ((i * 6151) % w.coerceAtLeast(1)).toFloat()
                val y = ((i * 3079) % (h / 2).coerceAtLeast(1)).toFloat()
                val twinkle = 0.5f + 0.5f * kotlin.math.sin(timeSec * 2f + i)
                val alpha = (140 + 100 * twinkle).coerceIn(0f, 255f)
                list += StarGeom(x, y, starRadius, alpha)
            }
            return list
        }

        fun computeRainDrops(w: Int, h: Int, timeSec: Float): List<RainDrop> {
            val cols = 24
            val speed = 900f // px/sec, purely deterministic from timeSec
            val list = ArrayList<RainDrop>(cols)
            for (i in 0 until cols) {
                val baseX = (i.toFloat() / cols) * w
                val x = (baseX + (i * 17) % 40)
                val y = ((timeSec * speed) + i * 53f).let { it % (h + 40f) } - 20f
                list += RainDrop(x, y, x - 8f, y + 20f)
            }
            return list
        }

        /** See [computeStarPositions]'s doc comment for the resolution-scaling fix this shares. */
        fun computeSnowFlakes(w: Int, h: Int, timeSec: Float): List<OvalGeometry> {
            val flakes = 30
            val speed = 120f
            val snowRadius = min(w, h).toFloat() * 0.005f
            val list = ArrayList<OvalGeometry>(flakes)
            for (i in 0 until flakes) {
                val baseX = (i.toFloat() / flakes) * w
                val drift = kotlin.math.sin(timeSec * 0.6f + i) * 15f
                val x = baseX + drift
                val y = ((timeSec * speed) + i * 71f).let { it % (h + 20f) } - 10f
                list += OvalGeometry(x, y, snowRadius, snowRadius)
            }
            return list
        }

        /**
         * Pure geometry for a resolved overlay's shape — no Canvas/Paint
         * dependency, shared between [RigRenderer.drawGmsShape] (Canvas) and
         * the GLES export path (overlay shapes — V2_DECISIONS.md), same
         * reasoning as [computeMouthGeometry]'s doc comment. Every constant
         * here is unchanged from the pre-extraction Canvas code — this is a
         * relocation of the math, not a rewrite of it.
         */
        fun computeOverlayShapeParts(layer: ResolvedOverlay, w: Int, h: Int, minDim: Float): List<LocalShapePart> {
            return when (layer.shape) {
                "circle" -> listOf(LocalShapePart.Circle(0f, 0f, (layer.radius ?: 0.1f) * minDim))
                "line" -> {
                    val strokeHalfWidth = (layer.height ?: 0.01f) * h / 2f
                    val halfLen = (layer.width ?: 0.2f) * w / 2f
                    listOf(LocalShapePart.Line(-halfLen, 0f, halfLen, 0f, strokeHalfWidth))
                }
                "arrow" -> {
                    // Points along +X in local space — the caller has already
                    // rotated to rotationDeg (velocity direction, for a
                    // physics-driven arrow) before this geometry is applied.
                    val strokeW = (layer.height ?: 0.012f) * h
                    val halfLen = (layer.width ?: 0.2f) * w / 2f
                    val headLen = strokeW * 3f
                    val headHalfW = strokeW * 2f
                    listOf(
                        LocalShapePart.Line(-halfLen, 0f, halfLen - headLen, 0f, strokeW / 2f),
                        LocalShapePart.Triangle(halfLen, 0f, halfLen - headLen, -headHalfW, halfLen - headLen, headHalfW)
                    )
                }
                "cross" -> {
                    // Traditional Latin-cross proportions: crossbar sits about
                    // a third of the way down from the top, and spans about
                    // 60% of the total height — reuses width/height (arm
                    // thickness / overall height) rather than adding new
                    // fields, same "reuse what's there" approach as every
                    // other shape. Added directly in response to a real
                    // AI-generated script: a single rotated rect can only
                    // ever be one bar, never an actual cross.
                    val thickness = (layer.width ?: 0.03f) * w
                    val totalHeight = (layer.height ?: 0.2f) * h
                    val armSpan = totalHeight * 0.6f
                    val vertHalf = totalHeight / 2f
                    val crossbarY = -vertHalf + totalHeight * 0.32f
                    listOf(
                        LocalShapePart.Rect(0f, 0f, thickness / 2f, vertHalf),
                        LocalShapePart.Rect(0f, crossbarY, armSpan / 2f, thickness / 2f)
                    )
                }
                else -> { // "rect"
                    val halfW = (layer.width ?: 0.3f) * w / 2f
                    val halfH = (layer.height ?: 0.15f) * h / 2f
                    listOf(LocalShapePart.Rect(0f, 0f, halfW, halfH))
                }
            }
        }

        /**
         * Transforms a LOCAL-space point (canvas-pixel units, relative to a
         * layer's own origin) into WORLD canvas-pixel space, given that
         * layer's resolved origin/rotation/scale — the explicit-math GLES
         * equivalent of `canvas.translate(originX, originY)` +
         * `canvas.rotate(rotationDeg)` + `canvas.scale(scale, scale)`,
         * applied in that same order (scale, then rotate, then translate,
         * reading right-to-left through the matrix composition Canvas
         * builds). Standard 2D rotation matrix; not yet visually confirmed
         * on-device for a NON-ZERO rotation specifically (0° rotation is the
         * overwhelmingly common case and is trivially correct either way) —
         * same "believe the device over the reasoning" discipline this file
         * already applies elsewhere.
         */
        fun localToWorld(lx: Float, ly: Float, originX: Float, originY: Float, rotationDeg: Float, scale: Float): Pair<Float, Float> {
            val sx = lx * scale
            val sy = ly * scale
            if (rotationDeg == 0f) return (originX + sx) to (originY + sy)
            val rad = Math.toRadians(rotationDeg.toDouble())
            val cosR = kotlin.math.cos(rad).toFloat()
            val sinR = kotlin.math.sin(rad).toFloat()
            val rx = sx * cosR - sy * sinR
            val ry = sx * sinR + sy * cosR
            return (originX + rx) to (originY + ry)
        }

        /** Shared by [RigRenderer.combinedAlpha] and the GLES overlay path — see that function's doc comment for why this moved here (Phase 3/4 precedent). */
        fun combinedAlphaChannel(baseColor: Int, opacity: Float): Int =
            (android.graphics.Color.alpha(baseColor) * opacity).toInt().coerceIn(0, 255)
    }
}
