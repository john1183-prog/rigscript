package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a project. The entire [ProjectDef] is serialised to JSON and
 * stored in [projectJson] so schema migrations are never needed when adding
 * new fields (Kotlin serialization defaults handle backwards compatibility).
 *
 * [id], [projectName], and [lastModifiedMs] are duplicated as top-level columns
 * so Room can sort and filter projects without deserialising the JSON blob.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val projectName: String,
    val lastModifiedMs: Long,
    val projectJson: String        // Full ProjectDef serialised to JSON
)
