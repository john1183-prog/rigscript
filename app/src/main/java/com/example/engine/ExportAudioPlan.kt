package com.example.engine

import com.example.data.SoundEffectClip

/**
 * Pure planning for the audio of an export (WP1.1): which of the three behaviours applies, which sound-effect
 * cues can be mixed, what to do when mixing fails, and the one `totalSec` formula. No Android types, so it is
 * unit-tested on the JVM; [ExportAudio] is the MediaCodec/MediaMuxer glue that follows this plan.
 *
 * The rule behind everything here: nothing is dropped silently. Every cue that is skipped and every fallback
 * produces a warning that the export result carries to the UI.
 */
object ExportAudioPlan {

    enum class Mode {
        /** Not embedding audio, or there is nothing to play. */
        NONE,

        /** Narration only: its compressed track is copied into the container unchanged (lossless). */
        PASSTHROUGH,

        /** Background music and/or sound effects present, with or without narration: decoded, mixed, AAC-encoded. */
        MIX
    }

    /** What the Canvas export has always done: mix when there is music or a sound-effect cue, else copy the narration. */
    fun decideMode(embedAudio: Boolean, hasNarration: Boolean, hasMusic: Boolean, sfxTriggerCount: Int): Mode = when {
        !embedAudio -> Mode.NONE
        hasMusic || sfxTriggerCount > 0 -> Mode.MIX
        hasNarration -> Mode.PASSTHROUGH
        else -> Mode.NONE
    }

    /** Export length: the later of the script end and the audio, plus a one-second tail (shared by both backends). */
    fun totalSec(scriptEndSec: Float, audioDurationSec: Float): Float = maxOf(scriptEndSec, audioDurationSec) + 1f

    /** A script cue whose clip is in the project's library, ready for the mixer. */
    data class ResolvedCue(val timeSec: Float, val filePath: String, val volume: Float)

    data class CueResolution(
        val resolved: List<ResolvedCue>,
        /** Distinct clip ids that are not in the library, in order of first appearance. */
        val unresolvedIds: List<String>,
        /** How many cues were skipped because of them. */
        val skippedCueCount: Int
    ) {
        val warnings: List<String>
            get() = if (unresolvedIds.isEmpty()) emptyList() else listOf(
                "Skipped $skippedCueCount sound-effect cue(s) whose clip id is not in this project's library: " +
                    unresolvedIds.joinToString(", ") + "."
            )
    }

    /** Volume rule shared with the preview player: [AudioMath.effectiveSfxVolume], clip volume times the cue's multiplier, clamped to 0..1. */
    fun resolveCues(cues: List<SoundEffectCue>, library: List<SoundEffectClip>): CueResolution {
        val byId = library.associateBy { it.id }
        val resolved = ArrayList<ResolvedCue>()
        val unresolved = LinkedHashSet<String>()
        var skipped = 0
        for (cue in cues) {
            val clip = byId[cue.clipId]
            if (clip == null) {
                unresolved += cue.clipId
                skipped++
            } else {
                resolved += ResolvedCue(cue.timeSec, clip.filePath, AudioMath.effectiveSfxVolume(clip.volume, cue.volumeMultiplier))
            }
        }
        return CueResolution(resolved, unresolved.toList(), skipped)
    }

    data class Outcome(val mode: Mode, val warnings: List<String>)

    /** The reason text used when the mixer produced nothing without throwing. */
    const val NOTHING_DECODABLE = "none of the audio files could be decoded"

    /** A planned [Mode.MIX] could not be built: keep the narration if there is one, and say why. */
    fun afterMixFailure(hasNarration: Boolean, reason: String): Outcome =
        if (hasNarration) {
            Outcome(Mode.PASSTHROUGH, listOf("Mixing music and sound effects failed ($reason); the video has the narration only."))
        } else {
            Outcome(Mode.NONE, listOf("Mixing music and sound effects failed ($reason); the video has no audio."))
        }

    /** The narration's audio track could not be read for a [Mode.PASSTHROUGH]. */
    fun afterNarrationFailure(reason: String): Outcome =
        Outcome(Mode.NONE, listOf("The narration could not be added ($reason); the video has no audio."))

    /** One short line for a warning: the first line of the message, else the exception's class name. */
    fun reasonOf(error: Throwable?): String {
        val firstLine = error?.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        return firstLine ?: error?.javaClass?.simpleName ?: "unknown error"
    }
}
