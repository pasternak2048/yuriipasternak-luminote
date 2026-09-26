package com.yp.luminote.app.ui.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.R
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.ui.components.HaloAppearancePicker
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSliderSetting
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteColorSwatchPicker
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun AmbientHaloScreen(
    onBackClick: () -> Unit,
    viewModel: LuminoteSettingsViewModel
) {
    val context = LocalContext.current
    val backDescription = stringResource(R.string.back)
    val settings by viewModel.settings.collectAsState()
    val uiMetrics = rememberLuminoteUiMetrics()

    LaunchedEffect(
        settings.ambientEnabled,
        settings.ambientColor,
        settings.ambientColorMode,
        settings.ambientIntensity,
        settings.ambientThickness,
        settings.ambientMotion,
        settings.ambientEffectSpeed,
        settings.ambientGradientFlowSpeed
    ) {
        if (settings.ambientEnabled) {
            HaloOverlayService.start(context, HaloOverlayService.createAmbientIntent(context, settings))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding()
        ) {
            Spacer(modifier = Modifier.height(uiMetrics.headerTopSpacing))
            LuminoteScreenHeader(
                title = stringResource(R.string.ambient_halo),
                backContentDescription = backDescription,
                onBackClick = onBackClick,
                backButtonSize = uiMetrics.backButtonSize
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ambient_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(uiMetrics.sectionSpacing))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = uiMetrics.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(uiMetrics.sectionSpacing)
        ) {
            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    HaloAppearancePicker(
                        modifier = Modifier.fillMaxWidth(),
                        frame = HaloFrame.CLASSIC,
                        motion = settings.ambientMotion,
                        availableMotions = listOf(HaloMotion.PULSE, HaloMotion.SNAKE),
                        onMotionSelected = viewModel::setAmbientMotion
                    )
                }
            }

            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    AmbientGroup(title = stringResource(R.string.animation), uiMetrics = uiMetrics) {
                        LuminoteSliderSetting(
                            title = stringResource(R.string.effect_speed),
                            value = settings.ambientEffectSpeed,
                            valueText = stringResource(R.string.multiplier, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.2g", settings.ambientEffectSpeed)),
                            valueRange = 0.25f..2f,
                            steps = 6,
                            onValueChange = viewModel::setAmbientEffectSpeed,
                            onValueChangeFinished = viewModel::flushPendingSettings
                        )
                    }
                }
            }

            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    AmbientGroup(title = stringResource(R.string.colors), uiMetrics = uiMetrics) {
                        Text(stringResource(R.string.ambient_color), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        AmbientColorGrid(
                            selectedColor = Color(settings.ambientColor),
                            gradientSelected = settings.ambientColorMode == HaloColorMode.GRADIENT,
                            onColorSelected = { viewModel.setAmbientColor(it.toArgb()) },
                            onGradientSelected = { viewModel.setAmbientColorMode(HaloColorMode.GRADIENT) }
                        )
                        if (settings.ambientColorMode == HaloColorMode.GRADIENT) {
                            Spacer(Modifier.height(18.dp))
                            LuminoteSliderSetting(
                                title = stringResource(R.string.color_flow),
                                value = settings.ambientGradientFlowSpeed,
                                valueText = stringResource(R.string.multiplier, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", settings.ambientGradientFlowSpeed)),
                                valueRange = 0.5f..2.5f,
                                steps = 3,
                                onValueChange = viewModel::setAmbientGradientFlowSpeed,
                                onValueChangeFinished = viewModel::flushPendingSettings
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    AmbientGroup(title = stringResource(R.string.appearance), uiMetrics = uiMetrics) {
                        LuminoteSliderSetting(
                            title = stringResource(R.string.halo_brightness),
                            value = settings.ambientIntensity,
                            valueText = stringResource(R.string.percentage, (settings.ambientIntensity * 100).toInt()),
                            onValueChange = viewModel::setAmbientIntensity,
                            onValueChangeFinished = viewModel::flushPendingSettings
                        )
                        Spacer(Modifier.height(16.dp))
                        LuminoteSliderSetting(
                            title = stringResource(R.string.edge_width),
                            value = settings.ambientThickness,
                            valueText = stringResource(R.string.percentage, (settings.ambientThickness * 100).toInt()),
                            onValueChange = viewModel::setAmbientThickness,
                            onValueChangeFinished = viewModel::flushPendingSettings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmbientGroup(
    title: String,
    uiMetrics: com.yp.luminote.app.ui.adaptive.LuminoteUiMetrics,
    content: @Composable () -> Unit
) {
    LuminoteSettingsCard {
        Column(modifier = Modifier.padding(uiMetrics.cardPadding)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(if (uiMetrics.isCompactHeight) 8.dp else 12.dp))
        content()
        }
    }
}

@Composable
private fun AmbientColorGrid(
    selectedColor: Color,
    gradientSelected: Boolean,
    onColorSelected: (Color) -> Unit,
    onGradientSelected: () -> Unit
) {
    LuminoteColorSwatchPicker(
        selectedColor = selectedColor,
        gradientSelected = gradientSelected,
        onColorSelected = onColorSelected,
        onGradientSelected = onGradientSelected
    )
}
