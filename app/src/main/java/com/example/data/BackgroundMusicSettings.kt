package com.example.data

import kotlinx.serialization.Serializable

/**
 * Optional background music mixed under the narration track on export.
 *
 * Unlike the narration audio (which is copied into the exported MP4
 * verbatim/losslessly whenever possible — see [com.example.engine.VideoExporter]),
 * background music requires an actual PCM decode → mix → re-encode pass
 * ([com.example.engine.AudioMixer]), because two separate compressed audio
 * streams can't be muxed into a single playable track without mixing their
 * samples first. That mixing pass only runs when [musicFilePath] is set —
 * a project with no background music still gets the fast verbatim-copy
 * export path, unchanged.
 *
 * [musicFilePath]     Absolute on-device path to the imported music file, set
 *                     by [com.example.viewmodel.MainViewModel.importBackgroundMusic].
 *                     Null = no background music.
 * [volume]            Music level in the final mix (0..1). Independent of
 *                     [narrationVolume] — the two are mixed as
 *                     `narration * narrationVolume + music * musicVolume`,
 *                     not normalized against each other, so raising one
 *                     doesn't automatically lower the other.
 * [narrationVolume]   Narration level in the final mix (0..1). Defaults to
 *                     1.0 (unchanged) — lowering it lets music sit more
 *                     forward without needing to guess a music volume that
 *                     "sounds balanced" against a fixed narration level.
 * [loop]              If the music is shorter than the video, loop it to
 *                     fill the remaining duration. If false, music plays
 *                     once and the remainder of the video has narration only.
 */
@Serializable
data class BackgroundMusicSettings(
    val musicFilePath: String? = null,
    val volume: Float = 0.35f,
    val narrationVolume: Float = 1.0f,
    val loop: Boolean = true
)
