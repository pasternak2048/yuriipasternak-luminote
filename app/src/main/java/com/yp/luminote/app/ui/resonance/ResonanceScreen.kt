package com.yp.luminote.app.ui.resonance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.overlay.service.HaloEffectController
import com.yp.luminote.app.overlay.service.ResonanceAccessState
import com.yp.luminote.app.resonance.ResonanceController
import com.yp.luminote.app.resonance.ResonanceState
import com.yp.luminote.app.resonance.ResonanceStatus
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteSliderSetting
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun ResonanceScreen(
    onBackClick: () -> Unit,
    viewModel: LuminoteSettingsViewModel,
    onAccessClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by ResonanceController.state.collectAsStateWithLifecycle()
    val access by HaloEffectController.resonanceAccessState.collectAsStateWithLifecycle()
    val uiMetrics = rememberLuminoteUiMetrics()
    LaunchedEffect(context) { ResonanceController.loadBeatResponse(context) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .luminoteSafeHorizontalPadding(),
        verticalArrangement = Arrangement.spacedBy(uiMetrics.sectionSpacing)
    ) {
        Spacer(Modifier.height(uiMetrics.headerTopSpacing))
        LuminoteScreenHeader(
            title = stringResource(R.string.resonance_settings),
            backContentDescription = stringResource(R.string.back),
            onBackClick = onBackClick,
            backButtonSize = uiMetrics.backButtonSize
        )
        Text(stringResource(R.string.resonance_description), color = MaterialTheme.colorScheme.onBackground)
        ResonanceModeStatusCard(access, state, settings.haloMode == HaloMode.RESONANCE, onAccessClick)
        LuminoteSettingsCard {
            Column(Modifier.padding(uiMetrics.cardPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LuminoteSliderSetting(
                    title = stringResource(R.string.resonance_intensity),
                    value = state.intensity,
                    valueText = stringResource(R.string.percentage, (state.intensity * 100).toInt()),
                    onValueChange = ResonanceController::setIntensity
                )
                LuminoteSliderSetting(
                    title = stringResource(R.string.resonance_beat_response),
                    value = state.beatResponse,
                    valueText = stringResource(R.string.percentage, (state.beatResponse * 100).toInt()),
                    onValueChange = { ResonanceController.setBeatResponse(context, it) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.resonance_beat_smooth), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.resonance_beat_pulse), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.resonance_gpu_requirement), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Text(stringResource(R.string.resonance_policy_explanation), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

/** Desired mode, permission readiness and runtime activity are deliberately displayed separately. */
@Composable
internal fun ResonanceModeStatusCard(
    access: ResonanceAccessState,
    state: ResonanceState,
    selected: Boolean,
    onAccessClick: () -> Unit
) {
    LuminoteSettingsCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (selected) R.string.resonance_selected else R.string.resonance_not_selected),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!access.audioGranted) Text(stringResource(R.string.resonance_audio_required),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!access.overlayGranted) Text(stringResource(R.string.resonance_overlay_required),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!access.ready) {
                Text(stringResource(R.string.resonance_permission_explanation),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onAccessClick) { Text(stringResource(R.string.access)) }
            }
            val active = state.status == ResonanceStatus.STARTING || state.status == ResonanceStatus.WAITING_FOR_AUDIO ||
                state.status == ResonanceStatus.RUNNING
            if (selected || active || state.cleanupPending) {
                val status = when {
                    state.cleanupPending -> R.string.resonance_stopping
                    !access.ready -> R.string.resonance_waiting_access
                    state.status == ResonanceStatus.STARTING -> R.string.resonance_starting
                    state.status == ResonanceStatus.WAITING_FOR_AUDIO -> R.string.resonance_waiting
                    state.status == ResonanceStatus.RUNNING -> R.string.resonance_running
                    state.status == ResonanceStatus.UNSUPPORTED -> R.string.resonance_unsupported
                    state.status == ResonanceStatus.ERROR -> R.string.resonance_error
                    else -> R.string.resonance_selected_waiting
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.status == ResonanceStatus.WAITING_FOR_AUDIO && access.ready) {
                    Text(stringResource(R.string.resonance_waiting_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
