package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight project card data for the home screen.
 * Reads ONLY the pre-extracted columns — never touches [ProjectEntity.projectJson].
 * For a project with a 7.5-min audio file, this avoids decoding ~200KB of JSON
 * (amplitude envelope + script) just to display the project name and date.
 */
data class ProjectSummary(
    val id: String,
    val projectName: String,
    val lastModifiedMs: Long
)

@Dao
interface ProjectDao {

    /** Full load — only used when a specific project is opened. */
    @Query("SELECT * FROM projects ORDER BY lastModifiedMs DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    /** E4: Home screen — reads only the pre-extracted name/date columns, zero JSON parsing. */
    @Query("SELECT id, projectName, lastModifiedMs FROM projects ORDER BY lastModifiedMs DESC")
    fun observeAllSummaries(): Flow<List<ProjectSummary>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface PoseDao {

    @Query("SELECT * FROM poses ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<PoseEntity>>

    @Query("SELECT * FROM poses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PoseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)   // IGNORE so built-ins aren't overwritten
    suspend fun insertIfAbsent(entity: PoseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PoseEntity)

    @Query("DELETE FROM poses WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String)

    @Query("SELECT COUNT(*) FROM poses WHERE isBuiltIn = 1")
    suspend fun builtInCount(): Int
}
