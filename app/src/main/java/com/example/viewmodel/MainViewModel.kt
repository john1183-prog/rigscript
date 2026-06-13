package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.db.AppDatabase
import com.example.db.AppRepository
import com.example.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db   = AppDatabase.getInstance(app)
    val repo         = AppRepository(db)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    // ── Project list ──────────────────────────────────────────────────────────

    val projects: StateFlow<List<ProjectDef>> = repo.observeProjects()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Active project ────────────────────────────────────────────────────────

    private val _activeProject = MutableStateFlow<ProjectDef?>(null)
    val activeProject: StateFlow<ProjectDef?> = _activeProject.asStateFlow()

    /** Human-readable script JSON shown in the script editor panel. */
    private val _scriptText = MutableStateFlow("")
    val scriptText: StateFlow<String> = _scriptText.asStateFlow()

    /** Null = valid JSON, else the parse error message. */
    private val _scriptError = MutableStateFlow<String?>(null)
    val scriptError: StateFlow<String?> = _scriptError.asStateFlow()

    // ── Pose library ──────────────────────────────────────────────────────────

    val poses: StateFlow<List<PoseDef>> = repo.observePoses()
        .stateIn(viewModelScope, SharingStarted.Lazily, StickFigureRig.BUILT_IN_POSES)

    // ── Global amplitude settings ─────────────────────────────────────────────
    // (stored in DataStore in a real app; kept in-memory here for simplicity)

    private val _amplitudeSettings = MutableStateFlow(AmplitudeSettings())
    val amplitudeSettings: StateFlow<AmplitudeSettings> = _amplitudeSettings.asStateFlow()

    // ── Audio state ───────────────────────────────────────────────────────────

    private val _audioFileName = MutableStateFlow<String?>(null)
    val audioFileName: StateFlow<String?> = _audioFileName.asStateFlow()

    private val _isAnalysingAudio = MutableStateFlow(false)
    val isAnalysingAudio: StateFlow<Boolean> = _isAnalysingAudio.asStateFlow()

    // ── Export state ──────────────────────────────────────────────────────────

    private val _exportProgress = MutableStateFlow<Float?>(null)  // null = idle
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _exportedFilePath = MutableStateFlow<String?>(null)
    val exportedFilePath: StateFlow<String?> = _exportedFilePath.asStateFlow()

    // ── Snackbar / toasts ─────────────────────────────────────────────────────

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    // ── Project CRUD ──────────────────────────────────────────────────────────

    fun createProject(name: String = "Untitled") {
        val project = ProjectDef(id = UUID.randomUUID().toString(), projectName = name,
            script = AnimScript.DEMO)
        viewModelScope.launch {
            repo.saveProject(project)
            loadProject(project.id)
        }
    }

    fun loadProject(id: String) {
        viewModelScope.launch {
            val project = repo.getProject(id) ?: return@launch
            _activeProject.value = project
            _scriptText.value    = json.encodeToString(project.script)
            _scriptError.value   = null
            _audioFileName.value = project.audioFilePath?.let { File(it).name }
        }
    }

    fun saveActiveProject() {
        val project = _activeProject.value ?: return
        viewModelScope.launch { repo.saveProject(project) }
    }

    fun renameProject(newName: String) {
        updateActive { it.copy(projectName = newName) }
        saveActiveProject()
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { repo.deleteProject(id) }
    }

    private fun updateActive(transform: (ProjectDef) -> ProjectDef) {
        _activeProject.update { it?.let(transform) }
    }

    // ── Script editing ────────────────────────────────────────────────────────

    /** Called by the script text field on every keystroke (with debounce from UI). */
    fun onScriptTextChanged(raw: String) {
        _scriptText.value = raw
        runCatching {
            val parsed = json.decodeFromString<AnimScript>(raw)
            _scriptError.value = null
            updateActive { it.copy(script = parsed) }
        }.onFailure { _scriptError.value = it.message?.take(120) }
    }

    fun insertScriptEvent(poseId: String, atSec: Float) {
        val project = _activeProject.value ?: return
        val newEvent  = ScriptEvent(timeSec = atSec, pose = poseId, duration = 0.5f)
        val newScript = project.script.copy(
            events = (project.script.events + newEvent).sortedBy { it.timeSec }
        )
        updateActive { it.copy(script = newScript) }
        _scriptText.value = json.encodeToString(newScript)
        _scriptError.value = null
        saveActiveProject()
    }

    // ── Appearance ────────────────────────────────────────────────────────────

    fun updateAppearance(appearance: AppearanceSettings) {
        updateActive { it.copy(appearance = appearance) }
        saveActiveProject()
    }

    fun updateExportSettings(settings: ExportSettings) {
        updateActive { it.copy(exportSettings = settings) }
        saveActiveProject()
    }

    fun updateAmplitudeSettings(settings: AmplitudeSettings) {
        _amplitudeSettings.value = settings
    }

    // ── Audio import ──────────────────────────────────────────────────────────

    fun importAudio(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isAnalysingAudio.value = true
            val project = _activeProject.value ?: run {
                _isAnalysingAudio.value = false; return@launch
            }

            // Copy to app-private cache so the path is stable across sessions
            val destDir  = File(context.filesDir, "audio").also { it.mkdirs() }
            val fileName = queryFileName(context, uri) ?: "audio_${project.id}.mp3"
            val destFile = File(destDir, "${project.id}_$fileName")

            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // Analyse amplitude envelope
            val envelope = AmplitudeAnalyzer.analyze(destFile.absolutePath,
                project.exportSettings.fps)

            updateActive {
                it.copy(
                    audioFilePath      = destFile.absolutePath,
                    amplitudeEnvelope  = envelope.toList()
                )
            }
            _audioFileName.value = fileName
            saveActiveProject()
            _isAnalysingAudio.value = false
            _message.emit("Audio imported: $fileName (${envelope.size} frames analysed)")
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else null
        }
    }

    // ── Pose library ──────────────────────────────────────────────────────────

    fun savePose(pose: PoseDef) {
        viewModelScope.launch {
            repo.savePose(pose)
            _message.emit("Pose '${pose.name}' saved")
        }
    }

    fun deleteCustomPose(id: String) {
        viewModelScope.launch {
            repo.deleteCustomPose(id)
            _message.emit("Pose deleted")
        }
    }

    // ── Timeline compilation (suspending, returns compiled keyframes) ──────────

    suspend fun compileTimeline(script: AnimScript): List<com.example.engine.BakedKeyframe> =
        withContext(Dispatchers.Default) {
            TimelineCompiler.compile(script) { poseId ->
                // Resolve from DB poses state (already loaded in memory)
                poses.value.find { it.id == poseId }
                    ?: StickFigureRig.BUILT_IN_POSE_INDEX[poseId]
            }
        }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportVideo(context: Context) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            _exportProgress.value = 0f
            _exportedFilePath.value = null
            try {
                val compiled = compileTimeline(project.script)
                val outputPath = VideoExporter.export(
                    context      = context,
                    project      = project,
                    keyframes    = compiled,
                    onProgress   = { _exportProgress.value = it }
                )
                _exportedFilePath.value = outputPath
                _message.emit("Export complete: ${File(outputPath).name}")
            } catch (e: Exception) {
                _message.emit("Export failed: ${e.message}")
            } finally {
                _exportProgress.value = null
            }
        }
    }
}
