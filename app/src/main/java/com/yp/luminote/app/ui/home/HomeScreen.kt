package com.yp.luminote.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.data.settings.HaloMode

@Composable
fun HomeScreen(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass,
    viewModel: LuminoteSettingsViewModel
) {
    val settings by viewModel.settings.collectAsState()
    val settingsLoaded by
    viewModel.settingsLoaded.collectAsState()
    val uiMetrics = rememberLuminoteUiMetrics()
    val context = LocalContext.current
    val onModeSelected: (HaloMode) -> Unit = { mode ->
        viewModel.setHaloMode(mode)
        when (mode) {
            HaloMode.AMBIENT -> HaloOverlayService.start(
                context,
                HaloOverlayService.createAmbientIntent(
                    context,
                    settings.copy(haloMode = HaloMode.AMBIENT)
                )
            )

            HaloMode.NOTIFICATIONS,
            HaloMode.OFF -> HaloOverlayService.start(
                context,
                HaloOverlayService.createStopRepeatingIntent(context)
            )
        }
    }

    if (!settingsLoaded) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment =
                Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier.size(24.dp)
            )
        }

        return
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .luminoteSafeHorizontalPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
    ) {
        Spacer(
            modifier =
                    Modifier.height(if (uiMetrics.isCompactHeight) 20.dp else 32.dp)
        )

        Text(
            text = "Luminote",
            style = if (uiMetrics.isCompactHeight) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Spacer(
            modifier =
                    Modifier.height(10.dp)
        )

        Text(
            text = "Edge lighting for alerts and ambient style",
            style =
                MaterialTheme
                    .typography
                    .bodyLarge,
            color = Color(0xFFBDBDBD)
        )

        Spacer(
            modifier =
                    Modifier.height(if (uiMetrics.isCompactHeight) 24.dp else 36.dp)
        )

        when (windowSizeClass) {

            LuminoteWindowSizeClass.COMPACT -> {

                CompactHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick = onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onNotificationBehaviorClick = onNotificationBehaviorClick,
                    onAboutClick = onAboutClick,
                    onAccessClick = onAccessClick,
                    mode = settings.haloMode,
                    onModeSelected = onModeSelected
                )
            }

            LuminoteWindowSizeClass.MEDIUM -> {

                MediumHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick = onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onNotificationBehaviorClick = onNotificationBehaviorClick,
                    onAboutClick = onAboutClick,
                    onAccessClick = onAccessClick,
                    mode = settings.haloMode,
                    onModeSelected = onModeSelected
                )
            }

            LuminoteWindowSizeClass.EXPANDED -> {

                ExpandedHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick = onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onNotificationBehaviorClick = onNotificationBehaviorClick,
                    onAboutClick = onAboutClick,
                    onAccessClick = onAccessClick,
                    mode = settings.haloMode,
                    onModeSelected = onModeSelected
                )
            }
        }

    }
}

@Composable
private fun CompactHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    HomeSettingsList(
        mode = mode,
        onModeSelected = onModeSelected,
        onHaloClick = onHaloClick,
        onAmbientClick = onAmbientClick,
        onAppsClick = onAppsClick,
        onNotificationBehaviorClick = onNotificationBehaviorClick,
        onAboutClick = onAboutClick,
        onAccessClick = onAccessClick,
        spacing = 12.dp
    )
}

@Composable
private fun MediumHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(
                    max = 840.dp
                ),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier =
                Modifier.weight(1f),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            HomeSettingsList(
                mode = mode,
                onModeSelected = onModeSelected,
                onHaloClick = onHaloClick,
                onAmbientClick = onAmbientClick,
                onAppsClick = onAppsClick,
                onNotificationBehaviorClick = onNotificationBehaviorClick,
                onAboutClick = onAboutClick,
                onAccessClick = onAccessClick,
                spacing = 12.dp
            )
        }
    }
}

@Composable
private fun ExpandedHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(
                    max = 1100.dp
                ),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f),
            verticalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {
            HomeSettingsList(
                mode = mode,
                onModeSelected = onModeSelected,
                onHaloClick = onHaloClick,
                onAmbientClick = onAmbientClick,
                onAppsClick = onAppsClick,
                onNotificationBehaviorClick = onNotificationBehaviorClick,
                onAboutClick = onAboutClick,
                onAccessClick = onAccessClick,
                spacing = 16.dp
            )
        }
    }
}

@Composable
private fun HomeSettingsList(
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit,
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    spacing: Dp
) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        HomeModeSelector(mode = mode, onModeSelected = onModeSelected)

        when (mode) {
            HaloMode.NOTIFICATIONS -> {
                HomeSettingItem(
                    title = "Luminote Halo",
                    subtitle = "Notification effects and appearance",
                    onClick = onHaloClick
                )
                HomeSettingItem(
                    title = "Apps",
                    subtitle = "Choose which apps can trigger the effect",
                    onClick = onAppsClick
                )
                HomeSettingItem(
                    title = "Notification behavior",
                    subtitle = "Control silent notification updates",
                    onClick = onNotificationBehaviorClick
                )
            }

            HaloMode.AMBIENT -> HomeSettingItem(
                title = "Ambient Halo",
                subtitle = "Keep a custom edge effect visible",
                onClick = onAmbientClick
            )

            HaloMode.OFF -> Unit
        }

        HomeSettingItem(
            title = "Access",
            subtitle = "Permissions for alerts and edge effects",
            onClick = onAccessClick
        )
        HomeSettingItem(
            title = "About",
            subtitle = "Luminote, developer and source code",
            onClick = onAboutClick
        )
    }
}

@Composable
private fun HomeModeSelector(
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF101010))
            .border(
                width = 1.dp,
                color = Color(0xFF3D3D3D),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Text(
            text = "Mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Choose when Luminote uses the screen edge",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBDBDBD)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeModeChoice(
                label = "Alerts",
                selected = mode == HaloMode.NOTIFICATIONS,
                onClick = { onModeSelected(HaloMode.NOTIFICATIONS) }
            )
            HomeModeChoice(
                label = "Ambient",
                selected = mode == HaloMode.AMBIENT,
                onClick = { onModeSelected(HaloMode.AMBIENT) }
            )
            HomeModeChoice(
                label = "Off",
                selected = mode == HaloMode.OFF,
                onClick = { onModeSelected(HaloMode.OFF) }
            )
        }
    }
}

@Composable
private fun RowScope.HomeModeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (selected) Color(0xFF303030) else Color(0xFF202020))
            .border(
                width = 1.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else Color(0xFFBDBDBD)
        )
    }
}

@Composable
private fun HomeSettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(24.dp)
                )
                .background(Color(0xFF101010))
                .border(
                    width = 1.dp,
                    color = Color(0xFF3D3D3D),
                    shape = RoundedCornerShape(24.dp)
                )
                .clickable(
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 18.dp
                ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color(0xFF808080)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Color(0xFFBDBDBD) else Color(0xFF5A5A5A)
            )
        }

        Text(
            text = "›",
            style = MaterialTheme.typography.headlineMedium,
            color = if (enabled) Color.White else Color(0xFF5A5A5A)
        )
    }
}
