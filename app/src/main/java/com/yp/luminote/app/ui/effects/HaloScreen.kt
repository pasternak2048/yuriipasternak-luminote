package com.yp.luminote.app.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.definition
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloColorSource
import com.yp.luminote.app.data.settings.NotificationPlayback
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.HaloAppearancePicker
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun HaloScreen(
    onBackClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass,
    viewModel: LuminoteSettingsViewModel
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(context) {
        onDispose {
            context.startService(HaloOverlayService.createStopPreviewIntent(context))
        }
    }

    val settings by
    viewModel.settings.collectAsState()

    val selectedColor =
        Color(settings.haloColor)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
    ) {

        /*
         * =========================================================
         * HEADER
         * =========================================================
         */

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .luminoteSafeHorizontalPadding()
        ) {

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "‹",
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clickable(
                                onClick =
                                    onBackClick
                            ),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Text(
                    text = "Luminote Halo",
                    modifier =
                        Modifier.padding(
                            start = 4.dp
                        ),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Text(
                text =
                    "Customize your notification lighting",
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                color = Color(0xFFAFAFB8)
            )
        }

        /*
         * =========================================================
         * CONTENT
         * =========================================================
         *
         * IMPORTANT:
         * weight() is used here, directly inside ColumnScope.
         * Helper composables themselves do not use weight().
         */

        when (windowSizeClass) {

            LuminoteWindowSizeClass.COMPACT -> {

                CompactHaloContent(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    settings =
                        settings,
                    selectedColor =
                        selectedColor,
                    viewModel =
                        viewModel
                )
            }

            LuminoteWindowSizeClass.MEDIUM,
            LuminoteWindowSizeClass.EXPANDED -> {

                WideHaloContent(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    settings =
                        settings,
                    selectedColor =
                        selectedColor,
                    viewModel =
                        viewModel
                )
            }
        }

        TestEffectButton(
            context = context,
            settings = settings
        )
    }
}

@Composable
private fun CompactHaloContent(
    modifier: Modifier,
    settings: LuminoteSettings,
    selectedColor: Color,
    viewModel: LuminoteSettingsViewModel
) {
    LazyColumn(
        modifier =
            modifier,
        contentPadding =
            PaddingValues(
                top = 24.dp,
                bottom = 16.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(20.dp)
    ) {

        item {

            HaloAppearancePicker(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .luminoteSafeHorizontalPadding(),
                frame = settings.haloFrame,
                motion = settings.haloMotion,
                onMotionSelected = viewModel::setHaloMotion,
            )
        }

        item {

            Column(modifier = Modifier.luminoteSafeHorizontalPadding()) {
                AppearanceGroup(
                    settings = settings,
                    selectedColor = selectedColor,
                    viewModel = viewModel
                )
            }
        }

        item {

            Column(modifier = Modifier.luminoteSafeHorizontalPadding()) {
                MotionGroup(settings = settings, viewModel = viewModel)
            }
        }

        item {

            Column(modifier = Modifier.luminoteSafeHorizontalPadding()) {
                TimingGroup(settings = settings, viewModel = viewModel)
            }
        }

    }
}

@Composable
private fun WideHaloContent(
    modifier: Modifier,
    settings: LuminoteSettings,
    selectedColor: Color,
    viewModel: LuminoteSettingsViewModel
) {
    Row(
        modifier =
            modifier
                .luminoteSafeHorizontalPadding()
                .padding(
                    top = 24.dp,
                    bottom = 16.dp
                ),
        horizontalArrangement =
            Arrangement.spacedBy(20.dp),
        verticalAlignment =
            Alignment.Top
    ) {

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            item {

                HaloAppearancePicker(
                    modifier =
                        Modifier.fillMaxWidth(),
                    frame = settings.haloFrame,
                    motion = settings.haloMotion,
                    onMotionSelected = viewModel::setHaloMotion,
                )
            }
        }

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            item {

                AppearanceGroup(
                    settings =
                        settings,
                    selectedColor =
                        selectedColor,
                    viewModel =
                        viewModel
                )
            }

            item {

                MotionGroup(
                    settings = settings,
                    viewModel = viewModel
                )
            }

            item {

                TimingGroup(
                    settings =
                        settings,
                    viewModel =
                        viewModel
                )
            }

        }
    }
}

@Composable
private fun AppearanceGroup(
    settings: LuminoteSettings,
    selectedColor: Color,
    viewModel: LuminoteSettingsViewModel
) {
    SettingsGroup(
        title = "Colors"
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Match app color", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
                Text("Use the notifying app’s icon color", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
            }
            Switch(
                checked = settings.colorSource == HaloColorSource.APP_ICON,
                onCheckedChange = viewModel::setAppIconBasedColor
            )
        }

        if (settings.colorSource != HaloColorSource.APP_ICON) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Halo color", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            ColorGrid(
                selectedColor = selectedColor,
                gradientSelected = settings.colorSource == HaloColorSource.GRADIENT,
                onColorSelected = { color ->
                    viewModel.setHaloColor(color.toArgb())
                    viewModel.setHaloColorMode(HaloColorMode.SOLID)
                },
                onGradientSelected = { viewModel.setHaloColorMode(HaloColorMode.GRADIENT) }
            )
        }

        if (settings.colorSource == HaloColorSource.GRADIENT) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use app palette", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
                    Text("Build the palette from active alerts", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
                }
                Switch(
                    checked = settings.gradientPalette == com.yp.luminote.app.data.settings.GradientPalette.NOTIFICATION_APPS,
                    onCheckedChange = { enabled ->
                        viewModel.setGradientPalette(
                            if (enabled) com.yp.luminote.app.data.settings.GradientPalette.NOTIFICATION_APPS
                            else com.yp.luminote.app.data.settings.GradientPalette.LUMINOTE
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            SliderSetting(
                title = "Color flow",
                value = settings.gradientFlowSpeed,
                valueText = "${"%.1f".format(settings.gradientFlowSpeed)}×",
                valueRange = 0.5f..2.5f,
                steps = 3,
                onValueChange = viewModel::setGradientFlowSpeed,
                onValueChangeFinished = viewModel::flushPendingSettings
            )
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

        SliderSetting(
            title = "Halo brightness",
            value =
                settings
                    .haloIntensity,
            valueText =
                "${(
                        settings
                            .haloIntensity *
                                100
                        ).toInt()}%",
            onValueChange = { value ->
                viewModel
                    .setHaloIntensity(
                        value
                    )
            },
            onValueChangeFinished =
                viewModel::flushPendingSettings
        )

        Spacer(
            modifier =
                Modifier.height(16.dp)
        )

        SliderSetting(
            title = "Edge width",
            value =
                settings
                    .haloThickness,
            valueText =
                "${(
                        settings
                            .haloThickness *
                                100
                        ).toInt()}%",
            onValueChange = { value ->
                viewModel
                    .setHaloThickness(
                        value
                    )
            },
            onValueChangeFinished =
                viewModel::flushPendingSettings
        )

    }
}

@Composable
private fun MotionGroup(
    settings: LuminoteSettings,
    viewModel: LuminoteSettingsViewModel
) {
    SettingsGroup(title = "Animation") {
        SliderSetting(
            title = "Effect speed",
            value = settings.haloEffectSpeed,
            valueText = "${"%.2g".format(settings.haloEffectSpeed)}×",
            valueRange = 0.25f..2f,
            steps = 6,
            onValueChange = viewModel::setHaloEffectSpeed,
            onValueChangeFinished = viewModel::flushPendingSettings
        )
    }
}

@Composable
private fun TimingGroup(
    settings: LuminoteSettings,
    viewModel: LuminoteSettingsViewModel
) {
    SettingsGroup(
        title = "Reminders"
    ) {

        val repeatsEnabled = settings.notificationPlayback != NotificationPlayback.ONCE
        RepeatHaloSetting(
            enabled = repeatsEnabled,
            onEnabledChange = { enabled ->
                viewModel.setNotificationPlayback(if (enabled) NotificationPlayback.REPEAT else NotificationPlayback.ONCE)
            }
        )

        if (repeatsEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaybackChoice(
                    selected = settings.notificationPlayback == NotificationPlayback.REPEAT,
                    title = "Repeat",
                    onClick = { viewModel.setNotificationPlayback(NotificationPlayback.REPEAT) },
                    modifier = Modifier.weight(1f)
                )
                PlaybackChoice(
                    selected = settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE,
                    title = "Keep visible",
                    onClick = { viewModel.setNotificationPlayback(NotificationPlayback.KEEP_VISIBLE) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (repeatsEnabled && settings.notificationPlayback == NotificationPlayback.REPEAT) {
            Spacer(modifier = Modifier.height(16.dp))
            val repeatPosition = settings.haloRepeatCount.toFloat()
            SliderSetting(
                title = "Reminder pulses",
                value = repeatPosition,
                valueText = "${settings.haloRepeatCount} times",
                valueRange = 2f..5f,
                steps = 3,
                onValueChange = { value -> viewModel.setHaloRepeatCount(value.toInt()) },
                onValueChangeFinished = viewModel::flushPendingSettings
            )

            Spacer(modifier = Modifier.height(16.dp))
            SliderSetting(
                title = "Repeat after",
                value = settings.haloInterval,
                valueText = "${"%.1f".format(settings.haloInterval)} s",
                valueRange = 0f..10f,
                onValueChange = viewModel::setHaloInterval,
                onValueChangeFinished = viewModel::flushPendingSettings
            )
        }

        if (settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(settings.haloMotion.definition.ambientDescription, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
            Text("Stops after all relevant alerts are dismissed", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
        }

    }
}

@Composable
private fun RepeatHaloSetting(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Repeat notification effect", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
            Text("Replay the effect for unread alerts", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAFAFB8))
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun PlaybackChoice(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF2478D4) else Color(0xFF303038),
            contentColor = Color.White
        )
    ) {
        Text(title)
    }
}

@Composable
private fun TestEffectButton(
    context: android.content.Context,
    settings: LuminoteSettings
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .luminoteSafeHorizontalPadding()
                .padding(
                    top = 8.dp,
                    bottom = 16.dp
                )
    ) {

        Button(
            onClick = {
                context.startService(
                    HaloOverlayService.createPreviewIntent(
                        context = context,
                        settings = settings.copy(
                            haloColor = if (settings.colorSource == HaloColorSource.APP_ICON) Color.White.toArgb() else settings.haloColor,
                            haloRepeatCount = if (settings.notificationPlayback == NotificationPlayback.REPEAT) settings.haloRepeatCount else 1,
                            notificationPlayback = NotificationPlayback.ONCE
                        )
                    )
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            enabled = !settings.ambientEnabled,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2478D4),
                contentColor = Color.White
            )
        ) {

            Text(
                text = if (settings.ambientEnabled) "Turn off Ambient Halo to preview" else "Preview Halo",
                style =
                    MaterialTheme
                        .typography
                        .labelLarge
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(28.dp)
                )
                .background(Color(0xFF1B1B20))
                .border(
                    width = 1.dp,
                    color = Color(0xFF303038),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 20.dp
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
            color = Color.White
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        content()
    }
}

@Composable
private fun ColorGrid(
    selectedColor: Color,
    gradientSelected: Boolean,
    onColorSelected: (Color) -> Unit,
    onGradientSelected: () -> Unit
) {
    val colors =
        listOf(
            Color(0xFF3E91FF),
            Color(0xFF64D2FF),
            Color(0xFF00C7BE),
            Color(0xFF30D158),
            Color(0xFFA8D800),
            Color(0xFFFFD60A),
            Color(0xFFFF9F0A),
            Color(0xFFFF453A),
            Color(0xFFFF375F),
            Color(0xFFFF6482),
            Color(0xFFBF5AF2),
            Color(0xFFAF52DE),
            Color(0xFF5E5CE6),
            Color(0xFF007AFF),
            Color.White,
            Color.White
        )

    Column(
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {

        colors.take(15)
            .chunked(8)
            .forEachIndexed { rowIndex, rowColors ->

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    rowColors.forEach { color ->

                        ColorOption(
                            color = color,
                            selected =
                                !gradientSelected && color.value == selectedColor.value,
                            onClick = {
                                onColorSelected(
                                    color
                                )
                            }
                        )
                    }
                    if (rowIndex == 1) {
                        GradientColorOption(
                            selected = gradientSelected,
                            onClick = onGradientSelected
                        )
                    }
                }
            }
    }
}

@Composable
private fun GradientColorOption(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(Color(0xFF3E91FF), Color(0xFFBF5AF2), Color(0xFFFF6482), Color(0xFFFF9F0A), Color(0xFF3E91FF))
                    )
                )
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun ColorOption(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(
                    onClick = onClick
                ),
        contentAlignment =
            Alignment.Center
    ) {

        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .border(
                            width = 2.dp,
                            color = color,
                            shape = CircleShape
                        )
            )
        }

        Box(
            modifier =
                Modifier
                    .size(
                        if (selected) {
                            30.dp
                        } else {
                            32.dp
                        }
                    )
                    .clip(CircleShape)
                    .background(
                        color = color,
                        shape = CircleShape
                    )
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueText: String,
    valueRange:
    ClosedFloatingPointRange<Float> =
        0f..1f,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {}
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = title,
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                fontWeight =
                    FontWeight.Medium,
                color = Color.White
            )

            Text(
                text = valueText,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color = Color(0xFFAFAFB8)
            )
        }

        Slider(
            value = value,
            onValueChange =
                onValueChange,
            onValueChangeFinished =
                onValueChangeFinished,
            valueRange =
                valueRange,
            steps = steps
        )
    }
}

