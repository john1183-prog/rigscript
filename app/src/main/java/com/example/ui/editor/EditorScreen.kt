package com.example.ui.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppearanceSettings
import com.example.data.ExportSettings
import com.example.data.ScriptEvent
import com.example.engine.AudioPlayer
import com.example.engine.BakedKeyframe
import com.example.engine.EnvelopeStore
import com.example.engine.ExportResult
import com.example.ui.canvas.AnimationSurfaceView
import com.example.ui.components.ColorPickerRow
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenPoseLibrary: () -> Unit,
    onOpenScriptEditor: (Float) -> Unit
) {
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val project      by vm.activeProject.collectAsStateWithLifecycle()
    val scriptText   by vm.scriptText.collectAsStateWithLifecycle()
    val scriptError  by vm.scriptError.collectAsStateWithLifecycle()
    val scriptWarnings by vm.scriptWarnings.collectAsStateWithLifecycle()
    val ampSettings  by vm.amplitudeSettings.collectAsStateWithLifecycle()
    val appearancePresets by vm.appearancePresets.collectAsStateWithLifecycle()
    val isAnalysing  by vm.isAnalysingAudio.collectAsStateWithLifecycle()
    val audioName    by vm.audioFileName.collectAsStateWithLifecycle()
    val exportProg   by vm.exportProgress.collectAsStateWithLifecycle()
    val exportEta    by vm.exportEtaSec.collectAsStateWithLifecycle()
    val exportedFile by vm.exportedFile.collectAsStateWithLifecycle()
    val messages     = vm.message

    // Preview-only aspect toggle — lets the person see how a script
    // composes in BOTH orientations before committing to export, without
    // changing the project's actual configured export.exportSettings.
    // Initialized from the project's own aspect once (not re-synced after
    // that), so switching projects doesn't silently reset a toggle the
    // person is actively using.
    var previewAspect by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(project?.id) {
        previewAspect = project?.exportSettings?.aspectRatio ?: "9:16"
    }

    LaunchedEffect(projectId) { vm.loadProject(projectId) }

    val snackState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        messages.collect { msg -> snackState.showSnackbar(msg) }
    }

    // ── Compiled timeline ──────────────────────────────────────────────────────
    var keyframes by remember { mutableStateOf<List<BakedKeyframe>>(emptyList()) }
    // Tracks which keyframes list has already been pushed into the surface view's
    // engine, by REFERENCE. compileTimeline always returns a fresh list/arrays,
    // so reference inequality reliably means "the script actually changed" —
    // letting us call loadTimeline exactly once per change, from inside the
    // AndroidView `update` lambda (which is guaranteed to run after `factory`,
    // unlike a bare LaunchedEffect which can race ahead of view creation).
    var lastLoadedKeyframes by remember { mutableStateOf<List<BakedKeyframe>?>(null) }
    var surfaceView by remember { mutableStateOf<AnimationSurfaceView?>(null) }

    LaunchedEffect(project?.script) {
        project?.script?.let { script -> keyframes = vm.compileTimeline(script) }
    }

    // Single source of truth for the amplitude envelope — read from
    // EnvelopeStore (falling back to the deprecated inline field for a
    // project saved before the V2 migration). Shared by audio-preview
    // loading, the waveform display, and idle-fidget scheduling below,
    // instead of each recomputing it separately.
    val envelopeArray = remember(project?.amplitudeEnvelopePath, project?.audioFilePath) {
        project?.let { p ->
            @Suppress("DEPRECATION")
            EnvelopeStore.readAmplitudeWithFallback(p.amplitudeEnvelopePath, p.amplitudeEnvelope)
        } ?: FloatArray(0)
    }

    // Keeps blink/fidget schedules in sync when AmplitudeSettings changes
    // (naturalBlinkEnabled, idleFidgetEnabled, interval ranges, ...) without
    // requiring an unrelated script edit to happen to reload them — see
    // refreshBlinkAndFidgetSchedules's own doc comment for why this is a
    // separate effect rather than folded into loadTimeline's gating.
    LaunchedEffect(project?.script?.blinkEvents, project?.audioDurationSec, envelopeArray, ampSettings, surfaceView) {
        surfaceView?.refreshBlinkAndFidgetSchedules(
            project?.script?.blinkEvents ?: emptyList(),
            project?.audioDurationSec ?: 0f,
            envelopeArray
        )
    }

    // ── Audio player (shared singleton; render thread samples it directly) ─────
    val audioPlayer = remember { AudioPlayer.getInstance() }
    var isAudioPlaying by remember { mutableStateOf(false) }

    // V2 — background music. Separate player from narration (see
    // BackgroundMusicPlayer's doc comment for why); driven in lockstep with
    // audioPlayer's transport below rather than independently.
    val musicPlayer = remember { com.example.engine.BackgroundMusicPlayer.getInstance() }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.pause(); musicPlayer.pause() }
    }

    LaunchedEffect(project?.audioFilePath) {
        project?.let { p ->
            if (p.audioFilePath != null) {
                @Suppress("DEPRECATION")
                val mouthShapes = EnvelopeStore.readMouthShapesWithFallback(p.mouthShapeEnvelopePath, p.mouthShapeEnvelope)
                if (envelopeArray.isNotEmpty()) {
                    audioPlayer.load(
                        filePath    = p.audioFilePath,
                        envelope    = envelopeArray,
                        mouthShapes = mouthShapes
                    )
                }
            }
        }
    }

    // V2 — (re)load background music whenever its file path changes; volume/
    // loop changes are applied live below without a reload.
    LaunchedEffect(project?.backgroundMusic?.musicFilePath) {
        val proj = project
        proj?.backgroundMusic?.musicFilePath?.let { path ->
            musicPlayer.load(path, proj.backgroundMusic.volume, proj.backgroundMusic.loop)
        }
    }
    LaunchedEffect(project?.backgroundMusic?.volume) {
        project?.backgroundMusic?.volume?.let { musicPlayer.setVolume(it) }
    }
    LaunchedEffect(project?.backgroundMusic?.loop) {
        project?.backgroundMusic?.let { musicPlayer.setLooping(it.loop) }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importAudio(context, it) } }

    // V2 — manual reference overlay image picker, same pattern as audioPicker.
    val referenceImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importReferenceImage(context, it) } }

    // V2 — background music picker, same pattern as audioPicker.
    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importBackgroundMusic(context, it) } }

    // V2 — sound effect library picker, same pattern as audioPicker.
    val soundEffectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importSoundEffect(context, it) } }

    // F1: Script import — pick any .json file and load it as the animation script
    val scriptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importScript(context, it) } }

    // ── Export — permission gate for API < 29 ──────────────────────────────────
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.exportVideo(context)
        else scope.launch { snackState.showSnackbar("Storage permission is needed to save the export") }
    }
    fun triggerExport() {
        if (Build.VERSION.SDK_INT >= 29) vm.exportVideo(context)
        else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // Same permission gate, for the low-res preview render — a separate
    // launcher/trigger rather than reusing triggerExport()'s, since the two
    // need to call different ViewModel functions (exportPreview vs exportVideo).
    val previewStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.exportPreview(context)
        else scope.launch { snackState.showSnackbar("Storage permission is needed to save the preview") }
    }
    fun triggerExportPreview() {
        if (Build.VERSION.SDK_INT >= 29) vm.exportPreview(context)
        else previewStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // Same permission gate again, for the GLES export diagnostic — see
    // MainViewModel.exportGlesSmokeTest's doc comment. Temporary;
    // remove alongside that function once later phases replace it.
    val glesTestStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.exportGlesSmokeTest(context)
        else scope.launch { snackState.showSnackbar("Storage permission is needed to save the test clip") }
    }
    fun triggerGlesSmokeTest() {
        if (Build.VERSION.SDK_INT >= 29) vm.exportGlesSmokeTest(context)
        else glesTestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // Stress-test mode of the same diagnostic — a separate button rather
    // than a gesture (long-press etc.) on the one above, deliberately:
    // this environment can't visually verify Compose gesture-detection
    // code before it ships, and a stray long-press accidentally firing a
    // multi-minute run mid-iteration would be a real annoyance, not just
    // a cosmetic risk. Two unambiguous buttons instead.
    val glesStressTestStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.exportGlesSmokeTest(context, stressTest = true)
        else scope.launch { snackState.showSnackbar("Storage permission is needed to save the test clip") }
    }
    fun triggerGlesStressTest() {
        if (Build.VERSION.SDK_INT >= 29) vm.exportGlesSmokeTest(context, stressTest = true)
        else glesStressTestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun openExport(uri: Uri) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            })
        }.onFailure { scope.launch { snackState.showSnackbar("No app found to open the video") } }
    }

    fun shareExport(uri: Uri) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(Intent.createChooser(send, "Share video"))
        }.onFailure { scope.launch { snackState.showSnackbar("Unable to share the video") } }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    // F3: Scrubber — current position in seconds, polled every 100ms while playing
    var scrubberPos by remember { mutableStateOf(0f) }
    val totalDuration = remember(project) {
        project?.audioDurationSec?.takeIf { it > 0f }
            ?: project?.script?.events?.maxOfOrNull { it.timeSec + it.duration }?.let { it + 1f }
            ?: 10f
    }
    LaunchedEffect(isAudioPlaying) {
        while (isAudioPlaying) {
            kotlinx.coroutines.delay(100)
            scrubberPos = surfaceView?.currentTimeSec() ?: scrubberPos
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.saveActiveProject(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                title = {
                    Text(project?.projectName ?: "Editor", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                actions = {
                    IconButton(onClick = onOpenPoseLibrary) {
                        Icon(Icons.Default.AccessibilityNew, "Pose Library")
                    }
                    if (exportProg == null) {
                        IconButton(onClick = { triggerExport() }) {
                            Icon(Icons.Default.Movie, "Export")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->

        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── Animation canvas ───────────────────────────────────────────────
            // Fixed 38% height across all tabs: script editing now has its own
            // dedicated full-screen destination (ScriptEditorScreen), so the
            // inline 14% canvas height hack and imePadding layout jumps are eliminated.
            // Preview aspect toggle — tap-only segmented control, same
            // interaction discipline as every other control added this
            // project (no gestures). Only shown once a project is loaded.
            if (project != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    listOf("9:16" to "Portrait", "16:9" to "Landscape").forEach { (value, label) ->
                        FilterChip(
                            selected = previewAspect == value,
                            onClick = { previewAspect = value },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .background(Color(project?.appearance?.previewBgColor?.toInt() ?: 0xFF1A1A2E.toInt())),
                contentAlignment = Alignment.Center
            ) {
                // Aspect-locked inner box — the outer box above still fills
                // the full available area (so the background doesn't leave
                // an awkward gap), while this one constrains the actual
                // rendered content to precisely the toggled aspect ratio,
                // matching ExportSettings.dimensions()'s corrected width/
                // height exactly rather than whatever shape happens to be
                // left over from the surrounding layout.
                val (aspectW, aspectH) = remember(previewAspect, project?.exportSettings?.resolution) {
                    com.example.data.ExportSettings(
                        aspectRatio = previewAspect ?: "9:16",
                        resolution  = project?.exportSettings?.resolution ?: "1080p"
                    ).dimensions()
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(aspectW.toFloat() / aspectH.toFloat(), matchHeightConstraintsFirst = true),
                    factory = { ctx ->
                        AnimationSurfaceView(ctx).also { sv ->
                            surfaceView = sv
                            sv.setAppearance(project?.appearance ?: AppearanceSettings())
                            sv.setAmplitudeSettings(ampSettings)
                            sv.setAudioPlayer(audioPlayer)
                            sv.setReferenceOverlay(project?.referenceOverlay ?: com.example.data.ReferenceOverlay())
                            sv.setSoundEffectLibrary(project?.soundEffects ?: emptyList())
                        }
                    },
                    update = { sv ->
                        if (keyframes !== lastLoadedKeyframes) {
                            sv.loadTimeline(
                                keyframes,
                                blinkTimes      = project?.script?.blinkEvents ?: emptyList(),
                                durationSec     = project?.audioDurationSec ?: 0f,
                                fidgetEnvelope  = envelopeArray,
                                captionCues     = project?.script?.let { com.example.engine.TimelineCompiler.extractCaptions(it) } ?: emptyList(),
                                soundEffectCues = project?.script?.let { com.example.engine.TimelineCompiler.extractSoundEffectCues(it) } ?: emptyList(),
                                overlayLayers   = project?.script?.let { com.example.engine.TimelineCompiler.extractOverlayLayers(it) } ?: emptyList()
                            )
                            lastLoadedKeyframes = keyframes
                        }
                        project?.appearance?.let { sv.setAppearance(it) }
                        sv.setAmplitudeSettings(ampSettings)
                        project?.referenceOverlay?.let { sv.setReferenceOverlay(it) }
                        sv.setSoundEffectLibrary(project?.soundEffects ?: emptyList())
                    },
                    onRelease = { it.release() }
                )

                exportProg?.let { progress ->
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(progress = { progress })
                            Spacer(Modifier.height(8.dp))
                            Text(
                                exportEta?.let { "About ${formatEtaSeconds(it)} left" } ?: "Exporting…",
                                color = Color.White
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { vm.cancelExport() }) {
                                Text("Cancel", color = Color.White)
                            }
                        }
                    }
                }
            }

            // ── Audio bar ──────────────────────────────────────────────────────
            AudioBar(
                audioName    = audioName,
                isPlaying    = isAudioPlaying,
                isAnalysing  = isAnalysing,
                onPickAudio  = { audioPicker.launch(arrayOf("audio/*")) },
                onPlayPause  = {
                    if (isAudioPlaying) {
                        audioPlayer.pause()
                        musicPlayer.pause()
                        surfaceView?.pause()
                    } else {
                        audioPlayer.play()
                        musicPlayer.play()
                        surfaceView?.play()
                    }
                    isAudioPlaying = !isAudioPlaying
                },
                onStop = {
                    audioPlayer.pause()
                    audioPlayer.seekTo(0)
                    musicPlayer.pause()
                    musicPlayer.seekTo(0)
                    surfaceView?.stop()
                    isAudioPlaying = false
                }
            )

            // Sole path for changing current playback time from timeline scrubbing/tapping.
            val seekPlayback: (Float) -> Unit = { pos ->
                scrubberPos = pos
                surfaceView?.seekTo(pos)
                if (isAudioPlaying) {
                    audioPlayer.seekTo((pos * 1000).toInt())
                    seekMusicForTimelinePos(musicPlayer, pos, project?.backgroundMusic?.loop == true)
                }
            }

            val scriptEvents = project?.script?.events ?: emptyList()
            val overlayLayersList = project?.script?.overlayLayers ?: emptyList()

            // Consolidated interactive timeline combining amplitude waveform,
            // overlay layer spans, event markers, and direct drag/tap playhead scrubbing.
            ConsolidatedTimeline(
                envelope      = envelopeArray.toList(),
                events        = scriptEvents,
                overlayLayers = overlayLayersList,
                scrubberPos   = scrubberPos,
                totalDuration = totalDuration,
                onSeek        = seekPlayback,
                modifier      = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = MaterialTheme.colorScheme.surface,
                contentColor     = MaterialTheme.colorScheme.primary
            ) {
                Tab(selectedTab == 0, { selectedTab = 0 },
                    text = { Text("Script") }, icon = { Icon(Icons.Default.Code, null, Modifier.size(16.dp)) })
                Tab(selectedTab == 1, { selectedTab = 1 },
                    text = { Text("Appearance") }, icon = { Icon(Icons.Default.Palette, null, Modifier.size(16.dp)) })
                Tab(selectedTab == 2, { selectedTab = 2 },
                    text = { Text("Export") }, icon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) })
            }

            when (selectedTab) {
                0 -> ScriptSummaryPanel(
                    project            = project,
                    scriptText         = scriptText,
                    scriptError        = scriptError,
                    scriptWarnings     = scriptWarnings,
                    currentTimeSec     = scrubberPos,
                    onOpenScriptEditor = onOpenScriptEditor,
                    onImport           = { scriptPicker.launch(arrayOf("application/json", "*/*")) },
                    onCopyPrompt       = {
                        scope.launch {
                            val text = vm.buildPromptForClipboard(context)
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("RigScript AI Prompt", text))
                            vm.notify("AI prompt copied to clipboard")
                        }
                    },
                    modifier           = Modifier.fillMaxSize()
                )
                1 -> AppearancePanel(
                    appearance        = project?.appearance ?: AppearanceSettings(),
                    ampSettings       = ampSettings,
                    referenceOverlay  = project?.referenceOverlay ?: com.example.data.ReferenceOverlay(),
                    backgroundMusic   = project?.backgroundMusic ?: com.example.data.BackgroundMusicSettings(),
                    soundEffects      = project?.soundEffects ?: emptyList(),
                    presets           = appearancePresets,
                    onAppearance      = { vm.updateAppearance(it) },
                    onAmplitude       = { vm.updateAmplitudeSettings(it) },
                    onReferenceOverlay = { vm.updateReferenceOverlay(it) },
                    onPickReferenceImage = { referenceImagePicker.launch(arrayOf("image/*")) },
                    onRemoveReferenceImage = { vm.removeReferenceImage(context) },
                    onBackgroundMusic = { vm.updateBackgroundMusic(it) },
                    onPickBackgroundMusic = { musicPicker.launch(arrayOf("audio/*")) },
                    onRemoveBackgroundMusic = { vm.removeBackgroundMusic(context) },
                    onPickSoundEffect = { soundEffectPicker.launch(arrayOf("audio/*")) },
                    onRemoveSoundEffect = { vm.removeSoundEffect(it) },
                    onSoundEffectVolume = { id, v -> vm.updateSoundEffectVolume(id, v) },
                    onRenameSoundEffect = { oldId, newId -> vm.renameSoundEffect(oldId, newId) },
                    onPickBuiltInSoundEffect = { vm.importBuiltInSoundEffect(context, it) },
                    onAddAllBuiltInSoundEffects = { vm.addAllBuiltInSoundEffects(context) },
                    onSavePreset      = { vm.saveCurrentAppearanceAsPreset(it) },
                    onApplyPreset     = { vm.applyAppearancePreset(it) },
                    onDeletePreset    = { vm.deleteAppearancePreset(it) },
                    modifier          = Modifier.fillMaxSize()
                )
                2 -> ExportPanel(
                    settings     = project?.exportSettings ?: ExportSettings(),
                    exportedFile = exportedFile,
                    onChange     = { vm.updateExportSettings(it) },
                    onExport     = { triggerExport() },
                    onExportPreview = { triggerExportPreview() },
                    onGlesSmokeTest = { triggerGlesSmokeTest() },
                    onGlesStressTest = { triggerGlesStressTest() },
                    onOpen       = { openExport(it) },
                    onShare      = { shareExport(it) },
                    modifier     = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ── Audio bar ─────────────────────────────────────────────────────────────────

@Composable
private fun AudioBar(
    audioName: String?,
    isPlaying: Boolean,
    isAnalysing: Boolean,
    onPickAudio: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onPickAudio, modifier = Modifier.height(36.dp)) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (audioName != null) audioName.take(20) else "Import Audio", fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (isAnalysing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        // Playback (and the pose timeline preview) doesn't require audio — useful
        // for checking script timing before audio is imported.
        IconButton(onClick = onStop, enabled = !isAnalysing) {
            Icon(Icons.Default.Stop, "Stop")
        }
        IconButton(onClick = onPlayPause, enabled = !isAnalysing) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pause" else "Play")
        }
    }
}

// ── Script summary panel ──────────────────────────────────────────────────────

@Composable
private fun ScriptSummaryPanel(
    project: com.example.data.ProjectDef?,
    scriptText: String,
    scriptError: String?,
    scriptWarnings: List<String>,
    currentTimeSec: Float,
    onOpenScriptEditor: (Float) -> Unit,
    onImport: () -> Unit,
    onCopyPrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Script",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val eventCount = project?.script?.events?.size ?: 0
                val overlayCount = project?.script?.overlayLayers?.size ?: 0
                Text(
                    "$eventCount event${if (eventCount == 1) "" else "s"} • $overlayCount overlay${if (overlayCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Button(
                onClick = { onOpenScriptEditor(currentTimeSec) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit Script", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onCopyPrompt, modifier = Modifier.height(30.dp)) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Prompt", fontSize = 11.sp)
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.height(30.dp)) {
                Icon(Icons.Default.FileOpen, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import", fontSize = 11.sp)
            }

            if (scriptError != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Invalid JSON",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (scriptWarnings.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE0A030),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${scriptWarnings.size} warning${if (scriptWarnings.size == 1) "" else "s"}",
                        color = Color(0xFFE0A030),
                        fontSize = 11.sp
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Valid",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Read-only monospace preview card that also navigates on tap
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A14), RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (scriptError != null) MaterialTheme.colorScheme.error
                    else if (scriptWarnings.isNotEmpty()) Color(0xFFE0A030)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp)
                )
                .clickable { onOpenScriptEditor(currentTimeSec) }
                .padding(12.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "PREVIEW",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Text(
                        "Tap to open full editor",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Text(
                    text = scriptText,
                    style = TextStyle(
                        color = Color(0xFFB0C4DE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ── Appearance panel ──────────────────────────────────────────────────────────

@Composable
private fun AppearancePanel(
    appearance: AppearanceSettings,
    ampSettings: com.example.data.AmplitudeSettings,
    referenceOverlay: com.example.data.ReferenceOverlay,
    backgroundMusic: com.example.data.BackgroundMusicSettings,
    soundEffects: List<com.example.data.SoundEffectClip>,
    presets: List<com.example.data.AppearancePreset>,
    onAppearance: (AppearanceSettings) -> Unit,
    onAmplitude: (com.example.data.AmplitudeSettings) -> Unit,
    onReferenceOverlay: ((com.example.data.ReferenceOverlay) -> com.example.data.ReferenceOverlay) -> Unit,
    onPickReferenceImage: () -> Unit,
    onRemoveReferenceImage: () -> Unit,
    onBackgroundMusic: ((com.example.data.BackgroundMusicSettings) -> com.example.data.BackgroundMusicSettings) -> Unit,
    onPickBackgroundMusic: () -> Unit,
    onRemoveBackgroundMusic: () -> Unit,
    onPickSoundEffect: () -> Unit,
    onRemoveSoundEffect: (String) -> Unit,
    onSoundEffectVolume: (String, Float) -> Unit,
    onRenameSoundEffect: (String, String) -> Unit,
    onPickBuiltInSoundEffect: (com.example.data.BuiltInSoundEffect) -> Unit,
    onAddAllBuiltInSoundEffects: () -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (com.example.data.AppearancePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    val categories = listOf("Character", "Scene", "Motion", "Reference", "Audio")

    Column(modifier = modifier) {
        // Switchable saved appearance presets — pinned persistent strip above the
        // category selector so users can switch or save presets at any time.
        PresetsStrip(
            presets = presets,
            onApplyPreset = onApplyPreset,
            onDeletePreset = onDeletePreset,
            onSavePreset = onSavePreset
        )

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        ScrollableTabRow(
            selectedTabIndex = selectedCategory,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 16.dp
        ) {
            categories.forEachIndexed { index, label ->
                Tab(
                    selected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                    text = { Text(label) }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedCategory) {
                0 -> CharacterCategoryContent(
                    appearance = appearance,
                    onAppearance = onAppearance
                )
                1 -> SceneCategoryContent(
                    appearance = appearance,
                    onAppearance = onAppearance
                )
                2 -> MotionCategoryContent(
                    ampSettings = ampSettings,
                    onAmplitude = onAmplitude
                )
                3 -> ReferenceCategoryContent(
                    referenceOverlay = referenceOverlay,
                    onReferenceOverlay = onReferenceOverlay,
                    onPickReferenceImage = onPickReferenceImage,
                    onRemoveReferenceImage = onRemoveReferenceImage
                )
                4 -> AudioCategoryContent(
                    backgroundMusic = backgroundMusic,
                    soundEffects = soundEffects,
                    onBackgroundMusic = onBackgroundMusic,
                    onPickBackgroundMusic = onPickBackgroundMusic,
                    onRemoveBackgroundMusic = onRemoveBackgroundMusic,
                    onPickSoundEffect = onPickSoundEffect,
                    onRemoveSoundEffect = onRemoveSoundEffect,
                    onSoundEffectVolume = onSoundEffectVolume,
                    onRenameSoundEffect = onRenameSoundEffect,
                    onPickBuiltInSoundEffect = onPickBuiltInSoundEffect,
                    onAddAllBuiltInSoundEffects = onAddAllBuiltInSoundEffects
                )
            }
        }
    }
}

// ── Appearance sub-category components ────────────────────────────────────────

@Composable
private fun PresetsStrip(
    presets: List<com.example.data.AppearancePreset>,
    onApplyPreset: (com.example.data.AppearancePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { showSaveDialog = true }, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save current look", fontSize = 12.sp)
            }
            presets.forEach { preset ->
                ElevatedCard(
                    onClick = { onApplyPreset(preset) },
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp)
                    ) {
                        Text(preset.name, fontSize = 12.sp)
                        IconButton(onClick = { onDeletePreset(preset.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Delete preset", Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save current look as preset") },
            text = {
                OutlinedTextField(
                    value = presetName, onValueChange = { presetName = it },
                    label = { Text("Preset name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (presetName.isNotBlank()) onSavePreset(presetName)
                    showSaveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CharacterCategoryContent(
    appearance: AppearanceSettings,
    onAppearance: (AppearanceSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Colors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ColorPickerRow("Figure color", appearance.boneColor) { newColor ->
            onAppearance(appearance.copy(boneColor = newColor, headColor = newColor, jointColor = newColor))
        }
        // F2: Preview and export backgrounds are now independently configurable.
        // Previously one picker set both. Useful for green-screen export (set
        // export background to 0xFF00FF00 while keeping a dark preview background).
        ColorPickerRow("Preview background", appearance.previewBgColor) { newColor ->
            onAppearance(appearance.copy(previewBgColor = newColor))
        }
        ColorPickerRow("Export background", appearance.exportBgColor) { newColor ->
            onAppearance(appearance.copy(exportBgColor = newColor))
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text("Character", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSlider("Scale", appearance.characterScale, 0.5f..2.0f) {
            onAppearance(appearance.copy(characterScale = it))
        }
        LabeledSlider("Stroke width", appearance.boneStrokeNormalized, 0.005f..0.3f) {
            onAppearance(appearance.copy(boneStrokeNormalized = it))
        }
        LabeledSlider("Joint radius", appearance.jointRadiusNormalized, 0.005f..0.03f) {
            onAppearance(appearance.copy(jointRadiusNormalized = it))
        }
        LabeledSlider("Root X", appearance.rootAnchorX, 0.1f..0.9f) {
            onAppearance(appearance.copy(rootAnchorX = it))
        }
        LabeledSlider("Root Y", appearance.rootAnchorY, 0.1f..0.9f) {
            onAppearance(appearance.copy(rootAnchorY = it))
        }

        LabeledSwitch("Show joints (preview)", appearance.showJoints) {
            onAppearance(appearance.copy(showJoints = it))
        }
        LabeledSwitch("Show joints in export", appearance.showJointsOnExport) {
            onAppearance(appearance.copy(showJointsOnExport = it))
        }

        LabeledSwitch("Show grid (preview only)", appearance.showGrid) {
            onAppearance(appearance.copy(showGrid = it))
        }
        LabeledSwitch("Show mouth", appearance.showMouth) {
            onAppearance(appearance.copy(showMouth = it))
        }
        ColorPickerRow("Mouth color", appearance.mouthColor) { newColor ->
            onAppearance(appearance.copy(mouthColor = newColor))
        }
        LabeledSlider("Head size", appearance.headScaleMultiplier, 0.5f..2.0f) {
            onAppearance(appearance.copy(headScaleMultiplier = it))
        }
        LabeledSlider("Neck length", appearance.neckLengthMultiplier, 0.8f..2.5f) {
            onAppearance(appearance.copy(neckLengthMultiplier = it))
        }
        LabeledSwitch("Show eyes", appearance.showEyes) {
            onAppearance(appearance.copy(showEyes = it))
        }
        ColorPickerRow("Eye color", appearance.eyeColor) { newColor ->
            onAppearance(appearance.copy(eyeColor = newColor))
        }
        LabeledSlider("Eye spacing", appearance.eyeSpacingNormalized, 0.15f..0.6f) {
            onAppearance(appearance.copy(eyeSpacingNormalized = it))
        }
        // Range extended to include negative values when the default
        // became -0.03f (AppearanceSettings.kt) — the old 0.0f..0.3f
        // range would have clamped a new project's own default the
        // moment anyone touched this slider.
        LabeledSlider("Eye vertical position", appearance.eyeVerticalOffsetNormalized, -0.1f..0.3f) {
            onAppearance(appearance.copy(eyeVerticalOffsetNormalized = it))
        }
        LabeledSlider("Eye shape (round \u2194 oval)", appearance.eyeAspectRatio, 0.5f..2.0f) {
            onAppearance(appearance.copy(eyeAspectRatio = it))
        }
        ColorPickerRow("Eyebrow color", appearance.eyebrowColor) { newColor ->
            onAppearance(appearance.copy(eyebrowColor = newColor))
        }
        Text("Eyebrows only draw for worried/angry expressions.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        TextButton(onClick = { onAppearance(AppearanceSettings()) }) {
            Text("Reset appearance to defaults")
        }
    }
}

@Composable
private fun SceneCategoryContent(
    appearance: AppearanceSettings,
    onAppearance: (AppearanceSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Scene", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSwitch("Gradient background", appearance.backgroundStyle == "gradient") { on ->
            onAppearance(appearance.copy(backgroundStyle = if (on) "gradient" else "solid"))
        }
        if (appearance.backgroundStyle == "gradient") {
            ColorPickerRow("Gradient bottom color", appearance.backgroundGradientColor) { newColor ->
                onAppearance(appearance.copy(backgroundGradientColor = newColor))
            }
        }
        LabeledSwitch("Show ground line", appearance.showGroundLine) {
            onAppearance(appearance.copy(showGroundLine = it))
        }
        if (appearance.showGroundLine) {
            ColorPickerRow("Ground line color", appearance.groundLineColor) { newColor ->
                onAppearance(appearance.copy(groundLineColor = newColor))
            }
            LabeledSlider("Ground line position", appearance.groundLineYFraction, 0.5f..0.95f) {
                onAppearance(appearance.copy(groundLineYFraction = it))
            }
        }
    }
}

@Composable
private fun MotionCategoryContent(
    ampSettings: com.example.data.AmplitudeSettings,
    onAmplitude: (com.example.data.AmplitudeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Audio Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("These apply to every project — edit shared defaults in Settings too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        LabeledSwitch("Enable amplitude motion", ampSettings.enabled) {
            onAmplitude(ampSettings.copy(enabled = it))
        }
        LabeledSlider("Silence threshold", ampSettings.silenceThreshold, 0.0f..0.5f) {
            onAmplitude(ampSettings.copy(silenceThreshold = it))
        }
        LabeledSlider("Talk torso amplitude (°)", ampSettings.talkTorsoAmplitude, 0f..8f) {
            onAmplitude(ampSettings.copy(talkTorsoAmplitude = it))
        }
        LabeledSlider("Head nod amplitude (°)", ampSettings.talkHeadNodAmplitude, 0f..7f) {
            onAmplitude(ampSettings.copy(talkHeadNodAmplitude = it))
        }
        LabeledSlider("Talk frequency (Hz)", ampSettings.talkFreqHz, 1f..7f) {
            onAmplitude(ampSettings.copy(talkFreqHz = it))
        }
        LabeledSlider("Smoothing", ampSettings.smoothingFactor, 0.0f..0.9f) {
            onAmplitude(ampSettings.copy(smoothingFactor = it))
        }
        LabeledSwitch("Arm sway", ampSettings.armSwayEnabled) {
            onAmplitude(ampSettings.copy(armSwayEnabled = it))
        }
        LabeledSlider("Idle breath amplitude (°)", ampSettings.idleBreathAmplitude, 0f..8f) {
            onAmplitude(ampSettings.copy(idleBreathAmplitude = it))
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text("Motion & Face", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSwitch("Spring feel on every transition", ampSettings.easeAllWithSpring) {
            onAmplitude(ampSettings.copy(easeAllWithSpring = it))
        }
        LabeledSwitch("Natural idle blinking", ampSettings.naturalBlinkEnabled) {
            onAmplitude(ampSettings.copy(naturalBlinkEnabled = it))
        }
        LabeledSwitch("Idle fidget during pauses", ampSettings.idleFidgetEnabled) {
            onAmplitude(ampSettings.copy(idleFidgetEnabled = it))
        }
        if (ampSettings.idleFidgetEnabled) {
            LabeledSlider("Fidget amplitude (°)", ampSettings.fidgetAmplitude, 0.5f..8f) {
                onAmplitude(ampSettings.copy(fidgetAmplitude = it))
            }
        }

        TextButton(onClick = { onAmplitude(com.example.data.AmplitudeSettings()) }) {
            Text("Reset audio response to defaults")
        }
    }
}

@Composable
private fun ReferenceCategoryContent(
    referenceOverlay: com.example.data.ReferenceOverlay,
    onReferenceOverlay: ((com.example.data.ReferenceOverlay) -> com.example.data.ReferenceOverlay) -> Unit,
    onPickReferenceImage: () -> Unit,
    onRemoveReferenceImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Reference Overlay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Manual image or text overlay you position yourself — never touched by AI-generated scripts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val types = listOf(
                com.example.data.ReferenceOverlay.OverlayType.NONE to "None",
                com.example.data.ReferenceOverlay.OverlayType.IMAGE to "Image",
                com.example.data.ReferenceOverlay.OverlayType.TEXT to "Text"
            )
            types.forEach { (type, label) ->
                FilterChip(
                    selected = referenceOverlay.type == type,
                    onClick = { onReferenceOverlay { it.copy(type = type) } },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        if (referenceOverlay.type == com.example.data.ReferenceOverlay.OverlayType.IMAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickReferenceImage) {
                    Icon(Icons.Default.Image, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (referenceOverlay.imagePath != null) "Change image" else "Choose image", fontSize = 12.sp)
                }
                if (referenceOverlay.imagePath != null) {
                    OutlinedButton(onClick = onRemoveReferenceImage) { Text("Remove", fontSize = 12.sp) }
                }
            }
            LabeledSlider("Crop left", referenceOverlay.cropLeft, 0f..0.9f) { v -> onReferenceOverlay { it.copy(cropLeft = v) } }
            LabeledSlider("Crop top", referenceOverlay.cropTop, 0f..0.9f) { v -> onReferenceOverlay { it.copy(cropTop = v) } }
            LabeledSlider("Crop right", referenceOverlay.cropRight, 0.1f..1f) { v -> onReferenceOverlay { it.copy(cropRight = v) } }
            LabeledSlider("Crop bottom", referenceOverlay.cropBottom, 0.1f..1f) { v -> onReferenceOverlay { it.copy(cropBottom = v) } }
        }

        if (referenceOverlay.type == com.example.data.ReferenceOverlay.OverlayType.TEXT) {
            OutlinedTextField(
                value = referenceOverlay.text ?: "",
                onValueChange = { v -> onReferenceOverlay { it.copy(text = v) } },
                label = { Text("Overlay text") },
                modifier = Modifier.fillMaxWidth()
            )
            ColorPickerRow("Text color", referenceOverlay.textColor) { newColor ->
                onReferenceOverlay { it.copy(textColor = newColor) }
            }
            LabeledSwitch("Backdrop behind text", referenceOverlay.showBackdrop) { v ->
                onReferenceOverlay { it.copy(showBackdrop = v) }
            }
        }

        if (referenceOverlay.type != com.example.data.ReferenceOverlay.OverlayType.NONE) {
            LabeledSlider("Position X", referenceOverlay.posX, 0f..1f) { v -> onReferenceOverlay { it.copy(posX = v) } }
            LabeledSlider("Position Y", referenceOverlay.posY, 0f..1f) { v -> onReferenceOverlay { it.copy(posY = v) } }
            LabeledSlider("Size", referenceOverlay.sizeFraction, 0.05f..0.8f) { v -> onReferenceOverlay { it.copy(sizeFraction = v) } }
            LabeledSwitch("In front of figure", referenceOverlay.inFrontOfFigure) { v ->
                onReferenceOverlay { it.copy(inFrontOfFigure = v) }
            }
        }
    }
}

@Composable
private fun AudioCategoryContent(
    backgroundMusic: com.example.data.BackgroundMusicSettings,
    soundEffects: List<com.example.data.SoundEffectClip>,
    onBackgroundMusic: ((com.example.data.BackgroundMusicSettings) -> com.example.data.BackgroundMusicSettings) -> Unit,
    onPickBackgroundMusic: () -> Unit,
    onRemoveBackgroundMusic: () -> Unit,
    onPickSoundEffect: () -> Unit,
    onRemoveSoundEffect: (String) -> Unit,
    onSoundEffectVolume: (String, Float) -> Unit,
    onRenameSoundEffect: (String, String) -> Unit,
    onPickBuiltInSoundEffect: (com.example.data.BuiltInSoundEffect) -> Unit,
    onAddAllBuiltInSoundEffects: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Background Music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Mixed under the narration on export. Previewed here as a separate track, not a live mix — see export for the real mixed result.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickBackgroundMusic) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (backgroundMusic.musicFilePath != null) "Change music" else "Choose music", fontSize = 12.sp)
            }
            if (backgroundMusic.musicFilePath != null) {
                OutlinedButton(onClick = onRemoveBackgroundMusic) { Text("Remove", fontSize = 12.sp) }
            }
        }

        if (backgroundMusic.musicFilePath != null) {
            LabeledSlider("Music volume", backgroundMusic.volume, 0f..1f) { v ->
                onBackgroundMusic { it.copy(volume = v) }
            }
            LabeledSlider("Narration volume", backgroundMusic.narrationVolume, 0f..1f) { v ->
                onBackgroundMusic { it.copy(narrationVolume = v) }
            }
            LabeledSwitch("Loop music to fill video length", backgroundMusic.loop) { v ->
                onBackgroundMusic { it.copy(loop = v) }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text("Sound Effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Import clips here, then trigger them by id from the script (soundEffect field on an event).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        soundEffects.forEach { clip ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = clip.id,
                    onValueChange = { newId -> onRenameSoundEffect(clip.id, newId) },
                    label = { Text("id", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemoveSoundEffect(clip.id) }) {
                    Icon(Icons.Default.Delete, "Remove ${clip.id}")
                }
            }
            LabeledSlider("Volume", clip.volume, 0f..1f) { v -> onSoundEffectVolume(clip.id, v) }
        }

        OutlinedButton(onClick = onPickSoundEffect) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add sound effect", fontSize = 12.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Or add from the bundled starter library (CC0):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f))
            TextButton(onClick = onAddAllBuiltInSoundEffects, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Add all", fontSize = 12.sp)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(com.example.data.BuiltInSoundEffects.ALL) { builtIn ->
                AssistChip(
                    onClick = { onPickBuiltInSoundEffect(builtIn) },
                    label = { Text(builtIn.label, fontSize = 12.sp) }
                )
            }
        }
    }
}

// ── Export panel ──────────────────────────────────────────────────────────────

@Composable
private fun ExportPanel(
    settings: ExportSettings,
    exportedFile: List<ExportResult>,
    onChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onExportPreview: () -> Unit,
    onGlesSmokeTest: () -> Unit,
    onGlesStressTest: () -> Unit,
    onOpen: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        if (exportedFile.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (exportedFile.size > 1) "Export complete — ${exportedFile.size} files" else "Export complete",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    exportedFile.forEach { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (exportedFile.size > 1) {
                                Text(result.aspectLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text(result.location, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onOpen(result.uri) }, Modifier.weight(1f)) { Text("Open") }
                                OutlinedButton(onClick = { onShare(result.uri) }, Modifier.weight(1f)) { Text("Share") }
                            }
                        }
                    }
                }
            }
        }

        Text("Export Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSwitch("Export both 9:16 and 16:9", settings.dualAspectExport) {
            onChange(settings.copy(dualAspectExport = it))
        }
        if (!settings.dualAspectExport) {
            SegmentedRow("Aspect Ratio", listOf("9:16", "16:9"), settings.aspectRatio) {
                onChange(settings.copy(aspectRatio = it))
            }
        } else {
            Text("Producing two files: 9:16 and 16:9. Timeline is resolved once and shared — only the video encode itself runs twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        SegmentedRow("Resolution", listOf("720p", "1080p"), settings.resolution) {
            onChange(settings.copy(resolution = it))
        }
        SegmentedRow("FPS", listOf("24", "30", "60"), settings.fps.toString()) {
            onChange(settings.copy(fps = it.toInt()))
        }
        LabeledSlider("Bitrate (Mbps)", settings.bitrateMbps.toFloat(), 2f..20f) {
            onChange(settings.copy(bitrateMbps = it.toInt()))
        }
        LabeledSwitch("Embed audio in video", settings.embedAudio) {
            onChange(settings.copy(embedAudio = it))
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExportPreview, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Quick Preview (360p)", fontSize = 13.sp)
        }
        Text("A fast low-res render to sanity-check poses/captions/scene before a full export.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        Spacer(Modifier.height(4.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export Video")
        }

        // GLES export rewrite, through the text phase + stress-test
        // hardening (V2_DECISIONS.md) — plain TextButtons, deliberately
        // not styled like the two real actions above, since neither
        // renders captions/text/figure overlays yet. Remove both once
        // later phases make GLES part of the real export path.
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onGlesSmokeTest, modifier = Modifier.fillMaxWidth()) {
            Text("GLES export test (debug)", fontSize = 12.sp)
        }
        Text("Renders ~3s of the real timeline through the new GPU export path — full figure + background/scene/atmosphere + overlay shapes/glow + camera pan/zoom/shake now, still no captions or text/figure overlays, a diagnostic check, not a real export.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onGlesStressTest, modifier = Modifier.fillMaxWidth()) {
            Text("GLES stress test — full project (debug)", fontSize = 12.sp)
        }
        Text("Same GPU path, but renders this project's actual full length instead of ~3s — a real unattended run, not a quick check. Can take a while for a long project; watch for heat, stalls, or slowdown.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

// ── Reusable UI helpers ───────────────────────────────────────────────────────

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SegmentedRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val isSelected = opt == selected
                OutlinedButton(
                    onClick = { onSelect(opt) },
                    modifier = Modifier.height(34.dp),
                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text(opt, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * F4: Draws the pre-analysed amplitude envelope as a vertical bar chart.
 *
 * Each bar represents one analysis frame (1/30 of a second at the default rate).
 * The playback cursor overlays at [scrubberPos] so the user can visually
 * correlate script events with speech content without counting seconds manually.
 *
 * Only rendered when [envelope] is non-empty (i.e. audio has been imported).
 */
/**
 * Visual overview of script event timing — tap a marker to seek there.
 *
 * Deliberately NOT the full drag-to-reposition / long-press-to-delete /
 * tap-to-add editor originally scoped for this feature. Without a way to
 * compile-check or visually test Compose gesture code in this environment,
 * writing untested drag/long-press handling risks shipping something that
 * looks correct in source but has a real on-device bug (gesture conflicts
 * with the parent scroll, wrong hit-test math, a drag threshold that never
 * fires) that only surfaces on an actual build. Tap is the simplest gesture
 * surface to get right blind. The JSON text field remains the actual edit
 * mechanism either way — this is a navigation/overview aid on top of it, not
 * a replacement for it.
 */
/**
 * Seeks [musicPlayer] to the position that corresponds to the FULL
 * timeline's [timelineSec], NOT that value taken literally — the whole
 * point of a looping background track is usually that it's SHORTER than
 * the narration, so a literal seek past its own end either clamps to the
 * last frame or leaves the player in a stalled state depending on the
 * platform, which is what "music cuts off / loops wrong / goes silent
 * after scrubbing" turned out to actually be: [BackgroundMusicPlayer.seekTo]
 * takes whatever ms it's given with no awareness of the loaded track's own
 * length, and the caller wasn't doing that math.
 *
 * During normal forward playback this was never an issue —
 * [android.media.MediaPlayer.isLooping] handles wraparound on its own once
 * a track reaches its natural end. It only broke on an explicit seek
 * (scrubbing, tapping a timeline strip), which is exactly [seekPlayback]
 * and every place that calls it.
 */
private fun seekMusicForTimelinePos(musicPlayer: com.example.engine.BackgroundMusicPlayer, timelineSec: Float, loop: Boolean) {
    val musicDurMs = musicPlayer.durationMs
    val rawMs = (timelineSec * 1000).toInt()
    when {
        musicDurMs <= 0  -> musicPlayer.seekTo(rawMs) // not loaded/ready yet — nothing to correct against
        loop             -> musicPlayer.seekTo(rawMs % musicDurMs)
        rawMs < musicDurMs -> musicPlayer.seekTo(rawMs)
        else             -> musicPlayer.pause() // past the track's own end and not looping — correctly nothing to play here
    }
}

/**
 * Consolidated interactive timeline surface combining:
 * 1. Audio amplitude waveform (base layer)
 * 2. Overlay layer spans ([com.example.data.OverlayLayer.startSec]..[com.example.data.OverlayLayer.endSec])
 * 3. Script event markers ([ScriptEvent.timeSec] ticks + top pips)
 * 4. High-contrast vertical playhead with handle cap
 *
 * Supports direct press-and-drag scrubbing anywhere across the surface as well
 * as tap-to-nearest-event/overlay snapping. Smoothly expands from 36.dp to 68.dp
 * while actively touched/dragged and settles back after a short post-release delay.
 */
@Composable
private fun ConsolidatedTimeline(
    envelope: List<Float>,
    events: List<ScriptEvent>,
    overlayLayers: List<com.example.data.OverlayLayer>,
    scrubberPos: Float,
    totalDuration: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val trackBgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val baselineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    var nearestLabel by remember(events, overlayLayers) { mutableStateOf<String?>(null) }
    var isPointerDown by remember { mutableStateOf(false) }
    var releaseTick by remember { mutableIntStateOf(0) }
    var isInteracting by remember { mutableStateOf(false) }

    LaunchedEffect(isPointerDown, releaseTick) {
        if (isPointerDown) {
            isInteracting = true
        } else if (releaseTick > 0) {
            kotlinx.coroutines.delay(1200L)
            isInteracting = false
        }
    }

    val canvasHeight by animateDpAsState(
        targetValue = if (isInteracting) 68.dp else 36.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ConsolidatedTimelineHeight"
    )

    val currentEvents by rememberUpdatedState(events)
    val currentLayers by rememberUpdatedState(overlayLayers)
    val currentDuration by rememberUpdatedState(totalDuration)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTimelineClock(scrubberPos)} / ${formatTimelineClock(totalDuration)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = labelColor
            )
            val rightText = nearestLabel ?: "Drag or tap to scrub"
            Text(
                text = rightText,
                style = MaterialTheme.typography.labelSmall,
                color = if (nearestLabel != null) labelColor else labelColor.copy(alpha = 0.6f)
            )
        }

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .pointerInput(Unit) {
                    fun xToTime(x: Float, width: Int, dur: Float): Float {
                        if (dur <= 0f || width <= 0) return 0f
                        return ((x / width.toFloat()).coerceIn(0f, 1f) * dur).coerceIn(0f, dur)
                    }

                    fun updateLabelForTime(timeSec: Float, width: Int, dur: Float) {
                        if (dur <= 0f || width <= 0) {
                            nearestLabel = null
                            return
                        }
                        val thresholdSec = (18.dp.toPx() / width.toFloat()).coerceIn(0f, 1f) * dur
                        val nearEv = currentEvents.minByOrNull { kotlin.math.abs(it.timeSec - timeSec) }
                        val evDiff = nearEv?.let { kotlin.math.abs(it.timeSec - timeSec) } ?: Float.MAX_VALUE
                        val activeLayer = currentLayers.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }
                            ?: currentLayers.minByOrNull { kotlin.math.abs(it.startSec - timeSec) }
                        val layDiff = activeLayer?.let { kotlin.math.abs(it.startSec - timeSec) } ?: Float.MAX_VALUE

                        nearestLabel = when {
                            nearEv != null && evDiff <= thresholdSec && evDiff <= layDiff -> {
                                "${nearEv.pose} @ %.1fs".format(nearEv.timeSec)
                            }
                            activeLayer != null && (
                                (timeSec >= activeLayer.startSec && timeSec <= activeLayer.endSec) ||
                                    layDiff <= thresholdSec
                                ) -> {
                                val label = activeLayer.id.ifBlank { activeLayer.type }
                                "$label  %.1fs\u2013%.1fs".format(activeLayer.startSec, activeLayer.endSec)
                            }
                            else -> null
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPointerDown = true
                        val startX = down.position.x
                        val touchSlop = viewConfiguration.touchSlop
                        var isDragging = false
                        var lastX = startX

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                if (!change.pressed) {
                                    lastX = change.position.x
                                    change.consume()
                                    break
                                }
                                lastX = change.position.x
                                if (!isDragging && kotlin.math.abs(lastX - startX) > touchSlop) {
                                    isDragging = true
                                }
                                if (isDragging) {
                                    change.consume()
                                    val dur = currentDuration
                                    if (dur > 0f && size.width > 0) {
                                        val newTime = xToTime(lastX, size.width, dur)
                                        currentOnSeek(newTime)
                                        updateLabelForTime(newTime, size.width, dur)
                                    }
                                }
                            }

                            if (!isDragging) {
                                val dur = currentDuration
                                val w = size.width
                                if (dur > 0f && w > 0) {
                                    val tapSec = xToTime(lastX, w, dur)
                                    val hitRadiusSec = (18.dp.toPx() / w.toFloat()).coerceIn(0f, 1f) * dur
                                    val nearestEv = currentEvents.minByOrNull {
                                        kotlin.math.abs(it.timeSec - tapSec)
                                    }
                                    val evDiffSec = nearestEv?.let {
                                        kotlin.math.abs(it.timeSec - tapSec)
                                    } ?: Float.MAX_VALUE

                                    val nearestLay = currentLayers.minByOrNull {
                                        kotlin.math.abs(it.startSec - tapSec)
                                    }
                                    val layDiffSec = nearestLay?.let {
                                        kotlin.math.abs(it.startSec - tapSec)
                                    } ?: Float.MAX_VALUE

                                    when {
                                        nearestEv != null && evDiffSec <= hitRadiusSec && evDiffSec <= layDiffSec -> {
                                            val target = nearestEv.timeSec.coerceIn(0f, dur)
                                            currentOnSeek(target)
                                            nearestLabel = "${nearestEv.pose} @ %.1fs".format(nearestEv.timeSec)
                                        }
                                        nearestLay != null && layDiffSec <= hitRadiusSec -> {
                                            val target = nearestLay.startSec.coerceIn(0f, dur)
                                            currentOnSeek(target)
                                            val label = nearestLay.id.ifBlank { nearestLay.type }
                                            nearestLabel = "$label  %.1fs\u2013%.1fs".format(nearestLay.startSec, nearestLay.endSec)
                                        }
                                        else -> {
                                            currentOnSeek(tapSec)
                                            updateLabelForTime(tapSec, w, dur)
                                        }
                                    }
                                }
                            }
                        } finally {
                            isPointerDown = false
                            releaseTick++
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val safeDuration = totalDuration.coerceAtLeast(0.001f)
            fun timeToX(timeSec: Float): Float =
                (timeSec / safeDuration).coerceIn(0f, 1f) * w

            // Subtle rounded track background + horizontal baseline
            val cornerPx = 6.dp.toPx()
            drawRoundRect(
                color = trackBgColor,
                size = Size(w, h),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
            drawLine(
                color = baselineColor,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1.dp.toPx()
            )

            // 1. Amplitude waveform (base layer)
            if (envelope.isNotEmpty()) {
                val n = envelope.size
                val step = w / n
                val barW = step.coerceAtLeast(1f)
                envelope.forEachIndexed { i, amp ->
                    val barH = (amp.coerceIn(0f, 1f) * h * 0.85f)
                    if (barH > 0.5f) {
                        drawRect(
                            color = primary.copy(alpha = 0.45f),
                            topLeft = Offset(i * step, h - barH),
                            size = Size(barW, barH)
                        )
                    }
                }
            }

            // 2. Overlay spans (upper-mid band, tertiary accent)
            if (overlayLayers.isNotEmpty() && totalDuration > 0f) {
                val spanY = h * 0.26f
                val spanStroke = (h * 0.20f).coerceIn(4.dp.toPx(), 10.dp.toPx())
                val minSpanW = 3.dp.toPx()
                overlayLayers.forEach { layer ->
                    val x0 = timeToX(layer.startSec)
                    val x1 = timeToX(layer.endSec)
                    drawLine(
                        color = accent.copy(alpha = 0.82f),
                        start = Offset(x0, spanY),
                        end = Offset(kotlin.math.max(x1, x0 + minSpanW), spanY),
                        strokeWidth = spanStroke,
                        cap = StrokeCap.Round
                    )
                }
            }

            // 3. Event markers (vertical ticks + top pip)
            if (events.isNotEmpty() && totalDuration > 0f) {
                val tickTop = h * 0.14f
                val tickBottom = h * 0.94f
                val pipRadius = if (isInteracting) 3.2.dp.toPx() else 2.4.dp.toPx()
                events.forEach { ev ->
                    val x = timeToX(ev.timeSec)
                    drawLine(
                        color = primary.copy(alpha = 0.90f),
                        start = Offset(x, tickTop),
                        end = Offset(x, tickBottom),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = primary,
                        radius = pipRadius,
                        center = Offset(x, tickTop)
                    )
                }
            }

            // 4. Playhead (current playback/scrub position)
            if (totalDuration > 0f) {
                val playheadX = timeToX(scrubberPos)
                val handleRadius = if (isInteracting) 6.dp.toPx() else 4.5.dp.toPx()
                val handleCenterY = handleRadius.coerceAtMost(h * 0.5f)
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawCircle(
                    color = primary,
                    radius = handleRadius,
                    center = Offset(playheadX, handleCenterY)
                )
                drawCircle(
                    color = playheadColor,
                    radius = handleRadius * 0.45f,
                    center = Offset(playheadX, handleCenterY)
                )
            }
        }
    }
}

private fun formatTimelineClock(seconds: Float): String {
    val safe = seconds.coerceAtLeast(0f)
    val totalTenths = (safe * 10f).toInt()
    val mins = totalTenths / 600
    val secs = (totalTenths % 600) / 10
    val tenths = totalTenths % 10
    return "%d:%02d.%d".format(mins, secs, tenths)
}

/**
 * Formats an ETA in seconds as a short countdown string ("45s", "2m 15s",
 * "1h 05m"). Rounds up to the next second so it never reads "0s" while a
 * frame is still in flight.
 */
private fun formatEtaSeconds(seconds: Float): String {
    val totalSec = kotlin.math.ceil(seconds.coerceAtLeast(0f)).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else  -> "${s}s"
    }
}
