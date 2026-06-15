package com.example.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.data.AmplitudeSettings
import com.example.data.AppearanceSettings
import com.example.engine.*

/**
 * A [SurfaceView] that drives [PlaybackEngine] on a dedicated background thread,
 * targeting ~60 fps independently of Compose recomposition.
 *
 * Two distinct usage modes:
 *  - **Playback** (editor): [loadTimeline] + [play]/[pause]/[stop]. When an
 *    [AudioPlayer] is attached via [setAudioPlayer], its amplitude is sampled
 *    every frame on the render thread and fed into the engine so talk-motion
 *    actually tracks the audio in real time.
 *  - **Pose editing** (pose library): [poseEditorMode] = true enables dragging
 *    joints; [applyPose]/[captureCurrentPose] read/write a pose directly without
 *    touching the timeline at all.
 */
class AnimationSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // ── Engine ────────────────────────────────────────────────────────────────

    private val engine     = PlaybackEngine()
    private var appearance = AppearanceSettings()
    private var isPlaying  = false
    private var lastTickMs = 0L

    /**
     * Optional audio source for amplitude-reactive motion. Sampled once per
     * render-thread frame via [AudioPlayer.updateAmplitude] — this is what makes
     * the talk-motion respond to the actual audio in real time, as opposed to
     * Compose recomposition (which doesn't fire every 16ms).
     */
    @Volatile private var audioPlayer: AudioPlayer? = null

    // ── Background render thread ────────────────────────────────────────────

    private val renderThread  = HandlerThread("RigRenderThread").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private var surfaceReady  = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt  = if (lastTickMs == 0L) 0.016f else ((now - lastTickMs) / 1000f)
            lastTickMs = now

            if (isPlaying) {
                val player = audioPlayer
                val amp = if (player != null) {
                    player.updateAmplitude()
                    player.currentAmplitude
                } else 0f
                engine.tick(dt.coerceIn(0f, 0.05f), amp)
            }

            drawFrame()
            renderHandler.postDelayed(this, 16)   // ~60fps
        }
    }

    // ── Touch state for pose editor ───────────────────────────────────────────

    var poseEditorMode: Boolean = false
    var onBoneAngleChanged: ((boneIndex: Int, totalAngle: Float) -> Unit)? = null
    private var dragBoneIdx: Int = -1

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        lastTickMs = 0L
        renderHandler.post(frameRunnable)
    }

    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        renderHandler.removeCallbacks(frameRunnable)
    }

    // ── Playback API ──────────────────────────────────────────────────────────

    /**
     * Loads a compiled timeline. Does NOT reset playback position — see
     * [PlaybackEngine.loadTimeline]. While paused, the new target pose is shown
     * immediately; while playing, the spring eases toward it.
     */
    fun loadTimeline(keyframes: List<BakedKeyframe>) {
        val snap = !isPlaying
        renderHandler.post { engine.loadTimeline(keyframes, snapToCurrentTime = snap) }
    }

    fun setAppearance(a: AppearanceSettings) { appearance = a }

    fun setAmplitudeSettings(s: AmplitudeSettings) {
        renderHandler.post { engine.amplitudeSettings = s }
    }

    /** Attach (or detach with null) the audio source for real-time amplitude motion. */
    fun setAudioPlayer(player: AudioPlayer?) { audioPlayer = player }

    fun play()  { isPlaying = true;  lastTickMs = 0L }
    fun pause() { isPlaying = false }
    fun stop()  { isPlaying = false; renderHandler.post { engine.reset() } }

    fun seekTo(timeSec: Float) {
        renderHandler.post { engine.seekTo(timeSec); isPlaying = false }
    }

    fun release() {
        renderHandler.removeCallbacks(frameRunnable)
        renderThread.quitSafely()
    }

    // ── Pose-editor API ───────────────────────────────────────────────────────

    /**
     * Directly applies [joints] (relative bone-id -> degree offsets) to the rig.
     * Used by the pose library to preview the currently-selected pose.
     *
     * Safe to call from the UI thread: this view is never [play]ed in pose-editor
     * mode, so the render thread never concurrently writes engine angles.
     */
    fun applyPose(joints: Map<String, Float>) {
        val bones = StickFigureRig.BONES
        bones.forEachIndexed { i, bone ->
            val total = bone.defaultAngleDegrees + (joints[bone.id] ?: 0f)
            engine.setBoneAngle(i, total)
        }
    }

    /** Returns the current pose as relative bone-id -> degree offsets, for saving. */
    fun captureCurrentPose(): Map<String, Float> = engine.captureRelativeAngles()

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun drawFrame() {
        if (!surfaceReady) return
        val canvas: Canvas = try { holder.lockCanvas() ?: return }
        catch (e: Exception) { return }
        try {
            canvas.drawColor(appearance.previewBgColor.toInt())
            RigRenderer.draw(canvas, engine.currentAngles, appearance, width, height)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // ── Touch (pose editor mode) ──────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!poseEditorMode) return false
        val tx = event.x; val ty = event.y
        val minDim = minOf(width, height).toFloat()
        val scale  = minDim * appearance.characterScale
        val rootX  = width  * appearance.rootAnchorX
        val rootY  = height * appearance.rootAnchorY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragBoneIdx = findNearestBone(tx, ty, engine.currentAngles, scale, rootX, rootY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBoneIdx >= 0) {
                    val bone   = StickFigureRig.BONES[dragBoneIdx]
                    val pIdx   = bone.parentId?.let { StickFigureRig.BONE_INDEX[it] } ?: -1
                    val pivotX = if (pIdx < 0) rootX else {
                        val pb = StickFigureRig.BONES[pIdx]
                        rootX + pb.normalizedLength * scale *
                            kotlin.math.cos(Math.toRadians(engine.currentAngles[pIdx].toDouble())).toFloat()
                    }
                    val pivotY = if (pIdx < 0) rootY else {
                        val pb = StickFigureRig.BONES[pIdx]
                        rootY + pb.normalizedLength * scale *
                            kotlin.math.sin(Math.toRadians(engine.currentAngles[pIdx].toDouble())).toFloat()
                    }
                    val angle = Math.toDegrees(
                        kotlin.math.atan2((ty - pivotY).toDouble(), (tx - pivotX).toDouble())
                    ).toFloat()
                    engine.setBoneAngle(dragBoneIdx, angle)
                    onBoneAngleChanged?.invoke(dragBoneIdx, angle)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragBoneIdx = -1
        }
        return true
    }

    private fun findNearestBone(
        tx: Float, ty: Float, angles: FloatArray,
        scale: Float, rootX: Float, rootY: Float
    ): Int {
        var bestIdx  = -1
        var bestDist = Float.MAX_VALUE
        val bones    = StickFigureRig.BONES

        for (i in bones.indices) {
            val bone = bones[i]
            val pIdx = bone.parentId?.let { StickFigureRig.BONE_INDEX[it] } ?: -1
            val boneStartX = if (pIdx < 0) rootX else {
                val pb = bones[pIdx]
                rootX + pb.normalizedLength * scale *
                    kotlin.math.cos(Math.toRadians(angles[pIdx].toDouble())).toFloat()
            }
            val boneStartY = if (pIdx < 0) rootY else {
                val pb = bones[pIdx]
                rootY + pb.normalizedLength * scale *
                    kotlin.math.sin(Math.toRadians(angles[pIdx].toDouble())).toFloat()
            }
            val dist = kotlin.math.sqrt(
                ((tx - boneStartX) * (tx - boneStartX) +
                 (ty - boneStartY) * (ty - boneStartY)).toDouble()
            ).toFloat()
            if (dist < bestDist && dist < 80f) { bestDist = dist; bestIdx = i }
        }
        return bestIdx
    }
}
