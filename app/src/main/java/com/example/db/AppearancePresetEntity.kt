package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a saved [com.example.data.AppearancePreset]. Mirrors
 * [PoseEntity]'s shape deliberately — same small-named-reusable-library
 * pattern, same storage treatment.
 *
 * [appearanceJson] holds the full [com.example.data.AppearanceSettings]
 * serialised to JSON, same as [PoseEntity.poseJson] does for [com.example.data.PoseDef].
 */
@Entity(tableName = "appearance_presets")
data class AppearancePresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val appearanceJson: String
)
