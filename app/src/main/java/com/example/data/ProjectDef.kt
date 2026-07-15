package com.example.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The complete in-memory representation of a RigScript project.
 *
 * Serialised to JSON and stored as a single column in the Room database so the
 * schema never needs migration as new fields are added (just provide defaults).
 *
 * [audioFilePath]          Absolute path to the imported audio file on-device, or null.
 * [amplitudeEnvelopePath]  Absolute path to a binary envelope file written by
 *                          [com.example.engine.EnvelopeStore], or null if no audio
 *                          has been imported yet. V2: envelopes moved OUT of this
 *                          JSON blob into binary files — a 7.5-min project at 30fps
 *                          was previously ~150-200KB of JSON-encoded floats/ints
 *                          living inside SQLite, decoded in full on every load.
 * [mouthShapeEnvelopePath] Same idea, for the per-frame [com.example.engine.MouthShape]
 *                          classification.
 * [amplitudeEnvelope]/[mouthShapeEnvelope] — DEPRECATED, V1 inline storage. Kept
 *                          ONLY so a project saved before this migration can still be
 *                          read; [com.example.viewmodel.MainViewModel.loadProject]
 *                          migrates these to disk and clears them the first time the
 *                          project is loaded after upgrading (kept out of
 *                          [com.example.db.AppRepository] deliberately — that layer
 *                          stays DB-only with no filesystem/Context dependency, same
 *                          as audio file handling already lives in the ViewModel).
 *                          New code should never write into these directly.
 */
@Serializable
data class ProjectDef(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String = "Untitled",
    val audioFilePath: String? = null,
    /** Decoded audio duration in seconds — derived from the amplitude envelope, not container metadata. */
    val audioDurationSec: Float = 0f,
    @Deprecated("Migrated to amplitudeEnvelopePath on load. Do not write to this directly.")
    val amplitudeEnvelope: List<Float> = emptyList(),
    @Deprecated("Migrated to mouthShapeEnvelopePath on load. Do not write to this directly.")
    val mouthShapeEnvelope: List<Int> = emptyList(),
    val amplitudeEnvelopePath: String? = null,
    val mouthShapeEnvelopePath: String? = null,
    val script: AnimScript = AnimScript.EMPTY,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val exportSettings: ExportSettings = ExportSettings(),
    /** Manual, one-time-configured reference image/text overlay. Never touched by the AI script pipeline — see [ReferenceOverlay]'s doc comment. */
    val referenceOverlay: ReferenceOverlay = ReferenceOverlay(),
    val lastModifiedMs: Long = System.currentTimeMillis()
)
