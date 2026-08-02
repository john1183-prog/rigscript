package com.example.data

import kotlinx.serialization.Serializable

/**
 * Export configuration stored per-project.
 *
 * [aspectRatio] "9:16" (portrait/Reels) or "16:9" (landscape/presentations).
 *               Ignored when [dualAspectExport] is true — see that field's
 *               doc comment.
 * [resolution]  "360p", "720p", or "1080p" — names the SHORT side of the
 *               frame (not always height — see [dimensions]'s doc comment
 *               for why that distinction matters). "360p" exists
 *               primarily for [com.example.viewmodel.MainViewModel.exportPreview]'s
 *               quick low-res preview render, not as a normal export choice —
 *               though nothing stops a person from picking it directly if a
 *               genuinely small file is what they actually want.
 * [fps]         24 | 30 | 60
 * [bitrateMbps] Target video bitrate in Mbit/s.
 * [embedAudio]  When true, the source audio file is muxed into the output MP4.
 * [outputFormat] "MP4" (H.264) or "WEBM" (VP9 with alpha channel).
 * [dualAspectExport] When true, exports BOTH "9:16" and "16:9" as two
 *               separate files in one export run, ignoring [aspectRatio].
 *               This is a genuine single-pass optimization, not just running
 *               export twice back to back: the animation timeline is
 *               resolved (pose/expression/camera/scene state) exactly once
 *               per frame regardless of how many aspect ratios are being
 *               produced, and audio (narration copy or the background-music/
 *               sound-effect mix) is computed once and reused for both
 *               outputs — only the per-target video encode (drawing +
 *               YUV conversion + MediaCodec) genuinely has to happen twice,
 *               since two different pixel grids are unavoidably two
 *               different encode jobs. "1:1" isn't included in dual mode;
 *               9:16 + 16:9 covers the two dominant real-world targets
 *               (short-form vertical vs. landscape/presentation) this app's
 *               audience actually asks for.
 */
@Serializable
data class ExportSettings(
    val aspectRatio: String = "9:16",
    val resolution: String = "1080p",
    val fps: Int = 30,
    val bitrateMbps: Int = 5,
    val embedAudio: Boolean = true,
    val outputFormat: String = "MP4",
    val dualAspectExport: Boolean = false
) {
    /** Resolved pixel dimensions for the export canvas, for a given [aspect] (defaults to this settings' own [aspectRatio]). */
    fun dimensions(aspect: String = aspectRatio): Pair<Int, Int> {
        // The resolution setting names the SHORT side, not always the
        // height — a bug fixed here: this used to fix height to the named
        // value regardless of orientation, so "1080p" portrait came out
        // 608x1080 (width computed FROM a wrongly-fixed height) instead of
        // the intended 1080x1920. That wasn't just a resolution shortfall:
        // RigRenderer scales the figure from the canvas's MINIMUM
        // dimension, so portrait (minDim 608) and landscape (minDim 1080)
        // produced visibly different figure sizes/positions from the same
        // script — the "figure elevates in portrait" symptom traced back
        // to this.
        val short = when (resolution) {
            "360p" -> 360
            "720p" -> 720
            else   -> 1080
        }
        val (width, height) = when (aspect) {
            "9:16" -> short to (short * 16) / 9
            "16:9" -> (short * 16) / 9 to short
            "1:1"  -> short to short
            else   -> short to (short * 16) / 9
        }
        // Both dimensions must be even for H.264, not just width.
        return Pair(
            if (width % 2 == 0) width else width + 1,
            if (height % 2 == 0) height else height + 1
        )
    }
}
