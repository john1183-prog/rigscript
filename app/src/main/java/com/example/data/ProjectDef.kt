package com.example.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The complete in-memory representation of a RigScript project.
 *
 * Serialised to JSON and stored as a single column in the Room database so the
 * schema never needs migration as new fields are added (just provide defaults).
 *
 * [audioFilePath]      Absolute path to the imported audio file on-device, or null.
 * [amplitudeEnvelope]  Pre-analysed RMS amplitude per frame at [ExportSettings.fps].
 *                      Populated by [com.example.engine.AmplitudeAnalyzer] when audio
 *                      is imported; stored here to avoid re-analysing on every load.
 */
@Serializable
data class ProjectDef(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String = "Untitled",
    val audioFilePath: String? = null,
    /** Decoded audio duration in seconds — derived from the amplitude envelope, not container metadata. */
    val audioDurationSec: Float = 0f,
    val amplitudeEnvelope: List<Float> = emptyList(),
    /** Coarse [com.example.engine.MouthShape] classification per frame, same length/rate as [amplitudeEnvelope]. */
    val mouthShapeEnvelope: List<Int> = emptyList(),
    val script: AnimScript = AnimScript.EMPTY,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val exportSettings: ExportSettings = ExportSettings(),
    val lastModifiedMs: Long = System.currentTimeMillis()
)
