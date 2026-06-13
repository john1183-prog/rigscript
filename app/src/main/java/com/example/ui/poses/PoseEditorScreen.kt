package com.example.ui.poses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.PoseDef
import com.example.engine.PlaybackEngine
import com.example.engine.StickFigureRig
import com.example.ui.canvas.AnimationSurfaceView
import com.example.viewmodel.MainViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoseEditorScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onPoseSelected: ((String) -> Unit)? = null    // non-null = pick mode (from editor)
) {
    val poses by vm.poses.collectAsStateWithLifecycle()
    val messages = vm.message

    val snackState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { snackState.showSnackbar(it) } }

    // Currently selected pose for preview/edit
    var selectedPose by remember { mutableStateOf<PoseDef?>(null) }
    var editingName  by remember { mutableStateOf("") }
    var isDirty      by remember { mutableStateOf(false) }
    var surfaceView  by remember { mutableStateOf<AnimationSurfaceView?>(null) }

    // Internal PlaybackEngine for pose preview (separate from the editor's engine)
    val engine = remember { PlaybackEngine() }

    fun loadPose(pose: PoseDef) {
        selectedPose = pose
        editingName  = pose.name
        isDirty      = false
        // Apply pose angles to engine
        val bones = StickFigureRig.BONES
        bones.forEachIndexed { i, bone ->
            val total = bone.defaultAngleDegrees + (pose.joints[bone.id] ?: 0f)
            engine.setBoneAngle(i, total)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                title = {
                    Text(
                        if (onPoseSelected != null) "Pick a Pose" else "Pose Library",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (selectedPose != null && !selectedPose!!.isBuiltIn) {
                        IconButton(onClick = {
                            selectedPose?.let { vm.deleteCustomPose(it.id) }
                            selectedPose = null
                        }) {
                            Icon(Icons.Default.Delete, "Delete pose",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            // Create new pose from scratch
            FloatingActionButton(onClick = {
                val blank = PoseDef(
                    id       = UUID.randomUUID().toString(),
                    name     = "New Pose",
                    category = "custom",
                    isBuiltIn = false,
                    joints   = emptyMap()
                )
                loadPose(blank)
                isDirty = true
            }) { Icon(Icons.Default.Add, "New Pose") }
        }
    ) { padding ->

        Row(Modifier.fillMaxSize().padding(padding)) {

            // ── Pose list (left panel) ─────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val builtIn = poses.filter { it.isBuiltIn }
                val custom  = poses.filter { !it.isBuiltIn }

                if (builtIn.isNotEmpty()) {
                    item { PoseSectionHeader("Built-in") }
                    items(builtIn, key = { it.id }) { pose ->
                        PoseListItem(
                            pose       = pose,
                            isSelected = pose.id == selectedPose?.id,
                            onClick    = {
                                if (onPoseSelected != null) {
                                    onPoseSelected(pose.id)
                                } else {
                                    loadPose(pose)
                                }
                            }
                        )
                    }
                }
                if (custom.isNotEmpty()) {
                    item { PoseSectionHeader("Custom") }
                    items(custom, key = { it.id }) { pose ->
                        PoseListItem(
                            pose       = pose,
                            isSelected = pose.id == selectedPose?.id,
                            onClick    = {
                                if (onPoseSelected != null) {
                                    onPoseSelected(pose.id)
                                } else {
                                    loadPose(pose)
                                }
                            }
                        )
                    }
                }
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // ── Right panel: pose canvas + editor ──────────────────────────────
            Column(Modifier.fillMaxSize()) {

                // Pose preview canvas
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1A1A2E))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            AnimationSurfaceView(ctx).also { sv ->
                                surfaceView = sv
                                sv.poseEditorMode = selectedPose != null && !selectedPose!!.isBuiltIn
                                sv.onBoneAngleChanged = { _, _ -> isDirty = true }
                            }
                        },
                        update = { sv ->
                            sv.poseEditorMode = selectedPose != null && !selectedPose!!.isBuiltIn
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (selectedPose == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("← Select a pose to preview",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp)
                        }
                    }
                }

                // ── Name + save bar ────────────────────────────────────────────
                selectedPose?.let { pose ->
                    Surface(
                        color     = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 2.dp
                    ) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {

                            OutlinedTextField(
                                value         = editingName,
                                onValueChange = { editingName = it; isDirty = true },
                                label         = { Text("Pose name") },
                                singleLine    = true,
                                enabled       = !pose.isBuiltIn,
                                modifier      = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Save (only for custom poses or when duplicating)
                                if (!pose.isBuiltIn || isDirty) {
                                    Button(
                                        onClick = {
                                            val captured = engine.captureRelativeAngles()
                                            val toSave = if (pose.isBuiltIn) {
                                                // Duplicate built-in as custom
                                                pose.copy(
                                                    id       = UUID.randomUUID().toString(),
                                                    name     = "Copy of ${pose.name}",
                                                    isBuiltIn = false,
                                                    category  = "custom",
                                                    joints   = captured
                                                )
                                            } else {
                                                pose.copy(name = editingName.trim().ifBlank { "Pose" },
                                                    joints = captured)
                                            }
                                            vm.savePose(toSave)
                                            selectedPose = toSave
                                            isDirty = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text(if (pose.isBuiltIn) "Save as Copy" else "Save") }
                                }

                                // Use in script (pick mode)
                                if (onPoseSelected != null) {
                                    OutlinedButton(
                                        onClick = { onPoseSelected(pose.id) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Use in Script") }
                                }
                            }

                            if (pose.isBuiltIn) {
                                Text("Built-in poses cannot be edited directly. Save as a copy to customise.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoseSectionHeader(title: String) {
    Text(title,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold)
}

@Composable
private fun PoseListItem(pose: PoseDef, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
             else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (pose.isBuiltIn) Icons.Default.Stars else Icons.Default.Person,
            null,
            modifier = Modifier.size(14.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.width(6.dp))
        Text(pose.name,
            style    = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color    = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface)
    }
}
