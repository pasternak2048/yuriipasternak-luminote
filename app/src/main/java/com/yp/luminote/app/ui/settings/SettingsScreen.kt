package com.yp.luminote.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.ThemeMode
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteNavigationRow
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun SettingsScreen(onBackClick: () -> Unit, onAppearanceClick: () -> Unit, onLanguageClick: () -> Unit, onUpdatesClick: () -> Unit, onAboutClick: () -> Unit) {
    SettingsPage(title = stringResource(R.string.settings), onBackClick = onBackClick) {
        SettingsItem(stringResource(R.string.appearance), stringResource(R.string.settings_appearance_description), Icons.Outlined.Palette, onAppearanceClick)
        SettingsItem(stringResource(R.string.language), stringResource(R.string.home_language_description), Icons.Outlined.Language, onLanguageClick)
        SettingsItem(stringResource(R.string.updates), stringResource(R.string.settings_updates_description), Icons.Outlined.SystemUpdate, onUpdatesClick)
        SettingsItem(stringResource(R.string.about), stringResource(R.string.home_about_description), Icons.Outlined.Info, onAboutClick)
    }
}

@Composable
fun AppearanceScreen(onBackClick: () -> Unit, viewModel: LuminoteSettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsPage(title = stringResource(R.string.appearance), onBackClick = onBackClick) {
        LuminoteSettingsCard {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.theme_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { option ->
                        val label = when (option) { ThemeMode.SYSTEM -> stringResource(R.string.theme_system_default); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.DARK -> stringResource(R.string.theme_dark) }
                        LuminoteSelectionRow(
                            title = label,
                            selected = settings.themeMode == option,
                            onClick = { viewModel.setThemeMode(option) },
                            control = LuminoteSelectionControl.Radio
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(title: String, onBackClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().luminoteSafeHorizontalPadding()) {
            Spacer(Modifier.height(20.dp))
            LuminoteScreenHeader(title, stringResource(R.string.back), onBackClick)
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    LuminoteNavigationRow(title, subtitle, icon, onClick)
}
