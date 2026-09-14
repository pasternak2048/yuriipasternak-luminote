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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.adaptive.rememberLuminoteUiMetrics
import com.yp.luminote.app.ui.components.HaloAppearancePicker
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun AmbientHaloScreen(
    onBackClick: () -> Unit,
    viewModel: LuminoteSettingsViewModel
) {
    val context = LocalContext.current
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
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding()
        ) {
            Spacer(modifier = Modifier.height(uiMetrics.headerTopSpacing))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "‹",
                    modifier = Modifier
                        .size(uiMetrics.backButtonSize)
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Text(
                    text = "Ambient Halo",
                    style = if (uiMetrics.isCompactHeight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Personalize the edge of your screen while it is on.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFBDBDBD)
            )
        }

        Spacer(modifier = Modifier.height(uiMetrics.sectionSpacing))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                    AmbientGroup(title = "Animation", uiMetrics = uiMetrics) {
                        AmbientSlider(
                            title = "Effect speed",
                            value = settings.ambientEffectSpeed,
                            valueText = "${"%.2g".format(settings.ambientEffectSpeed)}×",
                            range = 0.25f..2f,
                            steps = 6,
                            onValueChange = viewModel::setAmbientEffectSpeed,
                            onValueChangeFinished = viewModel::flushPendingSettings
                        )
                    }
                }
            }

            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    AmbientGroup(title = "Colors", uiMetrics = uiMetrics) {
                        Text("Ambient color", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        AmbientColorGrid(
                            selectedColor = Color(settings.ambientColor),
                            gradientSelected = settings.ambientColorMode == HaloColorMode.GRADIENT,
                            onColorSelected = { viewModel.setAmbientColor(it.toArgb()) },
                            onGradientSelected = { viewModel.setAmbientColorMode(HaloColorMode.GRADIENT) }
                        )
                        if (settings.ambientColorMode == HaloColorMode.GRADIENT) {
                            Spacer(Modifier.height(18.dp))
                            AmbientSlider(
                                title = "Color flow",
                                value = settings.ambientGradientFlowSpeed,
                                valueText = "${"%.1f".format(settings.ambientGradientFlowSpeed)}×",
                                range = 0.5f..2.5f,
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
                    AmbientGroup(title = "Appearance", uiMetrics = uiMetrics) {
                        AmbientSlider(
                            title = "Halo brightness",
                            value = settings.ambientIntensity,
                            valueText = "${(settings.ambientIntensity * 100).toInt()}%",
                            onValueChange = viewModel::setAmbientIntensity,
                            onValueChangeFinished = viewModel::flushPendingSettings
                        )
                        Spacer(Modifier.height(16.dp))
                        AmbientSlider(
                            title = "Edge width",
                            value = settings.ambientThickness,
                            valueText = "${(settings.ambientThickness * 100).toInt()}%",
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(uiMetrics.cardCornerRadius))
            .background(Color(0xFF101010))
            .border(1.dp, Color(0xFF3D3D3D), androidx.compose.foundation.shape.RoundedCornerShape(uiMetrics.cardCornerRadius))
            .padding(uiMetrics.cardPadding)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(if (uiMetrics.isCompactHeight) 8.dp else 12.dp))
        content()
    }
}

@Composable
private fun AmbientSlider(
    title: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
        Text(valueText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFBDBDBD))
    }
    Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, onValueChangeFinished = onValueChangeFinished)
}

@Composable
private fun AmbientColorGrid(
    selectedColor: Color,
    gradientSelected: Boolean,
    onColorSelected: (Color) -> Unit,
    onGradientSelected: () -> Unit
) {
    val colors = listOf(
        Color(0xFF3E91FF), Color(0xFF64D2FF), Color(0xFF00C7BE), Color(0xFF30D158),
        Color(0xFFA8D800), Color(0xFFFFD60A), Color(0xFFFF9F0A), Color(0xFFFF453A),
        Color(0xFFFF375F), Color(0xFFFF6482), Color(0xFFBF5AF2), Color(0xFFAF52DE),
        Color(0xFF5E5CE6), Color(0xFF007AFF), Color.White
    )
    BoxWithConstraints {
        val optionSize = minOf(40.dp, maxWidth / 8f)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.chunked(8).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { color ->
                    AmbientColorOption(
                        color = color,
                        selected = !gradientSelected && color.value == selectedColor.value,
                        size = optionSize,
                        onClick = { onColorSelected(color) }
                    )
                }
                if (rowIndex == 1) {
                    AmbientGradientColorOption(
                        selected = gradientSelected,
                        size = optionSize,
                        onClick = onGradientSelected
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AmbientColorOption(color: Color, selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(size)
                    .border(2.dp, color, androidx.compose.foundation.shape.CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size * if (selected) 0.75f else 0.8f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun AmbientGradientColorOption(selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFF3E91FF), Color(0xFFBF5AF2), Color(0xFFFF6482),
                            Color(0xFFFF9F0A), Color(0xFF3E91FF)
                        )
                    )
                )
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(size)
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
