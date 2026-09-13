package com.yp.luminote.app.ui.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
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
import com.yp.luminote.app.ui.components.HaloAppearancePicker
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun AmbientHaloScreen(
    onBackClick: () -> Unit,
    viewModel: LuminoteSettingsViewModel
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

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
            context.startService(HaloOverlayService.createAmbientIntent(context, settings))
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
            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "‹",
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Text(
                    text = "Ambient Halo",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Personalize the edge of your screen while it is on.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFAFAFB8)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(Modifier.luminoteSafeHorizontalPadding()) {
                    AmbientGroup(title = "Ambient Halo") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Show Ambient Halo", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
                                Text("Keeps a decorative effect visible while the screen is on", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
                            }
                            Switch(
                                checked = settings.ambientEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setAmbientEnabled(enabled)
                                    if (!enabled) {
                                        context.startService(HaloOverlayService.createStopRepeatingIntent(context))
                                    }
                                }
                            )
                        }
                        if (settings.ambientEnabled) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Notification effects are paused while Ambient Halo is active.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFC870)
                            )
                        }
                    }
                }
            }

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
                    AmbientGroup(title = "Animation") {
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
                    AmbientGroup(title = "Colors") {
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
                    AmbientGroup(title = "Appearance") {
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
private fun AmbientGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .background(Color(0xFF1B1B20))
            .border(1.dp, Color(0xFF303038), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .padding(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(12.dp))
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
        Text(valueText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFAFAFB8))
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
                        onClick = { onColorSelected(color) }
                    )
                }
                if (rowIndex == 1) {
                    AmbientGradientColorOption(
                        selected = gradientSelected,
                        onClick = onGradientSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun AmbientColorOption(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(2.dp, color, androidx.compose.foundation.shape.CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(if (selected) 30.dp else 32.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun AmbientGradientColorOption(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
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
                    .size(40.dp)
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
