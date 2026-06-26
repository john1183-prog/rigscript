package com.example.db

import com.example.data.AppJson
import com.example.data.PoseDef
import com.example.data.ProjectDef
import com.example.engine.StickFigureRig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppRepository(private val db: AppDatabase) {

    // E1: Use shared AppJson.storage instead of a private Json instance.
    private val json get() = AppJson.storage

    // ── Projects ──────────────────────────────────────────────────────────────

    /**
     * E4: Lightweight summary flow for the home screen — reads only the
     * pre-extracted name/date columns, never the full projectJson blob.
     */
    fun observeProjectSummaries(): Flow<List<ProjectSummary>> =
        db.projectDao().observeAllSummaries()

    /** Full project load — use only when actually opening a project. */
    fun observeProjects(): Flow<List<ProjectDef>> =
        db.projectDao().observeAll().map { list ->
            list.mapNotNull { entity ->
                runCatching { json.decodeFromString<ProjectDef>(entity.projectJson) }.getOrNull()
            }
        }

    suspend fun getProject(id: String): ProjectDef? =
        db.projectDao().getById(id)?.let { entity ->
            runCatching { json.decodeFromString<ProjectDef>(entity.projectJson) }.getOrNull()
        }

    suspend fun saveProject(project: ProjectDef) {
        val updated = project.copy(lastModifiedMs = System.currentTimeMillis())
        db.projectDao().upsert(
            ProjectEntity(
                id             = updated.id,
                projectName    = updated.projectName,
                lastModifiedMs = updated.lastModifiedMs,
                projectJson    = json.encodeToString(updated)
            )
        )
    }

    suspend fun deleteProject(id: String) = db.projectDao().deleteById(id)

    // ── Poses ─────────────────────────────────────────────────────────────────

    fun observePoses(): Flow<List<PoseDef>> =
        db.poseDao().observeAll().map { list ->
            list.mapNotNull { entity ->
                runCatching { json.decodeFromString<PoseDef>(entity.poseJson) }.getOrNull()
            }
        }

    suspend fun getPose(id: String): PoseDef? {
        return db.poseDao().getById(id)
            ?.let { runCatching { json.decodeFromString<PoseDef>(it.poseJson) }.getOrNull() }
            ?: StickFigureRig.BUILT_IN_POSE_INDEX[id]
    }

    suspend fun savePose(pose: PoseDef) {
        db.poseDao().upsert(
            PoseEntity(
                id        = pose.id,
                name      = pose.name,
                category  = pose.category,
                isBuiltIn = pose.isBuiltIn,
                poseJson  = json.encodeToString(pose)
            )
        )
    }

    suspend fun deleteCustomPose(id: String) = db.poseDao().deleteCustom(id)
}
