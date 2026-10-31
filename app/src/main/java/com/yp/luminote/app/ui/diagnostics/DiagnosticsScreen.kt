package com.yp.luminote.app.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yp.luminote.app.R
import com.yp.luminote.app.diagnostics.DiagnosticsStatus
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard

@Composable
fun DiagnosticsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val viewModel: DiagnosticsViewModel = viewModel(factory = remember(context) { DiagnosticsViewModel.Factory(context) })
    val status by viewModel.status.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showClearConfirmation by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.fillMaxWidth().luminoteSafeHorizontalPadding()) {
            Spacer(Modifier.height(20.dp))
            LuminoteScreenHeader(stringResource(R.string.diagnostics), stringResource(R.string.back), onBackClick)
            Spacer(Modifier.height(24.dp))
            DiagnosticsStatusCard(status)
            Spacer(Modifier.height(12.dp))
            LuminoteSettingsCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.diagnostics_privacy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.diagnostics_privacy_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.diagnostics_limitations), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { exportLauncher.launch("luminote-diagnostics.zip") }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (exporting) R.string.diagnostics_exporting else R.string.diagnostics_export))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showClearConfirmation = true }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_clear))
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(when (it) {
                    DiagnosticsMessage.EXPORTED -> R.string.diagnostics_exported
                    DiagnosticsMessage.EXPORT_FAILED -> R.string.diagnostics_export_failed
                    DiagnosticsMessage.CLEARED -> R.string.diagnostics_cleared
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.diagnostics_clear_title)) },
            text = { Text(stringResource(R.string.diagnostics_clear_message)) },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.diagnostics_cancel)) } },
            confirmButton = { TextButton(onClick = { showClearConfirmation = false; viewModel.clear() }) { Text(stringResource(R.string.diagnostics_clear)) } }
        )
    }
}

@Composable
private fun DiagnosticsStatusCard(status: DiagnosticsStatus) {
    LuminoteSettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diagnostics_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            DiagnosticsMetric(stringResource(R.string.diagnostics_session), status.sessionId)
            DiagnosticsMetric(stringResource(R.string.diagnostics_warnings), status.warningCount.toString())
            DiagnosticsMetric(stringResource(R.string.diagnostics_errors), status.errorCount.toString())
            DiagnosticsMetric(stringResource(R.string.diagnostics_storage), formatBytes(status.storedBytes))
            if (status.storageDegraded) Text(stringResource(R.string.diagnostics_storage_degraded), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosticsMetric(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
