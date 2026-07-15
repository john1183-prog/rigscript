package com.example.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.data.AmplitudeSettings
import com.example.data.AppearanceSettings
import com.example.data.ReferenceOverlay
import com.example.engine.*
import kotlin.math.*

class AnimationSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val engine     = PlaybackEngine()
    private val renderer   = RigRenderer()
    private var appearance = AppearanceSettings()
    private var isPlaying  = false

    // V2 — manual reference overlay. Bitmap is decoded once here (not per
    // frame, not inside RigRenderer, which must stay allocation-free in its
    // hot draw loop) and re-decoded only when the overlay's imagePath changes.
    private var referenceOverlay: ReferenceOverlay = ReferenceOverlay()
    @Volatile private var referenceOverlayBitmap: Bitmap? = null
    private var loadedOverlayImagePath: String? = null

    // B1: Use SystemClock.elapsedRealtime() — monotonic, never jumps backward
    // on clock sync or forward on DST. System.currentTimeMillis() could produce
    // a negative dt before coerceIn() caught it, freezing animation for that frame.
    private var lastTickMs = 0L

    @Volatile private var audioPlayer: AudioPlayer? = null

    private val renderThread  = HandlerThread("RigRenderThread").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private var surfaceReady  = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()   // B1: monotonic clock
            val dt  = if (lastTickMs == 0L) 0.016f else ((now - lastTickMs) / 1000f)
            lastTickMs = now

            if (isPlaying) {
                val player = audioPlayer
                val amp = if (player != null) {
                    player.updateAmplitude()
                    engine.rawMouthShape = player.currentMouthShape
                    player.currentAmplitude
                } else 0f
                engine.tick(dt.coerceIn(0f, 0.05f), amp)
            }

            drawFrame()
            renderHandler.postDelayed(this, 16)
        }
    }

    // ── Touch state for pose editor ───────────────────────────────────────────

    var poseEditorMode: Boolean = false
    var onBoneAngleChanged: ((boneIndex: Int, totalAngle: Float) -> Unit)? = null
    private var dragBoneIdx:   Int = -1
    // B3: Cache FK endpoints from ACTION_DOWN so ACTION_MOVE uses the same geometry.
    private var dragEndpoints: Array<FloatArray>? = null

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true; lastTickMs = 0L
        renderHandler.post(frameRunnable)
    }
    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        renderHandler.removeCallbacks(frameRunnable)
    }

    // ── Playback API ──────────────────────────────────────────────────────────

    fun loadTimeline(
        keyframes: List<BakedKeyframe>,
        blinkTimes: List<Float> = emptyList(),
        durationSec: Float = 0f,
        fidgetEnvelope: FloatArray = FloatArray(0),
        captionCues: List<CaptionCue> = emptyList()
    ) {
        val snap = !isPlaying
        renderHandler.post {
            engine.loadTimeline(keyframes, snapToCurrentTime = snap)
            engine.loadBlinkSchedule(blinkTimes, durationSec)
            engine.loadFidgetSchedule(fidgetEnvelope, AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS)
            engine.loadCaptions(captionCues)
        }
    }

    /**
     * Sets the manual reference overlay (see [ReferenceOverlay]'s doc
     * comment). Re-decodes the bitmap only when [ReferenceOverlay.imagePath]
     * actually changes, on the render thread — decoding is I/O + allocation
     * and must never happen inside [drawFrame]'s hot path.
     */
    fun setReferenceOverlay(overlay: ReferenceOverlay) {
        referenceOverlay = overlay
        renderHandler.post {
            if (overlay.type == ReferenceOverlay.OverlayType.IMAGE && overlay.imagePath != null) {
                if (overlay.imagePath != loadedOverlayImagePath) {
                    referenceOverlayBitmap = runCatching { BitmapFactory.decodeFile(overlay.imagePath) }.getOrNull()
                    loadedOverlayImagePath = overlay.imagePath
                }
            } else {
                referenceOverlayBitmap = null
                loadedOverlayImagePath = null
            }
        }
    }

    /**
     * Re-derives the blink/fidget schedules from their current inputs
     * without touching the pose timeline. Needed because
     * [com.example.data.AmplitudeSettings] can change (e.g. via the inline
     * settings panel) while the SAME script/audio stays loaded — loadTimeline
     * alone wouldn't re-run in that case, since it's correctly gated on the
     * KEYFRAMES actually changing. Without this, toggling
     * idleFidgetEnabled/naturalBlinkEnabled mid-session would silently keep
     * using whatever schedule was computed under the OLD settings until some
     * unrelated script edit happened to reload the timeline too.
     */
    fun refreshBlinkAndFidgetSchedules(blinkTimes: List<Float>, durationSec: Float, fidgetEnvelope: FloatArray) {
        renderHandler.post {
            engine.loadBlinkSchedule(blinkTimes, durationSec)
            engine.loadFidgetSchedule(fidgetEnvelope, AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS)
        }
    }
    fun setAppearance(a: AppearanceSettings) { appearance = a }
    fun setAmplitudeSettings(s: AmplitudeSettings) { renderHandler.post { engine.amplitudeSettings = s } }
    fun setAudioPlayer(player: AudioPlayer?) { audioPlayer = player }
    fun play()  { isPlaying = true;  lastTickMs = 0L }
    fun pause() { isPlaying = false }
    fun stop()  { isPlaying = false; renderHandler.post { engine.reset() } }

    fun seekTo(timeSec: Float) {
        renderHandler.post { engine.seekTo(timeSec); isPlaying = false }
    }

    /** Current playback position in seconds. Read from the UI thread for the scrubber. */
    fun currentTimeSec(): Float = engine.currentTimeSec

    fun release() {
        renderHandler.removeCallbacks(frameRunnable)
        renderThread.quitSafely()
    }

    // ── Pose-editor API ───────────────────────────────────────────────────────

    fun applyPose(joints: Map<String, Float>) {
        val bones = StickFigureRig.BONES
        bones.forEachIndexed { i, bone ->
            engine.setBoneAngle(i, bone.defaultAngleDegrees + (joints[bone.id] ?: 0f))
        }
    }
    fun captureCurrentPose(): Map<String, Float> = engine.captureRelativeAngles()

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun drawFrame() {
        if (!surfaceReady) return
        val canvas: Canvas = try { holder.lockCanvas() ?: return } catch (e: Exception) { return }
        try {
            // Background is now painted by RigRenderer itself (V2 — solid or
            // gradient, oversized for camera safety), so no pre-fill here.
            renderer.draw(canvas, engine.currentAngles, appearance, width, height,
                mouthShape             = engine.currentMouthShape,
                mouthOpenness          = engine.currentAmplitude,
                expression             = engine.currentExpression,
                eyeOpenness            = engine.currentEyeOpenness,
                cameraZoom             = engine.currentCameraZoom,
                cameraPanX             = engine.currentCameraPanX,
                cameraPanY             = engine.currentCameraPanY,
                cameraShakeIntensity   = engine.currentShakeIntensity,
                skyColor               = engine.currentSkyColor,
                groundColor            = engine.currentGroundColor,
                horizonY               = engine.currentHorizonY,
                sceneShape             = engine.currentSceneShape,
                sceneAtmosphere        = engine.currentSceneAtmosphere,
                currentTimeSec         = engine.currentTimeSec,
                captionText            = engine.currentCaption,
                referenceOverlay       = referenceOverlay,
                referenceOverlayBitmap = referenceOverlayBitmap)
        } finally { holder.unlockCanvasAndPost(canvas) }
    }

    // ── Touch (pose editor mode) — B3 fix ─────────────────────────────────────
    //
    // Previously, pivot and nearest-bone positions were derived with a one-hop
    // formula: rootX + parentBone.length * cos(angles[parentIdx]). This ignored
    // all ancestors above the immediate parent, so lower_arm and lower_leg bones
    // had wrong pivots and the drag didn't track the finger.
    //
    // Fix: call renderer.worldEndpoints() on ACTION_DOWN to get FK-accurate
    // world positions for every bone, then use those in ACTION_MOVE for the pivot
    // and parent world rotation. The parent's world rotation is extracted from
    // its own endpoints via atan2(endY-startY, endX-startX) — no need to walk
    // the ancestor chain manually.

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!poseEditorMode) return false
        val tx = event.x; val ty = event.y
        val minDim = minOf(width, height).toFloat()
        val scale  = minDim * appearance.characterScale
        val rootX  = width  * appearance.rootAnchorX
        val rootY  = height * appearance.rootAnchorY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val eps = renderer.worldEndpoints(engine.currentAngles, scale, rootX, rootY)
                dragEndpoints = eps
                dragBoneIdx   = findNearestBone(tx, ty, eps)
            }
            MotionEvent.ACTION_MOVE -> {
                val eps = dragEndpoints ?: return true
                if (dragBoneIdx >= 0) {
                    val bone = StickFigureRig.BONES[dragBoneIdx]
                    val pIdx = bone.parentId?.let { StickFigureRig.BONE_INDEX[it] } ?: -1

                    // Pivot = where this bone starts = parent's world tip
                    val pivotX = if (pIdx < 0) rootX else eps[pIdx][2]
                    val pivotY = if (pIdx < 0) rootY else eps[pIdx][3]

                    // Direction from pivot to finger in world space
                    val worldAngle = Math.toDegrees(
                        atan2((ty - pivotY).toDouble(), (tx - pivotX).toDouble())
                    ).toFloat()

                    // Parent's world rotation — extracted from its FK endpoints so the
                    // full ancestor chain is already baked in, no manual summation needed
                    val parentWorldRot = if (pIdx < 0) 0f else Math.toDegrees(
                        atan2(
                            (eps[pIdx][3] - eps[pIdx][1]).toDouble(),
                            (eps[pIdx][2] - eps[pIdx][0]).toDouble()
                        )
                    ).toFloat()

                    // currentAngles[i] stores the angle RELATIVE to the parent —
                    // subtracting the parent's world rotation gives us exactly that.
                    val relativeAngle = worldAngle - parentWorldRot
                    engine.setBoneAngle(dragBoneIdx, relativeAngle)
                    onBoneAngleChanged?.invoke(dragBoneIdx, relativeAngle)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragBoneIdx   = -1
                dragEndpoints = null
            }
        }
        return true
    }

    private fun findNearestBone(tx: Float, ty: Float, endpoints: Array<FloatArray>): Int {
        var bestIdx  = -1
        var bestDist = Float.MAX_VALUE
        for (i in endpoints.indices) {
            val sx = endpoints[i][0]; val sy = endpoints[i][1]
            val dist = sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy))
            if (dist < bestDist && dist < 80f) { bestDist = dist; bestIdx = i }
        }
        return bestIdx
    }
}
