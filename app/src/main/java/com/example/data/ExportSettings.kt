package com.example.data

import kotlinx.serialization.Serializable

/**
 * Export configuration stored per-project.
 *
 * [aspectRatio] "9:16" (portrait/Reels) or "16:9" (landscape/presentations).
 * [resolution]  "720p" or "1080p" — height is the named dimension; width is derived
 *               from the aspect ratio.
 * [fps]         24 | 30 | 60
 * [bitrateMbps] Target video bitrate in Mbit/s.
 * [embedAudio]  When true, the source audio file is muxed into the output MP4.
 * [outputFormat] "MP4" (H.264) or "WEBM" (VP9 with alpha channel).
 */
@Serializable
data class ExportSettings(
    val aspectRatio: String = "9:16",
    val resolution: String = "1080p",
    val fps: Int = 30,
    val bitrateMbps: Int = 5,
    val embedAudio: Boolean = true,
    val outputFormat: String = "MP4"
) {
    /** Resolved pixel dimensions for the export canvas. */
    fun dimensions(): Pair<Int, Int> {
        val height = if (resolution == "720p") 720 else 1080
        val width = when (aspectRatio) {
            "9:16"  -> (height * 9) / 16
            "16:9"  -> (height * 16) / 9
            "1:1"   -> height
            else    -> (height * 9) / 16
        }
        // Width must be even for H.264
        return Pair(if (width % 2 == 0) width else width + 1, height)
    }
}
