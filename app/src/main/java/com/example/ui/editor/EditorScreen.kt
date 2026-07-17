package com.example.ui.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
    onOpenPoseLibrary: () -> Unit
) {
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val project      by vm.activeProject.collectAsStateWithLifecycle()
    val scriptText   by vm.scriptText.collectAsStateWithLifecycle()
    val scriptError  by vm.scriptError.collectAsStateWithLifecycle()
    val ampSettings  by vm.amplitudeSettings.collectAsStateWithLifecycle()
    val isAnalysing  by vm.isAnalysingAudio.collectAsStateWithLifecycle()
    val audioName    by vm.audioFileName.collectAsStateWithLifecycle()
    val exportProg   by vm.exportProgress.collectAsStateWithLifecycle()
    val exportEta    by vm.exportEtaSec.collectAsStateWithLifecycle()
    val exportedFile by vm.exportedFile.collectAsStateWithLifecycle()
    val messages     = vm.message

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
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .background(Color(project?.appearance?.previewBgColor?.toInt() ?: 0xFF1A1A2E.toInt()))
            ) {
                AndroidView(
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
                                soundEffectCues = project?.script?.let { com.example.engine.TimelineCompiler.extractSoundEffectCues(it) } ?: emptyList()
                            )
                            lastLoadedKeyframes = keyframes
                        }
                        project?.appearance?.let { sv.setAppearance(it) }
                        sv.setAmplitudeSettings(ampSettings)
                        project?.referenceOverlay?.let { sv.setReferenceOverlay(it) }
                        sv.setSoundEffectLibrary(project?.soundEffects ?: emptyList())
                    },
                    onRelease = { it.release() },
                    modifier = Modifier.fillMaxSize()
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

            // F4: Amplitude waveform — visual reference for where speech is, so
            //     script events can be aligned to audio content without counting seconds.
            //     Only shown when audio has been imported (envelope is non-empty).
            if (envelopeArray.isNotEmpty()) {
                AmplitudeWaveform(
                    envelope      = envelopeArray.toList(),
                    scrubberPos   = scrubberPos,
                    totalDuration = totalDuration,
                    modifier      = Modifier.fillMaxWidth().height(28.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp)
                )
            }

            // Shared by the timeline strip below and the scrubber Slider —
            // was duplicated inline when the strip was added; one definition
            // means the two controls can't quietly drift apart later.
            val seekPlayback: (Float) -> Unit = { pos ->
                scrubberPos = pos
                surfaceView?.seekTo(pos)
                if (isAudioPlaying) {
                    audioPlayer.seekTo((pos * 1000).toInt())
                    musicPlayer.seekTo((pos * 1000).toInt())
                }
            }

            // V2 — visual overview of event pacing over the waveform. Tap a
            // marker to seek there; see EventTimelineStrip's own doc comment
            // for why this is tap-only rather than the full drag/long-press
            // editor originally scoped.
            val scriptEvents = project?.script?.events ?: emptyList()
            if (scriptEvents.isNotEmpty() && totalDuration > 0f) {
                EventTimelineStrip(
                    events        = scriptEvents,
                    totalDuration = totalDuration,
                    onSeek        = seekPlayback,
                    modifier      = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // F3: Playback scrubber — seek to any time; updates every 100ms while playing
            Slider(
                value         = scrubberPos.coerceIn(0f, totalDuration),
                onValueChange = seekPlayback,
                valueRange = 0f..totalDuration.coerceAtLeast(1f),
                modifier   = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(20.dp)
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
                0 -> ScriptPanel(
                    scriptText   = scriptText,
                    scriptError  = scriptError,
                    onTextChange = { vm.onScriptTextChanged(it) },
                    onInsertPose = onOpenPoseLibrary,
                    onImport     = { scriptPicker.launch(arrayOf("application/json", "*/*")) },
                    modifier     = Modifier.fillMaxSize()
                )
                1 -> AppearancePanel(
                    appearance        = project?.appearance ?: AppearanceSettings(),
                    ampSettings       = ampSettings,
                    referenceOverlay  = project?.referenceOverlay ?: com.example.data.ReferenceOverlay(),
                    backgroundMusic   = project?.backgroundMusic ?: com.example.data.BackgroundMusicSettings(),
                    soundEffects      = project?.soundEffects ?: emptyList(),
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
                    modifier          = Modifier.fillMaxSize()
                )
                2 -> ExportPanel(
                    settings     = project?.exportSettings ?: ExportSettings(),
                    exportedFile = exportedFile,
                    onChange     = { vm.updateExportSettings(it) },
                    onExport     = { triggerExport() },
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

// ── Script panel ──────────────────────────────────────────────────────────────

@Composable
private fun ScriptPanel(
    scriptText: String,
    scriptError: String?,
    onTextChange: (String) -> Unit,
    onInsertPose: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Script JSON", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // F1: Import a .json script file directly — avoids copy-paste for AI-generated scripts
            OutlinedButton(onClick = onImport, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.FileOpen, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onInsertPose, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Insert Pose", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (scriptError != null) {
            Text("⚠ $scriptError", color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color(0xFF0A0A14), RoundedCornerShape(8.dp))
                .border(1.dp,
                    if (scriptError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp))
        ) {
            BasicTextField(
                value = scriptText,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    color = Color(0xFFB0C4DE), fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())
            )
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
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

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
        LabeledSlider("Stroke width", appearance.boneStrokeNormalized, 0.005f..0.03f) {
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

        LabeledSwitch("Outline", appearance.outlineEnabled) {
            onAppearance(appearance.copy(outlineEnabled = it))
        }
        if (appearance.outlineEnabled) {
            ColorPickerRow("Outline color", appearance.outlineColor) { newColor ->
                onAppearance(appearance.copy(outlineColor = newColor))
            }
            LabeledSlider("Outline width", appearance.outlineWidthNormalized, 0.002f..0.02f) {
                onAppearance(appearance.copy(outlineWidthNormalized = it))
            }
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
        LabeledSwitch("Show eyes", appearance.showEyes) {
            onAppearance(appearance.copy(showEyes = it))
        }
        ColorPickerRow("Eye color", appearance.eyeColor) { newColor ->
            onAppearance(appearance.copy(eyeColor = newColor))
        }
        ColorPickerRow("Eyebrow color", appearance.eyebrowColor) { newColor ->
            onAppearance(appearance.copy(eyebrowColor = newColor))
        }
        Text("Eyebrows only draw for worried/angry expressions.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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

        TextButton(onClick = { onAppearance(AppearanceSettings()) }) {
            Text("Reset appearance to defaults")
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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

        Text("Or add from the bundled starter library (CC0):",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export Video")
        }
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
@Composable
private fun EventTimelineStrip(
    events: List<ScriptEvent>,
    totalDuration: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    var nearestLabel by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(events, totalDuration) {
                    detectTapGestures { offset ->
                        if (events.isEmpty() || totalDuration <= 0f) return@detectTapGestures
                        val tapSec = (offset.x / size.width).coerceIn(0f, 1f) * totalDuration
                        val nearest = events.minByOrNull { kotlin.math.abs(it.timeSec - tapSec) }
                        if (nearest != null) {
                            onSeek(nearest.timeSec)
                            nearestLabel = "${nearest.pose} @ %.1fs".format(nearest.timeSec)
                        }
                    }
                }
        ) {
            if (totalDuration <= 0f) return@Canvas
            events.forEach { ev ->
                val x = (ev.timeSec / totalDuration).coerceIn(0f, 1f) * size.width
                drawLine(
                    color       = primary.copy(alpha = 0.8f),
                    start       = Offset(x, size.height * 0.15f),
                    end         = Offset(x, size.height),
                    strokeWidth = 3f
                )
            }
        }
        nearestLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor) }
    }
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

@Composable
private fun AmplitudeWaveform(
    envelope: List<Float>,
    scrubberPos: Float,
    totalDuration: Float,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (envelope.isEmpty()) return@Canvas
        val n    = envelope.size
        val step = size.width / n
        envelope.forEachIndexed { i, amp ->
            val barH = amp * size.height * 0.85f
            drawRect(
                color    = primary.copy(alpha = 0.45f),
                topLeft  = Offset(i * step, size.height - barH),
                size     = Size(step.coerceAtLeast(1f), barH)
            )
        }
        if (totalDuration > 0f) {
            val x = (scrubberPos / totalDuration) * size.width
            drawLine(color = primary, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
        }
    }
}
