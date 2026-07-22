package com.example.data

import kotlinx.serialization.Serializable

/**
 * A named, reusable [AppearanceSettings] snapshot the person can save once
 * and apply to any project — the "switchable saved appearance presets"
 * half of the character-variants request (the other half, multiple
 * characters within a single project, is a separate and much larger
 * future project — see V2_DECISIONS.md).
 *
 * Persisted via [com.example.db.AppearancePresetDao], mirroring exactly
 * how [PoseDef] is persisted via [com.example.db.PoseDao] — same
 * small-named-reusable-library shape, so it gets the same storage
 * treatment rather than inventing a new pattern.
 */
@Serializable
data class AppearancePreset(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val appearance: AppearanceSettings
)
