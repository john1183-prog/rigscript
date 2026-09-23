package com.example.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen destination for animation script JSON editing.
 *
 * Sits outside EditorScreen's shared layout column (no preview canvas, audio bar,
 * or transport rows), giving the BasicTextField the entire screen height. When
 * the virtual keyboard opens, imePadding resizes this editor smoothly without
 * competing with fixed chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    projectId: String,
    currentTimeSec: Float = 0f,
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenPoseLibrary: () -> Unit
) {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()
    val project        by vm.activeProject.collectAsStateWithLifecycle()
    val scriptText     by vm.scriptText.collectAsStateWithLifecycle()
    val scriptError    by vm.scriptError.collectAsStateWithLifecycle()
    val scriptWarnings by vm.scriptWarnings.collectAsStateWithLifecycle()
    val messages       = vm.message

    val snackState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        messages.collect { msg -> snackState.showSnackbar(msg) }
    }

    // F1: Script import launcher
    val scriptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importScript(context, it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Edit Script",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        project?.projectName?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                actions = {
                    // Live parse / semantic error indicator in the top bar
                    if (scriptError != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Script error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Invalid JSON",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else if (scriptWarnings.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Script warnings",
                                tint = Color(0xFFE0A030),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${scriptWarnings.size} warning${if (scriptWarnings.size == 1) "" else "s"}",
                                color = Color(0xFFE0A030),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Valid script",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Valid",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Action button row: moved from ScriptPanel as-is
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // V2: copies AI script-generation prompt to clipboard
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = vm.buildPromptForClipboard(context)
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("RigScript AI Prompt", text))
                            vm.notify("AI prompt copied to clipboard")
                        }
                    },
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prompt", fontSize = 12.sp)
                }

                // F1: Import a .json script file
                OutlinedButton(
                    onClick = { scriptPicker.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.FileOpen, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import", fontSize = 12.sp)
                }

                // Insert pose: opens pose library
                OutlinedButton(
                    onClick = onOpenPoseLibrary,
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pose", fontSize = 12.sp)
                }

                // Motion-graphics overlay layers menu
                var showOverlayMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { showOverlayMenu = true },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Overlay", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showOverlayMenu,
                        onDismissRequest = { showOverlayMenu = false }
                    ) {
                        val t = currentTimeSec
                        DropdownMenuItem(
                            text = { Text("Text burst") },
                            onClick = {
                                showOverlayMenu = false
                                vm.insertOverlayLayer(
                                    com.example.data.OverlayLayer(
                                        id = "text_%.1f".format(t),
                                        type = "text",
                                        text = "TEXT",
                                        startSec = t,
                                        endSec = t + 2f,
                                        slot = "upper",
                                        enterStyle = "pop",
                                        enterEase = "back"
                                    )
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Shape") },
                            onClick = {
                                showOverlayMenu = false
                                vm.insertOverlayLayer(
                                    com.example.data.OverlayLayer(
                                        id = "shape_%.1f".format(t),
                                        type = "shape",
                                        shape = "rect",
                                        startSec = t,
                                        endSec = t + 2f,
                                        width = 0.3f,
                                        height = 0.05f
                                    )
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Particle burst") },
                            onClick = {
                                showOverlayMenu = false
                                vm.insertOverlayLayer(
                                    com.example.data.OverlayLayer(
                                        id = "burst_%.1f".format(t),
                                        type = "particles",
                                        startSec = t,
                                        endSec = t + 1.2f,
                                        particleCount = 20
                                    )
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Parse error notification
            if (scriptError != null) {
                Text(
                    text = "⚠ $scriptError",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Semantic warnings block
            if (scriptWarnings.isNotEmpty()) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${scriptWarnings.size} warning${if (scriptWarnings.size == 1) "" else "s"}",
                            color = Color(0xFFE0A030),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { vm.dismissScriptWarnings() },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Dismiss", fontSize = 11.sp, color = Color(0xFFE0A030))
                        }
                    }
                    Column(
                        Modifier
                            .heightIn(max = 100.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        scriptWarnings.forEach { w ->
                            Text("⚠ $w", color = Color(0xFFE0A030), fontSize = 11.sp)
                        }
                    }
                }
            }

            // Fullscreen JSON text editor filling all remaining space
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0A0A14), RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (scriptError != null) MaterialTheme.colorScheme.error
                        else if (scriptWarnings.isNotEmpty()) Color(0xFFE0A030)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
            ) {
                BasicTextField(
                    value = scriptText,
                    onValueChange = { vm.onScriptTextChanged(it) },
                    textStyle = TextStyle(
                        color = Color(0xFFB0C4DE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
