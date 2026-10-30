package com.yp.luminote.app.ui.resonance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yp.luminote.app.R
import com.yp.luminote.app.resonance.ResonanceController
import com.yp.luminote.app.resonance.ResonanceStatus
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard

@Composable
fun ResonanceScreen(onBackClick: () -> Unit, onAccessClick: () -> Unit = {}) {
    val context = LocalContext.current
    val state by ResonanceController.state.collectAsStateWithLifecycle()
    LaunchedEffect(context) { ResonanceController.loadBeatResponse(context) }
    val active = state.status == ResonanceStatus.STARTING || state.status == ResonanceStatus.WAITING_FOR_AUDIO || state.status == ResonanceStatus.RUNNING
    var awaitingConsent by rememberSaveable { mutableStateOf(false) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingConsent = false
        if (granted) ResonanceController.start(context) else ResonanceController.permissionDenied()
    }
    val supported = Build.VERSION.SDK_INT >= 33
    val overlayGranted = Settings.canDrawOverlays(context)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LuminoteScreenHeader(title = stringResource(R.string.resonance_title),
            backContentDescription = stringResource(R.string.back), onBackClick = onBackClick)
        Text(stringResource(R.string.resonance_description))
        LuminoteSettingsCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.resonance_gpu_requirement), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.resonance_permission_explanation))
                Text(stringResource(R.string.resonance_policy_explanation))
                if (!supported) Text(stringResource(R.string.resonance_device_unsupported))
                if (!overlayGranted) {
                    Text(stringResource(R.string.resonance_overlay_required))
                    TextButton(onClick = onAccessClick) { Text(stringResource(R.string.access)) }
                }
                val statusText = when (state.status) {
                    ResonanceStatus.STARTING -> R.string.resonance_starting
                    ResonanceStatus.WAITING_FOR_AUDIO -> R.string.resonance_waiting
                    ResonanceStatus.RUNNING -> R.string.resonance_running
                    ResonanceStatus.UNSUPPORTED -> R.string.resonance_unsupported
                    ResonanceStatus.ERROR -> R.string.resonance_error
                    else -> R.string.resonance_stopped
                }
                Text(stringResource(if (state.cleanupPending) R.string.resonance_stopping else statusText), style = MaterialTheme.typography.titleMedium)
                if (state.status == ResonanceStatus.WAITING_FOR_AUDIO) Text(stringResource(R.string.resonance_waiting_help))
                Button(onClick = {
                    awaitingConsent = true
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        awaitingConsent = false
                        ResonanceController.start(context)
                    } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = supported && overlayGranted && !active && !awaitingConsent && !state.cleanupPending) {
                    Text(stringResource(R.string.resonance_start))
                }
                OutlinedButton(onClick = { ResonanceController.stop(context) }, enabled = active) {
                    Text(stringResource(R.string.resonance_stop))
                }
            }
        }
        LuminoteSettingsCard {
            Column(Modifier.padding(20.dp)) {
                val label = stringResource(R.string.resonance_intensity)
                Text(label)
                Slider(value = state.intensity, onValueChange = ResonanceController::setIntensity,
                    modifier = Modifier.semantics { contentDescription = label })
                Spacer(Modifier.height(12.dp))
                val beatLabel = stringResource(R.string.resonance_beat_response)
                Text(beatLabel)
                Slider(value = state.beatResponse,
                    onValueChange = { ResonanceController.setBeatResponse(context, it) },
                    modifier = Modifier.semantics { contentDescription = beatLabel })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.resonance_beat_smooth), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.resonance_beat_pulse), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(R.string.resonance_diagnostics, state.gpuFrames, state.analysisFrames),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (state.providerInitialized) R.string.resonance_provider_ready else R.string.resonance_provider_not_ready),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.resonance_nonzero_frames, state.nonzeroFeatureFrames),
            style = MaterialTheme.typography.bodySmall)
    }
}
