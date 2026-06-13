package com.example.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AmplitudeSettings
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ampSettings by vm.amplitudeSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader("Global Audio Response Defaults")
            Text(
                "These defaults apply to all projects. Each project can override " +
                "them from the Appearance tab in the editor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            LabeledSwitch("Enable amplitude-driven motion", ampSettings.enabled) {
                vm.updateAmplitudeSettings(ampSettings.copy(enabled = it))
            }
            LabeledSlider("Silence threshold", ampSettings.silenceThreshold, 0f..0.5f,
                "Below this amplitude, the figure stays still") {
                vm.updateAmplitudeSettings(ampSettings.copy(silenceThreshold = it))
            }
            LabeledSlider("Smoothing factor", ampSettings.smoothingFactor, 0f..0.9f,
                "Higher = smoother, less reactive") {
                vm.updateAmplitudeSettings(ampSettings.copy(smoothingFactor = it))
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            SectionHeader("Talking Motion")

            LabeledSlider("Torso sway (°)", ampSettings.talkTorsoAmplitude, 0f..20f,
                "How much the torso rocks when speaking") {
                vm.updateAmplitudeSettings(ampSettings.copy(talkTorsoAmplitude = it))
            }
            LabeledSlider("Head nod (°)", ampSettings.talkHeadNodAmplitude, 0f..15f,
                "Subtle head bob while speaking") {
                vm.updateAmplitudeSettings(ampSettings.copy(talkHeadNodAmplitude = it))
            }
            LabeledSlider("Talk frequency (Hz)", ampSettings.talkFreqHz, 1f..10f,
                "Speed of the speaking oscillation") {
                vm.updateAmplitudeSettings(ampSettings.copy(talkFreqHz = it))
            }
            LabeledSwitch("Arm micro-sway", ampSettings.armSwayEnabled) {
                vm.updateAmplitudeSettings(ampSettings.copy(armSwayEnabled = it))
            }
            LabeledSlider("Arm sway (°)", ampSettings.armSwayAmplitude, 0f..10f,
                "Subtle arm movement while speaking") {
                vm.updateAmplitudeSettings(ampSettings.copy(armSwayAmplitude = it))
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            SectionHeader("Idle Breathing")

            LabeledSlider("Breath amplitude (°)", ampSettings.idleBreathAmplitude, 0f..8f,
                "Torso rotation for idle breathing cycle") {
                vm.updateAmplitudeSettings(ampSettings.copy(idleBreathAmplitude = it))
            }
            LabeledSlider("Breath frequency (Hz)", ampSettings.breathFreqHz, 0.1f..1.0f,
                "Breaths per second (0.3 ≈ natural)") {
                vm.updateAmplitudeSettings(ampSettings.copy(breathFreqHz = it))
            }

            Spacer(Modifier.height(32.dp))
            Text("RigScript v1.0 — Offline AI Animator",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    hint: String = "", onValue: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (hint.isNotBlank()) {
            Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
