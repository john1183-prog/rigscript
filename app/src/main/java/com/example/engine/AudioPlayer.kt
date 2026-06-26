package com.example.engine

import android.media.MediaPlayer
import android.util.Log

/**
 * Thin wrapper around [MediaPlayer] that exposes the current audio amplitude
 * and coarse mouth shape by indexing into pre-baked envelopes from
 * [AmplitudeAnalyzer].
 *
 * All calls should be made from a single thread. [currentAmplitude] and
 * [currentMouthShape] are @Volatile for safe cross-thread reads from the render thread.
 */
class AudioPlayer private constructor() {

    private var player: MediaPlayer? = null
    private var envelope: FloatArray = FloatArray(0)
    private var mouthShapes: IntArray = IntArray(0)
    private var envelopeFps: Int = 30

    @Volatile var currentAmplitude: Float = 0f
        private set

    @Volatile var currentMouthShape: Int = MouthShape.CLOSED
        private set

    @Volatile private var isReady: Boolean = false

    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = player?.isPlaying == true
    val durationMs: Int get() = player?.duration ?: 0
    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    // ── Load / unload ─────────────────────────────────────────────────────────

    fun load(filePath: String, envelope: FloatArray, mouthShapes: IntArray = IntArray(0), fps: Int = AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS) {
        release()
        this.envelope    = envelope
        this.mouthShapes = mouthShapes
        this.envelopeFps = fps
        // Note: load() is called from a LaunchedEffect (Dispatchers.Main by default).
        // We launch a coroutine for the blocking prepare() so the main thread is never
        // stalled. The player is simply not ready until prepare() completes — callers
        // that immediately call play() will find isReady=false and should check first.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                player = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepare()   // blocking — must not run on Main
                    isReady = true
                }
            }.onFailure { Log.e(TAG, "Failed to load audio: $filePath", it) }
        }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        currentAmplitude  = 0f
        currentMouthShape = MouthShape.CLOSED
        isReady           = false
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun play()  { if (isReady) runCatching { player?.start() } }
    fun pause() { runCatching { player?.pause() } }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms.coerceAtLeast(0)) }
        updateAmplitude()
    }

    fun setOnCompletionListener(cb: () -> Unit) {
        player?.setOnCompletionListener { cb() }
    }

    // ── Amplitude ─────────────────────────────────────────────────────────────

    /**
     * Call each render frame. Reads amplitude and mouth shape from the
     * envelopes at the current playback position.
     */
    fun updateAmplitude() {
        if (envelope.isEmpty()) {
            currentAmplitude  = 0f
            currentMouthShape = MouthShape.CLOSED
            return
        }
        val frameSec = positionMs / 1000f
        val idx      = (frameSec * envelopeFps).toInt().coerceIn(0, envelope.size - 1)
        currentAmplitude  = envelope[idx]
        currentMouthShape = if (mouthShapes.isNotEmpty())
            mouthShapes[idx.coerceAtMost(mouthShapes.size - 1)]
        else MouthShape.CLOSED
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioPlayer"

        @Volatile private var INSTANCE: AudioPlayer? = null

        fun getInstance(): AudioPlayer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioPlayer().also { INSTANCE = it }
            }
    }
}
