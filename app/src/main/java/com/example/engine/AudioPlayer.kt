package com.example.engine

import android.media.MediaPlayer
import android.util.Log

/**
 * Thin wrapper around [MediaPlayer] that exposes the current audio amplitude
 * by indexing into a pre-baked [amplitudeEnvelope] from [AmplitudeAnalyzer].
 *
 * All calls should be made from a single thread. [currentAmplitude] is @Volatile
 * for safe cross-thread reads (e.g. from the render thread).
 */
class AudioPlayer private constructor() {

    private var player: MediaPlayer? = null
    private var envelope: FloatArray = FloatArray(0)
    private var envelopeFps: Int = 30

    @Volatile var currentAmplitude: Float = 0f
        private set

    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = player?.isPlaying == true
    val durationMs: Int get() = player?.duration ?: 0
    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    // ── Load / unload ─────────────────────────────────────────────────────────

    fun load(filePath: String, envelope: FloatArray, fps: Int = 30) {
        release()
        this.envelope    = envelope
        this.envelopeFps = fps
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
            }
        }.onFailure { Log.e(TAG, "Failed to load audio: $filePath", it) }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        currentAmplitude = 0f
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun play()  { runCatching { player?.start() } }
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
     * Call each render frame. Reads amplitude from the envelope at the current
     * playback position and updates [currentAmplitude].
     */
    fun updateAmplitude() {
        if (envelope.isEmpty()) { currentAmplitude = 0f; return }
        val frameSec = positionMs / 1000f
        val idx      = (frameSec * envelopeFps).toInt().coerceIn(0, envelope.size - 1)
        currentAmplitude = envelope[idx]
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
