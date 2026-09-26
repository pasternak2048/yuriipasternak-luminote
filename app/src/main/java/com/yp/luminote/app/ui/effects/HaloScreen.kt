package com.yp.luminote.app.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.definition
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloColorSource
import com.yp.luminote.app.data.settings.NotificationPlayback
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.R
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.HaloAppearancePicker
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard

@Composable
fun HaloScreen(
    onBackClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass,
    viewModel: LuminoteSettingsViewModel
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current
    val backDescription = stringResource(R.string.back)

    DisposableEffect(context) {
        onDispose {
            HaloOverlayService.start(context, HaloOverlayService.createStopPreviewIntent(context))
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
                .background(MaterialTheme.colorScheme.background)
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

            LuminoteScreenHeader(
                title = stringResource(R.string.luminote_halo),
                backContentDescription = backDescription,
                onBackClick = onBackClick
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Text(
                text =
                    stringResource(R.string.customize_notification_lighting),
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    LazyColumn(
        modifier = modifier.luminoteSafeHorizontalPadding(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                HaloAppearancePicker(
                    modifier = Modifier.weight(1f),
                    frame = settings.haloFrame,
                    motion = settings.haloMotion,
                    onMotionSelected = viewModel::setHaloMotion,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    AppearanceGroup(settings, selectedColor, viewModel)
                    MotionGroup(settings, viewModel)
                    TimingGroup(settings, viewModel)
                }
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
        title = stringResource(R.string.colors)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.match_app_color), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.match_app_color_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.colorSource == HaloColorSource.APP_ICON,
                onCheckedChange = viewModel::setAppIconBasedColor
            )
        }

        if (settings.colorSource != HaloColorSource.APP_ICON) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.halo_color), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
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
                    Text(stringResource(R.string.use_app_palette), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.use_app_palette_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                title = stringResource(R.string.color_flow),
                value = settings.gradientFlowSpeed,
                valueText = stringResource(R.string.multiplier, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", settings.gradientFlowSpeed)),
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
            title = stringResource(R.string.halo_brightness),
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
            title = stringResource(R.string.edge_width),
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
    SettingsGroup(title = stringResource(R.string.animation)) {
        SliderSetting(
            title = stringResource(R.string.effect_speed),
            value = settings.haloEffectSpeed,
            valueText = stringResource(R.string.multiplier, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.2g", settings.haloEffectSpeed)),
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
        title = stringResource(R.string.reminders)
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
                    title = stringResource(R.string.repeat),
                    onClick = { viewModel.setNotificationPlayback(NotificationPlayback.REPEAT) },
                    modifier = Modifier.weight(1f)
                )
                PlaybackChoice(
                    selected = settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE,
                    title = stringResource(R.string.keep_visible),
                    onClick = { viewModel.setNotificationPlayback(NotificationPlayback.KEEP_VISIBLE) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (repeatsEnabled && settings.notificationPlayback == NotificationPlayback.REPEAT) {
            Spacer(modifier = Modifier.height(16.dp))
            val repeatPosition = settings.haloRepeatCount.toFloat()
            SliderSetting(
                title = stringResource(R.string.reminder_pulses),
                value = repeatPosition,
                valueText = androidx.compose.ui.res.pluralStringResource(R.plurals.times, settings.haloRepeatCount, settings.haloRepeatCount),
                valueRange = 2f..5f,
                steps = 3,
                onValueChange = { value -> viewModel.setHaloRepeatCount(value.toInt()) },
                onValueChangeFinished = viewModel::flushPendingSettings
            )

            Spacer(modifier = Modifier.height(16.dp))
            SliderSetting(
                title = stringResource(R.string.repeat_after),
                value = settings.haloInterval,
                valueText = stringResource(R.string.seconds, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", settings.haloInterval)),
                valueRange = 0f..10f,
                onValueChange = viewModel::setHaloInterval,
                onValueChangeFinished = viewModel::flushPendingSettings
            )
        }

        if (settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(settings.haloMotion.definition.ambientDescriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.stops_when_alerts_dismissed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

    }
}

@Composable
private fun RepeatHaloSetting(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.repeat_notification_effect), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.repeat_notification_effect_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
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
                HaloOverlayService.start(
                    context,
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
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {

            Text(
                text = stringResource(if (settings.ambientEnabled) R.string.turn_off_ambient_to_preview else R.string.preview_halo),
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
    LuminoteSettingsCard(shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(
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
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        content()
        }
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

    BoxWithConstraints {
        val columnCount = if (maxWidth / 5f >= 48.dp) 5 else 4
        val optionSize = minOf(56.dp, maxWidth / columnCount)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

        colors.take(15)
            .chunked(columnCount)
            .forEach { rowColors ->

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
                            size = optionSize,
                            onClick = {
                                onColorSelected(
                                    color
                                )
                            }
                        )
                    }
                }
            }
        Row(modifier = Modifier.fillMaxWidth()) {
            GradientColorOption(
                selected = gradientSelected,
                size = optionSize,
                onClick = onGradientSelected
            )
        }
        }
    }
}

@Composable
private fun GradientColorOption(
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val label = stringResource(R.string.gradient_color)
    val stateLabel = stringResource(if (selected) R.string.selected_state else R.string.not_selected_state)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics {
                contentDescription = label
                stateDescription = stateLabel
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
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
                    .size(size)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun ColorOption(
    color: Color,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val label = stringResource(
        R.string.color_swatch,
        String.format("#%06X", color.toArgb() and 0xFFFFFF)
    )
    val stateLabel = stringResource(if (selected) R.string.selected_state else R.string.not_selected_state)
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics {
                    contentDescription = label
                    stateDescription = stateLabel
                },
        contentAlignment =
            Alignment.Center
    ) {

        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(size)
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
                        size * if (selected) 0.75f else 0.8f
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
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = valueText,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

