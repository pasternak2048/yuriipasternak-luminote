package com.yp.luminote.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

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
            color = Color(0xFFAFAFB8)
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
                    notificationsLocked = settings.ambientEnabled
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
                    notificationsLocked = settings.ambientEnabled
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
                    notificationsLocked = settings.ambientEnabled
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
    notificationsLocked: Boolean
) {
    HomeSettingItem(
        title = "Luminote Halo",
        subtitle =
            "Notification effects and appearance",
        onClick =
            onHaloClick,
        enabled = !notificationsLocked
    )

    Spacer(
        modifier =
            Modifier.height(12.dp)
    )

    HomeSettingItem(
        title = "Ambient Halo",
        subtitle = "Keep a custom edge effect visible",
        onClick = onAmbientClick
    )

    Spacer(modifier = Modifier.height(12.dp))

    HomeSettingItem(
        title = "Apps",
        subtitle =
            "Choose which apps can trigger the effect",
        onClick =
            onAppsClick,
        enabled = !notificationsLocked
    )

    Spacer(modifier = Modifier.height(12.dp))

    HomeSettingItem(
        title = "Access",
        subtitle = "Permissions for alerts and edge effects",
        onClick = onAccessClick
    )

    Spacer(modifier = Modifier.height(12.dp))

    HomeSettingItem(
        title = "Notification behavior",
        subtitle = "Control silent notification updates",
        onClick = onNotificationBehaviorClick,
        enabled = !notificationsLocked
    )

    Spacer(modifier = Modifier.height(12.dp))

    HomeSettingItem(
        title = "About",
        subtitle = "Luminote, developer and source code",
        onClick = onAboutClick
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
    notificationsLocked: Boolean
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
            HomeSettingItem(
                title = "Luminote Halo",
                subtitle =
            "Notification effects and appearance",
                onClick =
                    onHaloClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Apps",
                subtitle =
                    "Choose which apps can trigger the effect",
                onClick =
                    onAppsClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Notification behavior",
                subtitle = "Control silent notification updates",
                onClick = onNotificationBehaviorClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Ambient Halo",
                subtitle = "Keep a custom edge effect visible",
                onClick = onAmbientClick
            )

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
}

@Composable
private fun ExpandedHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onNotificationBehaviorClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    notificationsLocked: Boolean
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
            HomeSettingItem(
                title = "Luminote Halo",
                subtitle =
                    "Notification effects and appearance",
                onClick =
                    onHaloClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Apps",
                subtitle =
                    "Choose which apps can trigger the effect",
                onClick =
                    onAppsClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Notification behavior",
                subtitle = "Control silent notification updates",
                onClick = onNotificationBehaviorClick,
                enabled = !notificationsLocked
            )

            HomeSettingItem(
                title = "Ambient Halo",
                subtitle = "Keep a custom edge effect visible",
                onClick = onAmbientClick
            )

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
                .background(Color(0xFF1B1B20))
                .border(
                    width = 1.dp,
                    color = Color(0xFF303038),
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
                color = if (enabled) Color.White else Color(0xFF74747C)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Color(0xFFAFAFB8) else Color(0xFF5F5F66)
            )
        }

        Text(
            text = "›",
            style = MaterialTheme.typography.headlineMedium,
            color = if (enabled) Color(0xFF8ECAE9) else Color(0xFF5F5F66)
        )
    }
}
