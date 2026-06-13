package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a user-created or built-in pose.
 *
 * Built-in poses ([isBuiltIn] = true) are pre-populated on first launch and
 * can never be deleted — only the user's custom poses are deletable.
 *
 * [poseJson] holds the full [com.example.data.PoseDef] serialised to JSON.
 */
@Entity(tableName = "poses")
data class PoseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val isBuiltIn: Boolean,
    val poseJson: String
)
