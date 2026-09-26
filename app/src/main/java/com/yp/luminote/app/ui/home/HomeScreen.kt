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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.data.settings.ThemeMode
import com.yp.luminote.app.R
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.ui.components.LuminoteForwardIcon
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import androidx.compose.ui.semantics.Role

@Composable
fun HomeScreen(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass,
    viewModel: LuminoteSettingsViewModel
) {
    val settings by
    viewModel.settings.collectAsState()

    val settingsLoaded by
    viewModel.settingsLoaded.collectAsState()

    val uiMetrics =
        rememberLuminoteUiMetrics()

    val context =
        LocalContext.current

    val onModeSelected:
                (HaloMode) -> Unit = { mode ->
        viewModel.setHaloMode(mode)

        when (mode) {
            HaloMode.AMBIENT ->
                HaloOverlayService.start(
                    context,
                    HaloOverlayService.createAmbientIntent(
                        context,
                        settings.copy(
                            haloMode =
                                HaloMode.AMBIENT
                        )
                    )
                )

            HaloMode.NOTIFICATIONS,
            HaloMode.OFF ->
                HaloOverlayService.start(
                    context,
                    HaloOverlayService
                        .createStopRepeatingIntent(
                            context
                        )
                )
        }
    }

    if (!settingsLoaded) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.background
                    ),
            contentAlignment =
                Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier.size(
                        24.dp
                    )
            )
        }

        return
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.background
                )
                .statusBarsPadding()
                .luminoteSafeHorizontalPadding()
                .navigationBarsPadding()
                .verticalScroll(
                    rememberScrollState()
                )
    ) {
        Spacer(
            modifier =
                Modifier.height(
                    if (
                        uiMetrics.isCompactHeight
                    ) {
                        20.dp
                    } else {
                        32.dp
                    }
                )
        )

        Text(
            text = stringResource(R.string.app_name),
            style =
                if (
                    uiMetrics.isCompactHeight
                ) {
                    MaterialTheme
                        .typography
                        .displayMedium
                } else {
                    MaterialTheme
                        .typography
                        .displayLarge
                },
            fontWeight =
                FontWeight.SemiBold,
            color =
                MaterialTheme.colorScheme.onBackground
        )

        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )

        Text(
            text =
                stringResource(R.string.home_tagline),
            style =
                MaterialTheme
                    .typography
                    .bodyLarge,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier =
                Modifier.height(
                    if (
                        uiMetrics.isCompactHeight
                    ) {
                        24.dp
                    } else {
                        36.dp
                    }
                )
        )

        when (windowSizeClass) {
            LuminoteWindowSizeClass.COMPACT -> {
                CompactHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick =
                        onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onLanguageClick =
                        onLanguageClick,
                    onAboutClick =
                        onAboutClick,
                        onAccessClick =
                            onAccessClick,
                    themeMode = settings.themeMode,
                    onThemeModeSelected = viewModel::setThemeMode,
                    mode =
                        settings.haloMode,
                    onModeSelected =
                        onModeSelected
                )
            }

            LuminoteWindowSizeClass.MEDIUM -> {
                MediumHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick =
                        onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onLanguageClick =
                        onLanguageClick,
                    onAboutClick =
                        onAboutClick,
                        onAccessClick =
                            onAccessClick,
                    themeMode = settings.themeMode,
                    onThemeModeSelected = viewModel::setThemeMode,
                    mode =
                        settings.haloMode,
                    onModeSelected =
                        onModeSelected
                )
            }

            LuminoteWindowSizeClass.EXPANDED -> {
                ExpandedHomeContent(
                    onHaloClick =
                        onHaloClick,
                    onAmbientClick =
                        onAmbientClick,
                    onAppsClick =
                        onAppsClick,
                    onLanguageClick =
                        onLanguageClick,
                    onAboutClick =
                        onAboutClick,
                        onAccessClick =
                            onAccessClick,
                    themeMode = settings.themeMode,
                    onThemeModeSelected = viewModel::setThemeMode,
                    mode =
                        settings.haloMode,
                    onModeSelected =
                        onModeSelected
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
    onLanguageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    HomeSettingsList(
        mode = mode,
        onModeSelected =
            onModeSelected,
        onHaloClick =
            onHaloClick,
        onAmbientClick =
            onAmbientClick,
        onAppsClick =
            onAppsClick,
        onLanguageClick =
            onLanguageClick,
        onAboutClick =
            onAboutClick,
            onAccessClick =
            onAccessClick,
        themeMode = themeMode,
        onThemeModeSelected = onThemeModeSelected,
        spacing =
            12.dp
    )
}

@Composable
private fun MediumHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
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
        horizontalArrangement =
            Arrangement.Center
    ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {
            HomeSettingsList(
                mode = mode,
                onModeSelected =
                    onModeSelected,
                onHaloClick =
                    onHaloClick,
                onAmbientClick =
                    onAmbientClick,
                onAppsClick =
                    onAppsClick,
                onLanguageClick =
                    onLanguageClick,
                onAboutClick =
                    onAboutClick,
                onAccessClick =
                    onAccessClick,
                themeMode = themeMode,
                onThemeModeSelected = onThemeModeSelected,
                spacing =
                    12.dp
            )
        }
    }
}

@Composable
private fun ExpandedHomeContent(
    onHaloClick: () -> Unit,
    onAmbientClick: () -> Unit,
    onAppsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
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
        horizontalArrangement =
            Arrangement.Center
    ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    16.dp
                )
        ) {
            HomeSettingsList(
                mode = mode,
                onModeSelected =
                    onModeSelected,
                onHaloClick =
                    onHaloClick,
                onAmbientClick =
                    onAmbientClick,
                onAppsClick =
                    onAppsClick,
                onLanguageClick =
                    onLanguageClick,
                onAboutClick =
                    onAboutClick,
                onAccessClick =
                    onAccessClick,
                themeMode = themeMode,
                onThemeModeSelected = onThemeModeSelected,
                spacing =
                    16.dp
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
    onLanguageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccessClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    spacing: Dp
) {
    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                spacing
            )
    ) {
        HomeModeSelector(
            mode = mode,
            onModeSelected =
                onModeSelected
        )

        HomeThemeSelector(
            themeMode = themeMode,
            onThemeModeSelected = onThemeModeSelected
        )

        when (mode) {
            HaloMode.NOTIFICATIONS -> {
                HomeSettingItem(
                    title =
                        stringResource(R.string.luminote_halo),
                    subtitle =
                        stringResource(R.string.home_halo_description),
                    onClick =
                        onHaloClick
                )

                HomeSettingItem(
                    title =
                        stringResource(R.string.apps),
                    subtitle =
                        stringResource(R.string.home_apps_description),
                    onClick =
                        onAppsClick
                )
            }

            HaloMode.AMBIENT ->
                HomeSettingItem(
                    title =
                        stringResource(R.string.ambient_halo),
                    subtitle =
                        stringResource(R.string.home_ambient_description),
                    onClick =
                        onAmbientClick
                )

            HaloMode.OFF ->
                Unit
        }

        HomeSettingItem(
            title = stringResource(R.string.access),
            subtitle =
                stringResource(R.string.home_access_description),
            onClick =
                onAccessClick
        )

        HomeSettingItem(
            title = stringResource(R.string.language),
            subtitle = stringResource(R.string.home_language_description),
            onClick = onLanguageClick
        )

        HomeSettingItem(
            title = stringResource(R.string.about),
            subtitle =
                stringResource(R.string.home_about_description),
            onClick =
                onAboutClick
        )
    }
}

@Composable
private fun HomeThemeSelector(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    LuminoteSettingsCard {
        Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = stringResource(R.string.theme),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.theme_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            val label = when (mode) {
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system_default)
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(
                        selected = themeMode == mode,
                        role = Role.RadioButton,
                        onClick = { onThemeModeSelected(mode) }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = themeMode == mode, onClick = null)
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        }
        }
    }
}

@Composable
private fun HomeModeSelector(
    mode: HaloMode,
    onModeSelected: (HaloMode) -> Unit
) {
    LuminoteSettingsCard {
        Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = stringResource(R.string.mode),
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.SemiBold,
            color =
                MaterialTheme.colorScheme.onSurface
        )

        Spacer(
            modifier =
                Modifier.height(
                    4.dp
                )
        )

        Text(
            text =
                stringResource(R.string.mode_description),
            style =
                MaterialTheme
                    .typography
                    .bodyMedium,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier =
                Modifier.height(
                    16.dp
                )
        )

        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {
            HomeModeChoice(
                label = stringResource(R.string.alerts),
                selected =
                    mode ==
                            HaloMode.NOTIFICATIONS,
                onClick = {
                    onModeSelected(
                        HaloMode.NOTIFICATIONS
                    )
                }
            )

            HomeModeChoice(
                label = stringResource(R.string.ambient),
                selected =
                    mode ==
                            HaloMode.AMBIENT,
                onClick = {
                    onModeSelected(
                        HaloMode.AMBIENT
                    )
                }
            )

            HomeModeChoice(
                label = stringResource(R.string.off),
                selected =
                    mode ==
                            HaloMode.OFF,
                onClick = {
                    onModeSelected(
                        HaloMode.OFF
                    )
                }
            )
        }
        }
    }
}

@Composable
private fun RowScope.HomeModeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape =
        RoundedCornerShape(
            16.dp
        )

    Column(
        modifier =
            Modifier
                .weight(
                    1f
                )
                .clip(
                    shape
                )
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .border(
                    width =
                        1.dp,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    shape =
                        shape
                )
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .padding(
                    vertical =
                        12.dp
                ),
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style =
                MaterialTheme
                    .typography
                    .labelLarge,
            fontWeight =
                FontWeight.Medium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
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
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    LuminoteSettingsCard(
        modifier = Modifier
                .clickable(
                    enabled =
                        enabled,
                    onClick =
                        onClick
                )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.SemiBold,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        contentColor
                    }
            )

            Spacer(
                modifier =
                    Modifier.height(
                        4.dp
                    )
            )

            Text(
                text = subtitle,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        contentColor
                    }
            )
        }

        LuminoteForwardIcon(tint = contentColor)
        }
    }
}
