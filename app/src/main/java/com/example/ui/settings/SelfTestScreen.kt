package com.example.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.MainViewModel

/**
 * The GLES self-test (WP1.6c), reached from Settings in debug builds. One tap runs the audio matrix and the parity
 * frames on this phone and writes `diagnostics_summary.txt` and `diagnostics_report.json`; Share sends the summary
 * text (paste it into the chat that is working on the app). Nothing here changes the stored GLES verdict.
 * NOT DEVICE-TESTED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.diagnostics.collectAsStateWithLifecycle()
    val glesState by vm.glesState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = { Text("GLES self-test", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Stored GLES verdict for this build: $glesState", style = MaterialTheme.typography.bodySmall)
            Text(
                "The self-test exports short test clips on Canvas and on GLES (seven audio combinations each, plus a " +
                    "picture comparison), measures them and writes a report. It takes a minute or two. Keep the app open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Button(onClick = { vm.runSelfTest(context) }, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
                Text("Run full self-test")
            }
            OutlinedButton(onClick = { vm.runGlesSmoke(context) }, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
                Text("GLES smoke test (3 s)")
            }

            if (ui.running) {
                Text("${ui.title}: ${ui.line}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            val summary = ui.summary
            if (summary != null) {
                ui.directory?.let {
                    Text("Report files: $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                OutlinedButton(onClick = { shareText(context, summary) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share summary")
                }
                SelectionContainer {
                    Text(summary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RigScript GLES self-test")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share self-test summary"))
    }
}
