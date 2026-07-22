package com.example.engine

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Live-preview playback for background music, kept as a second [MediaPlayer]
 * running alongside [AudioPlayer]'s narration player rather than through real
 * PCM mixing.
 *
 * This is a deliberately different (and much simpler) approach than
 * [AudioMixer], which the export path uses: two [MediaPlayer]s can play
 * concurrently on Android without issue, and small sync drift between them
 * during scrubbing/seeking is an acceptable preview-only tradeoff — the
 * export path is what actually needs to produce a single correctly-mixed
 * track, not the preview. Doing real-time PCM mixing on the UI/render path
 * just to preview a volume balance would be solving a problem the export
 * path already solves properly.
 *
 * Transport (play/pause/seek/stop) is driven by [com.example.ui.editor.EditorScreen]
 * calling this in lockstep with [AudioPlayer], not independently.
 */
class BackgroundMusicPlayer private constructor() {

    private var player: MediaPlayer? = null
    @Volatile private var isReady: Boolean = false
    // Same real bug/fix as AudioPlayer.pendingPlay — see that class's doc
    // comment. This class mirrored AudioPlayer's isReady pattern without
    // also carrying over this fix originally; same race, same fix.
    @Volatile private var pendingPlay: Boolean = false

    val isLoaded: Boolean get() = player != null

    @OptIn(DelicateCoroutinesApi::class)
    fun load(filePath: String, volume: Float, loop: Boolean) {
        release()
        // Same GlobalScope tradeoff as AudioPlayer.load() — see that class's
        // doc comment. This is a manually-managed singleton with no lifecycle
        // owner of its own to scope a coroutine to.
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                player = MediaPlayer().apply {
                    setDataSource(filePath)
                    isLooping = loop
                    setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
                    prepare() // blocking — must not run on Main
                    isReady = true
                }
                if (pendingPlay) {
                    pendingPlay = false
                    runCatching { player?.start() }
                }
            }.onFailure { Log.e(TAG, "Failed to load background music: $filePath", it) }
        }
    }

    fun setVolume(v: Float) {
        runCatching { player?.setVolume(v.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) }
    }

    fun setLooping(loop: Boolean) {
        runCatching { player?.isLooping = loop }
    }

    fun play() {
        if (isReady) runCatching { player?.start() }
        else pendingPlay = true
    }
    fun pause() {
        pendingPlay = false
        runCatching { player?.pause() }
    }
    fun seekTo(ms: Int) { runCatching { player?.seekTo(ms.coerceAtLeast(0)) } }

    /**
     * The loaded track's own duration in ms, or 0 if nothing's loaded/ready
     * yet. Needed by callers doing loop-aware seeking — see
     * [com.example.ui.editor.EditorScreen]'s `seekPlayback`, which is what
     * this was added for: [seekTo] takes whatever ms it's given literally,
     * with no awareness of the loaded track's own length, so a caller
     * seeking to the FULL TIMELINE's position (which can easily exceed a
     * shorter music track's length when the whole point is that it loops)
     * has to do that math itself.
     */
    val durationMs: Int get() = runCatching { player?.duration }.getOrNull() ?: 0

    fun release() {
        runCatching { player?.release() }
        player      = null
        isReady     = false
        pendingPlay = false
    }

    companion object {
        private const val TAG = "BackgroundMusicPlayer"

        @Volatile private var INSTANCE: BackgroundMusicPlayer? = null

        fun getInstance(): BackgroundMusicPlayer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundMusicPlayer().also { INSTANCE = it }
            }
    }
}
