package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.db.AppDatabase
import com.example.db.AppRepository
import com.example.db.ProjectSummary
import com.example.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db    = AppDatabase.getInstance(app)
    val repo          = AppRepository(db)
    private val prefs = app.getSharedPreferences("rigscript_prefs", Context.MODE_PRIVATE)
    // E1: Use shared AppJson — was a private Json instance duplicated here, in AppRepository, and AppDatabase
    private val json  get() = AppJson.pretty   // pretty only for the script editor display

    // ── Project list ──────────────────────────────────────────────────────────

    /**
     * E4: Lightweight summary flow for the home screen — reads only the pre-extracted
     * name/date columns, never the full projectJson blob (which can be ~200KB per
     * project after audio analysis). Previously, [projects] decoded ALL project JSON
     * just to display project names and dates on the home screen.
     */
    val projectSummaries: StateFlow<List<ProjectSummary>> = repo.observeProjectSummaries()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Full project list — only needed internally (e.g. initial load). Prefer [projectSummaries] for UI. */
    val projects: StateFlow<List<ProjectDef>> = repo.observeProjects()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _navigateToProject = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToProject: SharedFlow<String> = _navigateToProject.asSharedFlow()

    // ── Active project ────────────────────────────────────────────────────────

    private val _activeProject = MutableStateFlow<ProjectDef?>(null)
    val activeProject: StateFlow<ProjectDef?> = _activeProject.asStateFlow()

    private val _scriptText  = MutableStateFlow("")
    val scriptText: StateFlow<String> = _scriptText.asStateFlow()

    private val _scriptError = MutableStateFlow<String?>(null)
    val scriptError: StateFlow<String?> = _scriptError.asStateFlow()

    // ── Pose library ──────────────────────────────────────────────────────────

    val poses: StateFlow<List<PoseDef>> = repo.observePoses()
        .stateIn(viewModelScope, SharingStarted.Lazily, StickFigureRig.BUILT_IN_POSES)

    // ── Global amplitude settings ─────────────────────────────────────────────

    private val _amplitudeSettings = MutableStateFlow(loadAmplitudeSettings())
    val amplitudeSettings: StateFlow<AmplitudeSettings> = _amplitudeSettings.asStateFlow()
    private var amplitudeSaveJob: Job? = null

    // ── Audio state ───────────────────────────────────────────────────────────

    private val _audioFileName    = MutableStateFlow<String?>(null)
    val audioFileName: StateFlow<String?> = _audioFileName.asStateFlow()

    private val _isAnalysingAudio = MutableStateFlow(false)
    val isAnalysingAudio: StateFlow<Boolean> = _isAnalysingAudio.asStateFlow()

    // ── Export state ──────────────────────────────────────────────────────────

    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _exportedFile = MutableStateFlow<ExportResult?>(null)
    val exportedFile: StateFlow<ExportResult?> = _exportedFile.asStateFlow()

    private var exportJob: Job? = null

    // ── Snackbar ──────────────────────────────────────────────────────────────

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    // ── Debounced persistence ─────────────────────────────────────────────────

    private var saveJob:    Job?   = null
    // B7: Mutex ensures sequential writes — without it, two rapid saveActiveProject()
    // calls could both launch coroutines that write concurrently, and whichever
    // completes last wins (nondeterministic). With the mutex, the second write
    // always captures the most current project state and writes it after the first.
    private val saveMutex: Mutex  = Mutex()
    private var compileJob: Job?  = null

    fun saveActiveProject() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveMutex.withLock {
                // Read INSIDE the lock so we always save the most current state,
                // not the state captured when this coroutine was launched.
                _activeProject.value?.let { repo.saveProject(it) }
            }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            saveMutex.withLock { _activeProject.value?.let { repo.saveProject(it) } }
        }
    }

    // ── Project CRUD ──────────────────────────────────────────────────────────

    fun createProject(name: String = "Untitled") {
        val project = ProjectDef(
            id          = UUID.randomUUID().toString(),
            projectName = name,
            script      = AnimScript.DEMO
        )
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
        viewModelScope.launch {
            // B4: Delete the on-device audio copy so storage doesn't grow indefinitely.
            // Previous behaviour silently accumulated files on every re-import.
            repo.getProject(id)?.audioFilePath?.let { path ->
                withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            }
            repo.deleteProject(id)
        }
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
            // E3: Debounce the updateActive call — previously it fired immediately
            // on every keystroke, triggering a timeline recompile and a playhead
            // reset mid-typing. Now the compile only runs after 400ms of no typing,
            // matching the save debounce cadence.
            compileJob?.cancel()
            compileJob = viewModelScope.launch {
                delay(400)
                updateActive { it.copy(script = parsed) }
            }
        }.onFailure { _scriptError.value = it.message?.take(120) }
        scheduleSave()
    }

    // F1: Import a .json animation script from a file URI.
    fun importScript(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText()
                } ?: run { _message.emit("Couldn't read file"); return@launch }

                val parsed = AppJson.storage.decodeFromString<AnimScript>(content)
                // Cancel any pending compile/save and apply immediately
                compileJob?.cancel(); saveJob?.cancel()
                updateActive { it.copy(script = parsed) }
                _scriptText.value  = AppJson.pretty.encodeToString(parsed)
                _scriptError.value = null
                saveActiveProject()
                _message.emit("Script imported — ${parsed.events.size} events")
            }.onFailure {
                _message.emit("Invalid script file: ${it.message?.take(80)}")
            }
        }
    }

    fun insertScriptEvent(poseId: String, atSec: Float) {
        val project = _activeProject.value ?: return
        val newScript = project.script.copy(
            events = (project.script.events + ScriptEvent(atSec, poseId, 0.5f))
                .sortedBy { it.timeSec }
        )
        compileJob?.cancel()
        updateActive { it.copy(script = newScript) }
        _scriptText.value  = json.encodeToString(newScript)
        _scriptError.value = null
        saveActiveProject()
    }

    // ── Appearance / Export ───────────────────────────────────────────────────

    fun updateAppearance(appearance: AppearanceSettings) {
        updateActive { it.copy(appearance = appearance) }
        scheduleSave()
    }

    fun updateExportSettings(settings: ExportSettings) {
        updateActive { it.copy(exportSettings = settings) }
        scheduleSave()
    }

    // ── Amplitude settings ────────────────────────────────────────────────────

    fun updateAmplitudeSettings(settings: AmplitudeSettings) {
        _amplitudeSettings.value = settings
        amplitudeSaveJob?.cancel()
        amplitudeSaveJob = viewModelScope.launch {
            delay(400)
            prefs.edit().putString(PREF_AMPLITUDE, AppJson.storage.encodeToString(settings)).apply()
        }
    }

    private fun loadAmplitudeSettings(): AmplitudeSettings {
        val raw = prefs.getString(PREF_AMPLITUDE, null) ?: return AmplitudeSettings()
        return runCatching { AppJson.storage.decodeFromString<AmplitudeSettings>(raw) }
            .getOrDefault(AmplitudeSettings())
    }

    // ── Audio import ──────────────────────────────────────────────────────────

    fun importAudio(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isAnalysingAudio.value = true
            val project = _activeProject.value ?: run { _isAnalysingAudio.value = false; return@launch }

            val destDir  = File(context.filesDir, "audio").also { it.mkdirs() }
            val fileName = queryFileName(context, uri) ?: "audio_${project.id}.mp3"

            // B4: Delete the previous audio file before writing the new one.
            // Without this, each new import added a 5-50MB file and never removed the old one.
            withContext(Dispatchers.IO) {
                project.audioFilePath?.let { runCatching { File(it).delete() } }
            }

            val destFile = File(destDir, "${project.id}_$fileName")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val result = AmplitudeAnalyzer.analyze(destFile.absolutePath)
            updateActive {
                it.copy(
                    audioFilePath      = destFile.absolutePath,
                    audioDurationSec   = result.durationSec,
                    amplitudeEnvelope  = result.envelope.toList(),
                    mouthShapeEnvelope = result.mouthShapes.toList()
                )
            }
            _audioFileName.value = fileName
            saveActiveProject()
            _isAnalysingAudio.value = false
            _message.emit("Audio imported: $fileName (${"%.1f".format(result.durationSec)}s)")
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
        viewModelScope.launch { repo.savePose(pose); _message.emit("Pose '${pose.name}' saved") }
    }

    fun deleteCustomPose(id: String) {
        viewModelScope.launch { repo.deleteCustomPose(id); _message.emit("Pose deleted") }
    }

    // ── Timeline compilation ──────────────────────────────────────────────────

    suspend fun compileTimeline(script: AnimScript): List<BakedKeyframe> =
        withContext(Dispatchers.Default) {
            TimelineCompiler.compile(script) { poseId ->
                poses.value.find { it.id == poseId } ?: StickFigureRig.BUILT_IN_POSE_INDEX[poseId]
            }
        }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportVideo(context: Context) {
        val project = _activeProject.value ?: return
        val pm       = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RigScript:Export")
        exportJob = viewModelScope.launch {
            wakeLock.acquire(3 * 60 * 60 * 1000L)
            _exportProgress.value = 0f
            _exportedFile.value   = null
            try {
                val compiled = compileTimeline(project.script)
                val result = VideoExporter.export(
                    context           = context,
                    project           = project,
                    keyframes         = compiled,
                    amplitudeSettings = _amplitudeSettings.value,
                    onProgress        = { _exportProgress.value = it }
                )
                _exportedFile.value = result
                _message.emit("Export complete: ${result.location}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _message.emit("Export cancelled")
                throw e
            } catch (e: Exception) {
                _message.emit("Export failed: ${e.message}")
            } finally {
                _exportProgress.value = null
                exportJob = null
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _exportProgress.value = null
    }

    companion object { private const val PREF_AMPLITUDE = "amplitude_settings_json" }
}
