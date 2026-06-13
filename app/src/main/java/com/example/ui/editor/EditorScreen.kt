package com.example.ui.editor

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ProjectDef
import com.example.engine.AudioPlayer
import com.example.engine.BakedKeyframe
import com.example.ui.canvas.AnimationSurfaceView
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
    val messages     = vm.message

    // Load project on first composition
    LaunchedEffect(projectId) { vm.loadProject(projectId) }

    // Snackbar
    val snackState  = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        messages.collect { msg -> snackState.showSnackbar(msg) }
    }

    // Compiled timeline
    var keyframes by remember { mutableStateOf<List<BakedKeyframe>>(emptyList()) }
    var surfaceView by remember { mutableStateOf<AnimationSurfaceView?>(null) }

    // Audio player
    val audioPlayer = remember { AudioPlayer.getInstance() }
    var isAudioPlaying by remember { mutableStateOf(false) }

    // Recompile timeline whenever project script changes
    LaunchedEffect(project?.script) {
        project?.script?.let { script ->
            keyframes = vm.compileTimeline(script)
            surfaceView?.loadTimeline(keyframes)
        }
    }

    // Load audio when project changes
    LaunchedEffect(project?.audioFilePath) {
        project?.let { p ->
            if (p.audioFilePath != null && p.amplitudeEnvelope.isNotEmpty()) {
                audioPlayer.load(
                    filePath = p.audioFilePath,
                    envelope = p.amplitudeEnvelope.toFloatArray(),
                    fps      = p.exportSettings.fps
                )
            }
        }
    }

    // Audio file picker
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importAudio(context, it) }
    }

    // Tab state: Script | Appearance | Export
    var selectedTab by remember { mutableIntStateOf(0) }

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
                    Text(
                        text = project?.projectName ?: "Editor",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(onClick = onOpenPoseLibrary) {
                        Icon(Icons.Default.AccessibilityNew, "Pose Library")
                    }
                    if (exportProg == null) {
                        IconButton(onClick = { vm.exportVideo(context) }) {
                            Icon(Icons.Default.Movie, "Export")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->

        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── Animation canvas (40% height) ─────────────────────────────────
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
                            sv.setAppearance(project?.appearance ?: com.example.data.AppearanceSettings())
                            sv.setAmplitudeSettings(ampSettings)
                        }
                    },
                    update = { sv ->
                        project?.appearance?.let { sv.setAppearance(it) }
                        sv.setAmplitudeSettings(ampSettings)
                        sv.setAudioAmplitude(if (isAudioPlaying) audioPlayer.currentAmplitude else 0f)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Export progress overlay
                exportProg?.let { progress ->
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(progress = { progress })
                            Spacer(Modifier.height(8.dp))
                            Text("Exporting ${(progress * 100).toInt()}%",
                                color = Color.White)
                        }
                    }
                }
            }

            // ── Audio bar ──────────────────────────────────────────────────────
            AudioBar(
                audioName       = audioName,
                isPlaying       = isAudioPlaying,
                isAnalysing     = isAnalysing,
                onPickAudio     = {
                    audioPicker.launch(arrayOf("audio/*"))
                },
                onPlayPause     = {
                    if (isAudioPlaying) {
                        audioPlayer.pause()
                        surfaceView?.pause()
                    } else {
                        audioPlayer.play()
                        surfaceView?.play()
                    }
                    isAudioPlaying = !isAudioPlaying
                },
                onStop = {
                    audioPlayer.pause()
                    audioPlayer.seekTo(0)
                    surfaceView?.stop()
                    isAudioPlaying = false
                }
            )

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // ── Tabs ───────────────────────────────────────────────────────────
            TabRow(
                selectedTabIndex  = selectedTab,
                containerColor    = MaterialTheme.colorScheme.surface,
                contentColor      = MaterialTheme.colorScheme.primary
            ) {
                Tab(selectedTab == 0, { selectedTab = 0 },
                    text = { Text("Script") }, icon = { Icon(Icons.Default.Code, null, Modifier.size(16.dp)) })
                Tab(selectedTab == 1, { selectedTab = 1 },
                    text = { Text("Appearance") }, icon = { Icon(Icons.Default.Palette, null, Modifier.size(16.dp)) })
                Tab(selectedTab == 2, { selectedTab = 2 },
                    text = { Text("Export") }, icon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) })
            }

            // ── Tab content ────────────────────────────────────────────────────
            when (selectedTab) {
                0 -> ScriptPanel(
                    scriptText      = scriptText,
                    scriptError     = scriptError,
                    onTextChange    = { vm.onScriptTextChanged(it) },
                    onInsertPose    = onOpenPoseLibrary,
                    modifier        = Modifier.fillMaxSize()
                )
                1 -> AppearancePanel(
                    appearance   = project?.appearance ?: com.example.data.AppearanceSettings(),
                    ampSettings  = ampSettings,
                    onAppearance = { vm.updateAppearance(it) },
                    onAmplitude  = { vm.updateAmplitudeSettings(it) },
                    modifier     = Modifier.fillMaxSize()
                )
                2 -> ExportPanel(
                    settings    = project?.exportSettings ?: com.example.data.ExportSettings(),
                    onChange    = { vm.updateExportSettings(it) },
                    onExport    = { vm.exportVideo(context) },
                    modifier    = Modifier.fillMaxSize()
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
        OutlinedButton(
            onClick = onPickAudio,
            modifier = Modifier.height(36.dp)
        ) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (audioName != null) audioName.take(20) else "Import Audio",
                fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (isAnalysing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = onStop, enabled = audioName != null) {
            Icon(Icons.Default.Stop, "Stop")
        }
        IconButton(onClick = onPlayPause, enabled = audioName != null && !isAnalysing) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pause" else "Play")
        }
    }
}

// ── Script panel (JSON editor) ────────────────────────────────────────────────

@Composable
private fun ScriptPanel(
    scriptText: String,
    scriptError: String?,
    onTextChange: (String) -> Unit,
    onInsertPose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Script JSON", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
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
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A14), RoundedCornerShape(8.dp))
                .border(1.dp,
                    if (scriptError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp))
        ) {
            BasicTextField(
                value         = scriptText,
                onValueChange = onTextChange,
                textStyle     = TextStyle(
                    color      = Color(0xFFB0C4DE),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier    = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

// ── Appearance panel ──────────────────────────────────────────────────────────

@Composable
private fun AppearancePanel(
    appearance: com.example.data.AppearanceSettings,
    ampSettings: com.example.data.AmplitudeSettings,
    onAppearance: (com.example.data.AppearanceSettings) -> Unit,
    onAmplitude: (com.example.data.AmplitudeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Text("Character", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSlider("Scale", appearance.characterScale, 0.5f..2.0f) {
            onAppearance(appearance.copy(characterScale = it))
        }
        LabeledSlider("Stroke width", appearance.boneStrokeNormalized, 0.005f..0.03f) {
            onAppearance(appearance.copy(boneStrokeNormalized = it))
        }
        LabeledSlider("Root X", appearance.rootAnchorX, 0.1f..0.9f) {
            onAppearance(appearance.copy(rootAnchorX = it))
        }
        LabeledSlider("Root Y", appearance.rootAnchorY, 0.1f..0.9f) {
            onAppearance(appearance.copy(rootAnchorY = it))
        }

        LabeledSwitch("Show joints", appearance.showJoints) {
            onAppearance(appearance.copy(showJoints = it))
        }
        LabeledSwitch("Show grid", appearance.showGrid) {
            onAppearance(appearance.copy(showGrid = it))
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text("Audio Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LabeledSwitch("Enable amplitude motion", ampSettings.enabled) {
            onAmplitude(ampSettings.copy(enabled = it))
        }
        LabeledSlider("Silence threshold", ampSettings.silenceThreshold, 0.0f..0.5f) {
            onAmplitude(ampSettings.copy(silenceThreshold = it))
        }
        LabeledSlider("Talk torso amplitude (°)", ampSettings.talkTorsoAmplitude, 0f..20f) {
            onAmplitude(ampSettings.copy(talkTorsoAmplitude = it))
        }
        LabeledSlider("Head nod amplitude (°)", ampSettings.talkHeadNodAmplitude, 0f..15f) {
            onAmplitude(ampSettings.copy(talkHeadNodAmplitude = it))
        }
        LabeledSlider("Talk frequency (Hz)", ampSettings.talkFreqHz, 1f..10f) {
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
    }
}

// ── Export panel ──────────────────────────────────────────────────────────────

@Composable
private fun ExportPanel(
    settings: com.example.data.ExportSettings,
    onChange: (com.example.data.ExportSettings) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Text("Export Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        SegmentedRow("Aspect Ratio", listOf("9:16", "16:9"), settings.aspectRatio) {
            onChange(settings.copy(aspectRatio = it))
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
