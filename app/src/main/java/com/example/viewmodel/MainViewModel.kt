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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val prefs = app.getSharedPreferences("rigscript_prefs", Context.MODE_PRIVATE)

    // ── Project list ──────────────────────────────────────────────────────────

    val projects: StateFlow<List<ProjectDef>> = repo.observeProjects()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Emits the ID of a project right after it's created, so the nav graph can
     * navigate into it. Deliberately NOT inferred from `projects.size` changes —
     * that heuristic also fires on the initial load of an existing project list
     * (size goes from 0 -> N once Room's Flow emits), which would force-navigate
     * into a random project on every app launch.
     */
    private val _navigateToProject = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToProject: SharedFlow<String> = _navigateToProject.asSharedFlow()

    // ── Active project ────────────────────────────────────────────────────────

    private val _activeProject = MutableStateFlow<ProjectDef?>(null)
    val activeProject: StateFlow<ProjectDef?> = _activeProject.asStateFlow()

    private val _scriptText = MutableStateFlow("")
    val scriptText: StateFlow<String> = _scriptText.asStateFlow()

    private val _scriptError = MutableStateFlow<String?>(null)
    val scriptError: StateFlow<String?> = _scriptError.asStateFlow()

    // ── Pose library ──────────────────────────────────────────────────────────

    val poses: StateFlow<List<PoseDef>> = repo.observePoses()
        .stateIn(viewModelScope, SharingStarted.Lazily, StickFigureRig.BUILT_IN_POSES)

    // ── Global amplitude settings (persisted to SharedPreferences) ─────────────

    private val _amplitudeSettings = MutableStateFlow(loadAmplitudeSettings())
    val amplitudeSettings: StateFlow<AmplitudeSettings> = _amplitudeSettings.asStateFlow()
    private var amplitudeSaveJob: Job? = null

    // ── Audio state ───────────────────────────────────────────────────────────

    private val _audioFileName = MutableStateFlow<String?>(null)
    val audioFileName: StateFlow<String?> = _audioFileName.asStateFlow()

    private val _isAnalysingAudio = MutableStateFlow(false)
    val isAnalysingAudio: StateFlow<Boolean> = _isAnalysingAudio.asStateFlow()

    // ── Export state ──────────────────────────────────────────────────────────

    private val _exportProgress = MutableStateFlow<Float?>(null)  // null = idle
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _exportedFile = MutableStateFlow<ExportResult?>(null)
    val exportedFile: StateFlow<ExportResult?> = _exportedFile.asStateFlow()

    // ── Snackbar / toasts ─────────────────────────────────────────────────────

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    // ── Debounced project persistence ───────────────────────────────────────────
    // Slider-driven settings (Appearance/Export tabs) call scheduleSave(), which
    // debounces writes — without this, every onValueChange tick during a drag
    // would JSON-encode and write the ENTIRE project (including a potentially
    // large amplitudeEnvelope) to SQLite.

    private var saveJob: Job? = null

    /** Immediate flush. Use for discrete actions: rename, audio import, pose insert, leaving the screen. */
    fun saveActiveProject() {
        saveJob?.cancel()
        saveJob = null
        val project = _activeProject.value ?: return
        viewModelScope.launch { repo.saveProject(project) }
    }

    /** Debounced flush (400ms). Use for continuous interactions like sliders. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            _activeProject.value?.let { repo.saveProject(it) }
        }
    }

    // ── Project CRUD ──────────────────────────────────────────────────────────

    fun createProject(name: String = "Untitled") {
        val project = ProjectDef(id = UUID.randomUUID().toString(), projectName = name,
            script = AnimScript.DEMO)
        viewModelScope.launch {
            repo.saveProject(project)
            _navigateToProject.emit(project.id)
        }
    }

    fun loadProject(id: String) {
        viewModelScope.launch {
            val project = repo.getProject(id) ?: return@launch
            _activeProject.value = project
            _scriptText.value    = json.encodeToString(project.script)
            _scriptError.value   = null
            _audioFileName.value = project.audioFilePath?.let { File(it).name }
            _exportedFile.value  = null
        }
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

    fun onScriptTextChanged(raw: String) {
        _scriptText.value = raw
        runCatching {
            val parsed = json.decodeFromString<AnimScript>(raw)
            _scriptError.value = null
            updateActive { it.copy(script = parsed) }
        }.onFailure { _scriptError.value = it.message?.take(120) }
        scheduleSave()
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

    // ── Appearance / Export (debounced — sliders fire these continuously) ──────

    fun updateAppearance(appearance: AppearanceSettings) {
        updateActive { it.copy(appearance = appearance) }
        scheduleSave()
    }

    fun updateExportSettings(settings: ExportSettings) {
        updateActive { it.copy(exportSettings = settings) }
        scheduleSave()
    }

    // ── Global amplitude settings ───────────────────────────────────────────────

    fun updateAmplitudeSettings(settings: AmplitudeSettings) {
        _amplitudeSettings.value = settings
        amplitudeSaveJob?.cancel()
        amplitudeSaveJob = viewModelScope.launch {
            delay(400)
            prefs.edit().putString(PREF_AMPLITUDE, json.encodeToString(settings)).apply()
        }
    }

    private fun loadAmplitudeSettings(): AmplitudeSettings {
        val raw = prefs.getString(PREF_AMPLITUDE, null) ?: return AmplitudeSettings()
        return runCatching { json.decodeFromString<AmplitudeSettings>(raw) }
            .getOrDefault(AmplitudeSettings())
    }

    // ── Audio import ──────────────────────────────────────────────────────────

    fun importAudio(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isAnalysingAudio.value = true
            val project = _activeProject.value ?: run {
                _isAnalysingAudio.value = false; return@launch
            }

            val destDir  = File(context.filesDir, "audio").also { it.mkdirs() }
            val fileName = queryFileName(context, uri) ?: "audio_${project.id}.mp3"
            val destFile = File(destDir, "${project.id}_$fileName")

            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val result = AmplitudeAnalyzer.analyze(destFile.absolutePath)

            updateActive {
                it.copy(
                    audioFilePath     = destFile.absolutePath,
                    audioDurationSec  = result.durationSec,
                    amplitudeEnvelope = result.envelope.toList()
                )
            }
            _audioFileName.value = fileName
            saveActiveProject()
            _isAnalysingAudio.value = false
            _message.emit("Audio imported: $fileName (${"%.1f".format(result.durationSec)}s, ${result.envelope.size} frames)")
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

    // ── Timeline compilation ─────────────────────────────────────────────────

    suspend fun compileTimeline(script: AnimScript): List<BakedKeyframe> =
        withContext(Dispatchers.Default) {
            TimelineCompiler.compile(script) { poseId ->
                poses.value.find { it.id == poseId }
                    ?: StickFigureRig.BUILT_IN_POSE_INDEX[poseId]
            }
        }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportVideo(context: Context) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            _exportProgress.value = 0f
            _exportedFile.value   = null
            try {
                val compiled = compileTimeline(project.script)
                val result = VideoExporter.export(
                    context    = context,
                    project    = project,
                    keyframes  = compiled,
                    onProgress = { _exportProgress.value = it }
                )
                _exportedFile.value = result
                _message.emit("Export complete: ${result.location}")
            } catch (e: Exception) {
                _message.emit("Export failed: ${e.message}")
            } finally {
                _exportProgress.value = null
            }
        }
    }

    companion object {
        private const val PREF_AMPLITUDE = "amplitude_settings_json"
    }
}
