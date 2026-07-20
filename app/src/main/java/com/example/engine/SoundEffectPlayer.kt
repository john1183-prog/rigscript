package com.example.engine

import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.example.data.SoundEffectClip

/**
 * Live-preview playback for one-shot sound effect clips, using [SoundPool]
 * (Android's low-latency mechanism for short clips) rather than
 * [android.media.MediaPlayer] — a `MediaPlayer` per trigger would have
 * noticeable start latency and isn't designed for rapid-fire one-shot
 * playback the way `SoundPool` is.
 *
 * Owned directly by `AnimationSurfaceView` (one instance per view, released
 * alongside it) — same ownership pattern as that class's `RigRenderer` and
 * `PlaybackEngine` instances, NOT the app-wide singleton pattern
 * [AudioPlayer]/[BackgroundMusicPlayer] use. Those two are singletons
 * specifically because narration/music playback needs to survive an
 * `AnimationSurfaceView` being torn down and recreated (e.g. on
 * configuration change); one-shot sound effects have no such continuity
 * requirement — polling happens on the same render-thread tick as
 * `PlaybackEngine`, so tying its lifecycle to that engine's owner is simpler
 * and correct.
 *
 * Export does NOT use this class — [VideoExporter] mixes sound effects
 * directly into the exported audio track via [AudioMixer] using the same
 * [SoundEffectCue] list this class is driven by in preview. This mirrors
 * the narration/[BackgroundMusicPlayer] split: preview plays clips live and
 * approximately, export produces one correctly mixed result.
 */
class SoundEffectPlayer {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // id -> (soundPoolId, clip's own configured volume)
    private var loaded: Map<String, Pair<Int, Float>> = emptyMap()
    private var loadedForClips: List<SoundEffectClip> = emptyList()

    // Real bug this fixes: SoundPool.load() is ASYNCHRONOUS — the returned
    // soundId is a handle for a sample that may not have finished decoding
    // yet. Calling play() on it before it's ready is a SILENT no-op (no
    // exception, no log) per SoundPool's documented behavior. Unlike
    // AudioPlayer/BackgroundMusicPlayer's pendingPlay fix, a one-shot sound
    // effect can't just be deferred to "whenever it's ready" — that would
    // play it late, out of sync with the video it was supposed to punctuate.
    // So the fix here is different: track which sample ids have actually
    // finished loading, and skip (with a log line, so it's at least
    // diagnosable) a trigger that fires before its clip is ready, rather
    // than silently calling play() on something that can't play yet.
    private val readySoundIds = mutableSetOf<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) readySoundIds += sampleId
            else Log.e(TAG, "SoundPool failed to load sample id $sampleId (status $status)")
        }
    }

    /**
     * (Re)loads the project's sound-effect library into the pool. Cheap to
     * call redundantly — no-ops if [clips] is reference-equal to what's
     * already loaded, same "only reload on real change" convention as
     * `AnimationSurfaceView.loadTimeline`'s keyframes check.
     */
    fun loadClips(clips: List<SoundEffectClip>) {
        if (clips === loadedForClips) return
        val next = HashMap<String, Pair<Int, Float>>()
        for (clip in clips) {
            runCatching {
                val soundId = soundPool.load(clip.filePath, 1)
                next[clip.id] = soundId to clip.volume
            }.onFailure { Log.e(TAG, "Failed to load sound effect '${clip.id}' (${clip.filePath})", it) }
        }
        loaded = next
        loadedForClips = clips
    }

    /** Fires [cues] (as returned by [PlaybackEngine.pollTriggeredSoundEffects]) — a no-op for any id not in the currently loaded library, or whose clip hasn't finished loading yet (see [readySoundIds]'s doc comment above). */
    fun playTriggered(cues: List<SoundEffectCue>) {
        for (cue in cues) {
            val (soundId, clipVolume) = loaded[cue.clipId] ?: continue
            if (soundId !in readySoundIds) {
                Log.w(TAG, "Sound effect '${cue.clipId}' triggered before its clip finished loading — skipped.")
                continue
            }
            val v = (clipVolume * cue.volumeMultiplier).coerceIn(0f, 1f)
            runCatching { soundPool.play(soundId, v, v, 0, 0, 1f) }
        }
    }

    fun release() {
        soundPool.release()
        loaded = emptyMap()
        loadedForClips = emptyList()
        readySoundIds.clear()
    }

    companion object {
        private const val TAG = "SoundEffectPlayer"
    }
}
