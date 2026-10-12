package com.example.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.data.BackgroundMusicSettings
import com.example.data.SoundEffectClip
import java.nio.ByteBuffer

/**
 * The audio of an export, shared by the video backends (WP1.1). [ExportAudioPlan] decides what to do (pure,
 * unit-tested); this object builds the audio and writes it into a [MediaMuxer]:
 *  - [ExportAudioPlan.Mode.PASSTHROUGH]: the narration's compressed track is copied unchanged;
 *  - [ExportAudioPlan.Mode.MIX]: [AudioMixer] decodes, mixes and AAC-encodes narration, music and sound effects;
 *  - [ExportAudioPlan.Mode.NONE]: no audio track.
 *
 * Nothing is dropped silently: skipped cues, a failed mix (with the narration kept as the fallback), an unreadable
 * narration and a copy that stops part-way all become warnings that travel in [ExportResult.warnings].
 *
 * Compiled by the CI build only; not verified on a device.
 */
object ExportAudio {

    private const val TAG = "ExportAudio"

    class PreparedAudio internal constructor(
        val mode: ExportAudioPlan.Mode,
        /** Everything that was skipped or fell back while preparing, in plain words. */
        val warnings: List<String>,
        private val mixed: AudioMixer.EncodedAudioTrack?,
        private val narrationPath: String?,
        private val narrationFormat: MediaFormat?
    ) {
        /** Adds the audio track to [muxer] (before it starts). Returns the track index, or -1 when there is no audio. */
        fun addTrack(muxer: MediaMuxer): Int = when {
            mixed != null -> muxer.addTrack(mixed.format)
            narrationFormat != null -> muxer.addTrack(narrationFormat)
            else -> -1
        }

        /**
         * Writes the audio for the track [addTrack] returned: a mixed track whole, a narration copy up to
         * [maxDurationUs]. Returns warnings (empty when everything was written).
         */
        fun write(muxer: MediaMuxer, trackIdx: Int, maxDurationUs: Long): List<String> {
            if (trackIdx < 0) return emptyList()
            if (mixed != null) {
                val info = MediaCodec.BufferInfo()
                for (s in mixed.samples) {
                    info.offset = 0
                    info.size = s.data.size
                    info.presentationTimeUs = s.presentationTimeUs
                    info.flags = s.flags
                    muxer.writeSampleData(trackIdx, ByteBuffer.wrap(s.data), info)
                }
                return emptyList()
            }
            val path = narrationPath ?: return emptyList()
            val failure = copyNarration(path, muxer, trackIdx, maxDurationUs)
            return if (failure == null) emptyList()
            else listOf("Copying the narration stopped part-way ($failure); the audio may be cut short.")
        }
    }

    /**
     * Builds the audio plan and, for a mix, the mixed track. Blocking (decodes and encodes), like [AudioMixer].
     * With [embedAudio] false nothing is built and nothing is reported.
     */
    fun prepare(
        embedAudio: Boolean,
        narrationPath: String?,
        music: BackgroundMusicSettings,
        cues: List<SoundEffectCue>,
        library: List<SoundEffectClip>,
        totalSec: Float
    ): PreparedAudio {
        if (!embedAudio) return PreparedAudio(ExportAudioPlan.Mode.NONE, emptyList(), null, null, null)

        val resolution = ExportAudioPlan.resolveCues(cues, library)
        val warnings = ArrayList<String>(resolution.warnings)
        val hasNarration = narrationPath != null
        val planned = ExportAudioPlan.decideMode(true, hasNarration, music.musicFilePath != null, resolution.resolved.size)

        if (planned == ExportAudioPlan.Mode.MIX) {
            val attempt = runCatching {
                AudioMixer.buildMixedTrack(
                    narrationPath   = narrationPath,
                    musicPath       = music.musicFilePath,
                    narrationVolume = music.narrationVolume,
                    musicVolume     = music.volume,
                    loopMusic       = music.loop,
                    totalSec        = totalSec,
                    soundEffects    = resolution.resolved.map { AudioMixer.SoundEffectTrigger(it.timeSec, it.filePath, it.volume) }
                )
            }
            val track = attempt.getOrNull()
            if (track != null) return PreparedAudio(ExportAudioPlan.Mode.MIX, warnings, track, null, null)

            val reason = if (attempt.isFailure) ExportAudioPlan.reasonOf(attempt.exceptionOrNull()) else ExportAudioPlan.NOTHING_DECODABLE
            Log.e(TAG, "Mixing failed: $reason", attempt.exceptionOrNull())
            val fallback = ExportAudioPlan.afterMixFailure(hasNarration, reason)
            warnings += fallback.warnings
            if (fallback.mode != ExportAudioPlan.Mode.PASSTHROUGH) {
                return PreparedAudio(fallback.mode, warnings, null, null, null)
            }
        }

        // PASSTHROUGH (planned, or the fallback of a failed mix), or nothing at all.
        val path = narrationPath ?: return PreparedAudio(ExportAudioPlan.Mode.NONE, warnings, null, null, null)
        val probe = runCatching { findAudioFormat(path) }
        val format = probe.getOrNull()
        if (format == null) {
            val reason = ExportAudioPlan.reasonOf(probe.exceptionOrNull())
            Log.e(TAG, "Narration unreadable: $reason", probe.exceptionOrNull())
            warnings += ExportAudioPlan.afterNarrationFailure(reason).warnings
            return PreparedAudio(ExportAudioPlan.Mode.NONE, warnings, null, null, null)
        }
        return PreparedAudio(ExportAudioPlan.Mode.PASSTHROUGH, warnings, null, path, format)
    }

    /** The first audio track's format of [path]; throws when the file has none or cannot be read. */
    private fun findAudioFormat(path: String): MediaFormat {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return fmt
            }
            throw IllegalStateException("no audio track in the file")
        } finally {
            ex.release()
        }
    }

    /** Adds the first audio track of [audioPath] to [muxer] unchanged; -1 if there is none. (The GLES smoke test still uses this.) */
    internal fun addNarrationTrack(muxer: MediaMuxer, audioPath: String): Int =
        runCatching { muxer.addTrack(findAudioFormat(audioPath)) }
            .getOrElse { Log.e(TAG, "addNarrationTrack failed", it); -1 }

    /**
     * Copies the first audio track of [audioPath] into [muxer] sample by sample, stopping after [maxPtsUs].
     * Returns null on success, or a one-line reason when it failed part-way.
     */
    internal fun copyNarration(audioPath: String, muxer: MediaMuxer, trackIdx: Int, maxPtsUs: Long): String? {
        return runCatching {
            val ex = MediaExtractor().also { it.setDataSource(audioPath) }
            try {
                var src = -1
                for (i in 0 until ex.trackCount) {
                    if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { src = i; break }
                }
                if (src < 0) return@runCatching
                ex.selectTrack(src)

                val buf  = ByteBuffer.allocate(512 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0 || ex.sampleTime > maxPtsUs) break
                    info.offset = 0; info.size = sz
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(trackIdx, buf, info)
                    ex.advance()
                }
            } finally {
                ex.release()
            }
        }.exceptionOrNull()?.let {
            Log.e(TAG, "copyNarration failed", it)
            ExportAudioPlan.reasonOf(it)
        }
    }
}
