package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY lastModifiedMs DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

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
