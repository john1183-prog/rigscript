package com.example.engine

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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

    /**
     * WP0.5 (B3): load() prepares on a background thread, so a late prepare could assign a MediaPlayer after
     * release() or a newer load() had already run, and nothing would ever release it. Every load() and release()
     * moves [loadGeneration]; a prepare that finishes under an older generation releases its own player instead.
     */
    private val playerLock = Any()
    @Volatile private var loadGeneration: Int = 0

    /** Kept here (not only on the player) so a listener set before prepare completes is not lost. */
    private var completionListener: (() -> Unit)? = null
    // Real bug this fixes: previously, play() called before prepare()
    // finished was a SILENT no-op with no retry — the UI would flip to
    // "playing" state regardless, since nothing here told it otherwise, and
    // audio simply never started for that attempt. This is very plausibly
    // why audio sometimes doesn't sound: opening a project and immediately
    // tapping Play is an entirely normal thing to do, and prepare() takes a
    // real (if usually short) amount of time. Now play() called too early
    // just remembers the intent and honors it the moment prepare() finishes.
    @Volatile private var pendingPlay: Boolean = false

    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = player?.isPlaying == true
    val durationMs: Int get() = player?.duration ?: 0
    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    // ── Load / unload ─────────────────────────────────────────────────────────

    @OptIn(DelicateCoroutinesApi::class)
    fun load(filePath: String, envelope: FloatArray, mouthShapes: IntArray = IntArray(0), fps: Int = AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS) {
        release()
        this.envelope    = envelope
        this.mouthShapes = mouthShapes
        this.envelopeFps = fps
        // Note: load() is called from a LaunchedEffect (Dispatchers.Main by default).
        // We launch a coroutine for the blocking prepare() so the main thread is never
        // stalled. The player is simply not ready until prepare() completes — callers
        // that immediately call play() will find isReady=false and should check first.
        //
        // GlobalScope is used because AudioPlayer is a manually-managed singleton with
        // no lifecycle owner of its own to scope a coroutine to. This is a known
        // tradeoff, not an oversight — see the V2 handoff doc for the cleaner
        // alternative (inject a CoroutineScope tied to AudioPlayer's own lifecycle).
        val generation = synchronized(playerLock) { loadGeneration }
        GlobalScope.launch(Dispatchers.IO) {
            val prepared = runCatching { MediaPlayer() }.getOrNull() ?: return@launch
            runCatching {
                prepared.setDataSource(filePath)
                prepared.prepare()   // blocking — must not run on Main
                val accepted = synchronized(playerLock) {
                    if (generation != loadGeneration) {
                        false
                    } else {
                        player = prepared
                        completionListener?.let { cb -> prepared.setOnCompletionListener { cb() } }
                        isReady = true
                        true
                    }
                }
                if (!accepted) {
                    // A newer load() or a release() happened while this one was preparing.
                    runCatching { prepared.release() }
                    return@runCatching
                }
                if (pendingPlay) {
                    pendingPlay = false
                    runCatching { player?.start() }
                }
            }.onFailure {
                runCatching { prepared.release() }
                Log.e(TAG, "Failed to load audio: $filePath", it)
            }
        }
    }

    fun release() {
        synchronized(playerLock) { loadGeneration++ }
        runCatching { player?.release() }
        player = null
        currentAmplitude  = 0f
        currentMouthShape = MouthShape.CLOSED
        isReady           = false
        pendingPlay        = false
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun play() {
        if (isReady) runCatching { player?.start() }
        else pendingPlay = true
    }
    fun pause() {
        pendingPlay = false
        runCatching { player?.pause() }
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms.coerceAtLeast(0)) }
        updateAmplitude()
    }

    fun setOnCompletionListener(cb: () -> Unit) {
        synchronized(playerLock) {
            completionListener = cb
            player?.setOnCompletionListener { cb() }
        }
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
