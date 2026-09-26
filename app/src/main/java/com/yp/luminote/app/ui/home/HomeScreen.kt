package com.yp.luminote.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.ui.components.LuminoteNavigationRow
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun HomeScreen(onHaloClick: () -> Unit, onAmbientClick: () -> Unit, onAppsClick: () -> Unit,
    onAccessClick: () -> Unit, onSettingsClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass, viewModel: LuminoteSettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val settingsLoaded by viewModel.settingsLoaded.collectAsState()
    val context = LocalContext.current
    val uiMetrics = rememberLuminoteUiMetrics()
    val contentModifier = when (windowSizeClass) {
        LuminoteWindowSizeClass.COMPACT -> Modifier.fillMaxWidth()
        LuminoteWindowSizeClass.MEDIUM -> Modifier.fillMaxWidth().widthIn(max = 840.dp)
        LuminoteWindowSizeClass.EXPANDED -> Modifier.fillMaxWidth().widthIn(max = 1100.dp)
    }
    val onModeSelected: (HaloMode) -> Unit = { mode ->
        viewModel.setHaloMode(mode)
        when (mode) {
            HaloMode.AMBIENT -> HaloOverlayService.start(context, HaloOverlayService.createAmbientIntent(context, settings.copy(haloMode = HaloMode.AMBIENT)))
            HaloMode.NOTIFICATIONS, HaloMode.OFF -> HaloOverlayService.start(context, HaloOverlayService.createStopRepeatingIntent(context))
        }
    }
    if (!settingsLoaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        return
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
            .navigationBarsPadding().luminoteSafeHorizontalPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(if (uiMetrics.isCompactHeight) 20.dp else 32.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettingsClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings_content_description), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeModeSelector(settings.haloMode, onModeSelected)
            when (settings.haloMode) {
                HaloMode.NOTIFICATIONS -> { HomeAction(stringResource(R.string.luminote_halo), stringResource(R.string.home_halo_description), Icons.Outlined.Tune, onHaloClick); HomeAction(stringResource(R.string.apps), stringResource(R.string.home_apps_description), Icons.Outlined.Apps, onAppsClick) }
                HaloMode.AMBIENT -> HomeAction(stringResource(R.string.ambient_halo), stringResource(R.string.home_ambient_description), Icons.Outlined.Notifications, onAmbientClick)
                HaloMode.OFF -> Unit
            }
            HomeAction(stringResource(R.string.access), stringResource(R.string.home_access_description), Icons.Outlined.Security, onAccessClick)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun HomeModeSelector(mode: HaloMode, onModeSelected: (HaloMode) -> Unit) {
    LuminoteSettingsCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.working_mode),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.mode_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                HomeModeChoice(stringResource(R.string.alerts), mode == HaloMode.NOTIFICATIONS) { onModeSelected(HaloMode.NOTIFICATIONS) }
                HomeModeChoice(stringResource(R.string.ambient), mode == HaloMode.AMBIENT) { onModeSelected(HaloMode.AMBIENT) }
                HomeModeChoice(stringResource(R.string.off), mode == HaloMode.OFF) { onModeSelected(HaloMode.OFF) }
            }
        }
    }
}

@Composable private fun HomeModeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    LuminoteSelectionRow(
        title = label,
        selected = selected,
        onClick = onClick,
        control = LuminoteSelectionControl.Radio
    )
}

@Composable private fun HomeAction(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    LuminoteNavigationRow(title, subtitle, icon, onClick)
}
