package com.example.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
 * targeting 60 fps regardless of the Compose recomposition cycle.
 *
 * Usage in Compose via [AndroidView]:
 * ```kotlin
 * AndroidView(factory = { AnimationSurfaceView(it) }, update = { view ->
 *     view.setProject(keyframes, appearance, amplSettings)
 *     view.setAudioAmplitude(amplitude)
 * })
 * ```
 */
class AnimationSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // ── Engine ────────────────────────────────────────────────────────────────

    private val engine       = PlaybackEngine()
    private var appearance   = AppearanceSettings()
    private var isPlaying    = false
    private var lastTickMs   = 0L

    // Amplitude written from UI thread, read from render thread (@Volatile)
    @Volatile private var externalAmplitude: Float = 0f

    // ── Background render thread ──────────────────────────────────────────────

    private val renderThread  = HandlerThread("RigRenderThread").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private var surfaceReady  = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt  = if (lastTickMs == 0L) 0.016f else ((now - lastTickMs) / 1000f)
            lastTickMs = now

            if (isPlaying) {
                engine.tick(dt.coerceIn(0f, 0.05f), externalAmplitude)
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
        renderHandler.post(frameRunnable)
    }

    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        renderHandler.removeCallbacks(frameRunnable)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun loadTimeline(keyframes: List<BakedKeyframe>) {
        renderHandler.post { engine.loadTimeline(keyframes) }
    }

    fun setAppearance(a: AppearanceSettings) { appearance = a }

    fun setAmplitudeSettings(s: AmplitudeSettings) {
        renderHandler.post { engine.amplitudeSettings = s }
    }

    fun setAudioAmplitude(amp: Float) { externalAmplitude = amp }

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

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun drawFrame() {
        if (!surfaceReady) return
        val canvas: Canvas = try { holder.lockCanvas() ?: return }
        catch (e: Exception) { return }
        try {
            val w = width; val h = height
            canvas.drawColor(appearance.previewBgColor.toInt())
            RigRenderer.draw(canvas, engine.currentAngles, appearance, w, h)
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
                    // Compute angle from bone's parent position to touch point
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
            val bone  = bones[i]
            val pIdx  = bone.parentId?.let { StickFigureRig.BONE_INDEX[it] } ?: -1
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
