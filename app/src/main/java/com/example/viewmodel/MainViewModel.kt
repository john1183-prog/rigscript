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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

    /** Estimated seconds remaining — null while progress is null (not exporting) or before enough frames have been measured. See VideoExporter.computeEtaSec. */
    private val _exportEtaSec = MutableStateFlow<Float?>(null)
    val exportEtaSec: StateFlow<Float?> = _exportEtaSec.asStateFlow()

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
            var project = repo.getProject(id) ?: return@launch

            // V2: migrate a pre-envelope-file project to disk-backed storage
            // the first time it's opened after upgrading — see EnvelopeStore's
            // doc comment for why this migration exists. Done here rather
            // than in AppRepository to match the existing convention: file
            // I/O lives in the ViewModel layer (see importAudio below),
            // AppRepository stays DB-only with no filesystem/Context dependency.
            @Suppress("DEPRECATION")
            if (project.amplitudeEnvelopePath == null && project.amplitudeEnvelope.isNotEmpty()) {
                val appCtx = getApplication<Application>()
                @Suppress("DEPRECATION")
                val ampPath = withContext(Dispatchers.IO) {
                    EnvelopeStore.writeAmplitude(appCtx, project.id, project.amplitudeEnvelope.toFloatArray())
                }
                @Suppress("DEPRECATION")
                val mouthPath = withContext(Dispatchers.IO) {
                    EnvelopeStore.writeMouthShapes(appCtx, project.id, project.mouthShapeEnvelope.toIntArray())
                }
                project = project.copy(
                    amplitudeEnvelopePath  = ampPath,
                    mouthShapeEnvelopePath = mouthPath,
                    amplitudeEnvelope      = emptyList(),
                    mouthShapeEnvelope     = emptyList()
                )
                repo.saveProject(project)
            }

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
            // V2: also delete the envelope .bin files for the same reason — these
            // now live outside the JSON blob (see EnvelopeStore), so deleting the
            // DB row alone would leak them silently.
            repo.getProject(id)?.let { proj ->
                withContext(Dispatchers.IO) {
                    proj.audioFilePath?.let { runCatching { File(it).delete() } }
                    EnvelopeStore.deletePaths(proj.amplitudeEnvelopePath, proj.mouthShapeEnvelopePath)
                    proj.referenceOverlay.imagePath?.let { runCatching { File(it).delete() } }
                    proj.backgroundMusic.musicFilePath?.let { runCatching { File(it).delete() } }
                }
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

            // V2: envelopes go to disk via EnvelopeStore instead of inlining
            // into the ProjectDef JSON blob — this was the #1 architectural
            // debt item from the V1->V2 handoff (a 7.5-min project was
            // previously ~150-200KB of JSON floats/ints inside one SQLite
            // column, decoded in full on every load). Delete any previous
            // envelope files first for the same reason the audio file above
            // is deleted before re-import — otherwise every re-import leaks
            // the old .bin files.
            withContext(Dispatchers.IO) {
                EnvelopeStore.deletePaths(project.amplitudeEnvelopePath, project.mouthShapeEnvelopePath)
            }
            val ampPath = withContext(Dispatchers.IO) {
                EnvelopeStore.writeAmplitude(context, project.id, result.envelope)
            }
            val mouthPath = withContext(Dispatchers.IO) {
                EnvelopeStore.writeMouthShapes(context, project.id, result.mouthShapes)
            }

            updateActive {
                it.copy(
                    audioFilePath          = destFile.absolutePath,
                    audioDurationSec       = result.durationSec,
                    amplitudeEnvelopePath  = ampPath,
                    mouthShapeEnvelopePath = mouthPath
                )
            }
            _audioFileName.value = fileName
            saveActiveProject()
            _isAnalysingAudio.value = false
            _message.emit("Audio imported: $fileName (${"%.1f".format(result.durationSec)}s)")
        }
    }

    // ── Reference overlay (V2) ────────────────────────────────────────────────
    // File I/O lives here, not in AppRepository — same convention as audio
    // import above (see ProjectDef's doc comment on that boundary).

    fun importReferenceImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val project = _activeProject.value ?: return@launch
            val destDir  = File(context.filesDir, "overlay_images").also { it.mkdirs() }
            val fileName = queryFileName(context, uri) ?: "overlay_${project.id}.jpg"

            // Delete the previous overlay image before writing the new one —
            // same leak-prevention reasoning as importAudio's B4 fix.
            withContext(Dispatchers.IO) {
                project.referenceOverlay.imagePath?.let { runCatching { File(it).delete() } }
            }

            val destFile = File(destDir, "${project.id}_$fileName")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            updateReferenceOverlay {
                it.copy(type = ReferenceOverlay.OverlayType.IMAGE, imagePath = destFile.absolutePath)
            }
            saveActiveProject()
            _message.emit("Reference image imported: $fileName")
        }
    }

    fun removeReferenceImage(context: Context) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                project.referenceOverlay.imagePath?.let { runCatching { File(it).delete() } }
            }
            updateReferenceOverlay {
                it.copy(type = ReferenceOverlay.OverlayType.NONE, imagePath = null)
            }
            saveActiveProject()
        }
    }

    fun updateReferenceOverlay(transform: (ReferenceOverlay) -> ReferenceOverlay) {
        updateActive { it.copy(referenceOverlay = transform(it.referenceOverlay)) }
        scheduleSave()
    }

    // ── Background music (V2) ─────────────────────────────────────────────────
    // Same file-handling boundary as audio/reference-overlay import above —
    // file I/O lives here, AppRepository stays DB-only.

    fun importBackgroundMusic(context: Context, uri: Uri) {
        viewModelScope.launch {
            val project = _activeProject.value ?: return@launch
            val destDir  = File(context.filesDir, "background_music").also { it.mkdirs() }
            val fileName = queryFileName(context, uri) ?: "music_${project.id}"

            withContext(Dispatchers.IO) {
                project.backgroundMusic.musicFilePath?.let { runCatching { File(it).delete() } }
            }

            val destFile = File(destDir, "${project.id}_$fileName")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            updateBackgroundMusic { it.copy(musicFilePath = destFile.absolutePath) }
            saveActiveProject()
            _message.emit("Background music imported: $fileName")
        }
    }

    fun removeBackgroundMusic(context: Context) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                project.backgroundMusic.musicFilePath?.let { runCatching { File(it).delete() } }
            }
            updateBackgroundMusic { it.copy(musicFilePath = null) }
            saveActiveProject()
        }
    }

    fun updateBackgroundMusic(transform: (BackgroundMusicSettings) -> BackgroundMusicSettings) {
        updateActive { it.copy(backgroundMusic = transform(it.backgroundMusic)) }
        scheduleSave()
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
            _exportEtaSec.value   = null
            _exportedFile.value   = null
            try {
                val compiled = compileTimeline(project.script)
                val result = VideoExporter.export(
                    context           = context,
                    project           = project,
                    keyframes         = compiled,
                    amplitudeSettings = _amplitudeSettings.value,
                    onProgress        = { progress, eta -> _exportProgress.value = progress; _exportEtaSec.value = eta }
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
                _exportEtaSec.value   = null
                exportJob = null
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _exportProgress.value = null
        _exportEtaSec.value   = null
    }

    companion object { private const val PREF_AMPLITUDE = "amplitude_settings_json" }
}
