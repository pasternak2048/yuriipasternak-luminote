package com.yp.luminote.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.ThemeMode
import com.yp.luminote.app.data.settings.RenderingEngine
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteNavigationRow
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun SettingsScreen(onBackClick: () -> Unit, onAppearanceClick: () -> Unit, onLanguageClick: () -> Unit, onUpdatesClick: () -> Unit, onDiagnosticsClick: () -> Unit, onAboutClick: () -> Unit, viewModel: LuminoteSettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage(title = stringResource(R.string.settings), onBackClick = onBackClick) {
        RenderingEngineSetting(settings.renderingEngine, viewModel::setRenderingEngine)
        SettingsItem(stringResource(R.string.appearance), Icons.Outlined.Palette, onAppearanceClick)
        SettingsItem(stringResource(R.string.language), Icons.Outlined.Language, onLanguageClick)
        SettingsItem(stringResource(R.string.updates), Icons.Outlined.SystemUpdate, onUpdatesClick)
        SettingsItem(stringResource(R.string.diagnostics), Icons.Outlined.Info, onDiagnosticsClick)
        SettingsItem(stringResource(R.string.about), Icons.Outlined.Info, onAboutClick)
    }
}

@Composable
private fun RenderingEngineSetting(selectedEngine: RenderingEngine, onSelected: (RenderingEngine) -> Unit) {
    var showSelection by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = stringResource(
        if (selectedEngine == RenderingEngine.CANVAS) R.string.rendering_engine_standard else R.string.rendering_engine_gpu
    )
    LuminoteSettingsCard {
        Column(Modifier.padding(horizontal = 16.dp)) {
            LuminoteNavigationRow(
                title = stringResource(R.string.rendering_engine),
                subtitle = stringResource(R.string.rendering_engine_selected, selectedLabel),
                icon = Icons.Outlined.Memory,
                onClick = { showSelection = true }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.rendering_engine_resonance),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showSelection) {
        AlertDialog(
            onDismissRequest = { showSelection = false },
            title = { Text(stringResource(R.string.rendering_engine)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RenderingEngine.entries.forEach { engine ->
                            LuminoteSelectionRow(
                                title = stringResource(if (engine == RenderingEngine.CANVAS) R.string.rendering_engine_standard else R.string.rendering_engine_gpu),
                                description = stringResource(if (engine == RenderingEngine.CANVAS) R.string.rendering_engine_standard_description else R.string.rendering_engine_gpu_description),
                                selected = selectedEngine == engine,
                                onClick = { onSelected(engine) },
                                control = LuminoteSelectionControl.Radio
                            )
                        }
                    }
                    Text(stringResource(R.string.rendering_engine_compatibility), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSelection = false }) { Text(stringResource(R.string.done)) }
            }
        )
    }
}

@Composable
fun AppearanceScreen(onBackClick: () -> Unit, viewModel: LuminoteSettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage(title = stringResource(R.string.appearance), onBackClick = onBackClick) {
        LuminoteSettingsCard {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
private fun SettingsItem(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    LuminoteNavigationRow(title = title, icon = icon, onClick = onClick)
}
