package com.yp.luminote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R

/** Shared color choice grid for the Halo and Ambient Halo settings. */
@Composable
fun LuminoteColorSwatchPicker(
    selectedColor: Color,
    gradientSelected: Boolean,
    onColorSelected: (Color) -> Unit,
    onGradientSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.selectableGroup()) {
        val columnCount = if (maxWidth / 5f >= 48.dp) 5 else 4
        val optionSize = minOf(56.dp, maxWidth / columnCount)
        val options = presetColors.map { ColorOption.Solid(it) } + ColorOption.Gradient

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.chunked(columnCount).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEach { option ->
                        when (option) {
                            is ColorOption.Solid -> ColorSwatch(
                                color = option.color,
                                selected = !gradientSelected && option.color.value == selectedColor.value,
                                size = optionSize,
                                onClick = { onColorSelected(option.color) }
                            )
                            ColorOption.Gradient -> GradientSwatch(
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
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.color_swatch, String.format("#%06X", color.toArgb() and 0xFFFFFF))
    SwatchContainer(label, selected, size, onClick) {
        if (selected) {
            Box(
                Modifier.size(size).border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
            )
        }
        Box(
            Modifier
                .size(size * if (selected) 0.75f else 0.8f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun GradientSwatch(selected: Boolean, size: Dp, onClick: () -> Unit) {
    val gradient = Brush.sweepGradient(gradientColors)
    SwatchContainer(stringResource(R.string.gradient_color), selected, size, onClick) {
        if (selected) {
            Box(
                Modifier.size(size).border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
            )
        }
        Box(
            Modifier
                .size(size * if (selected) 0.75f else 0.8f)
                .clip(CircleShape)
                .background(gradient)
        )
    }
}

@Composable
private fun SwatchContainer(
    label: String,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
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
    ) { content() }
}

private sealed interface ColorOption {
    data class Solid(val color: Color) : ColorOption
    data object Gradient : ColorOption
}

private val presetColors = listOf(
    Color(0xFF3E91FF), Color(0xFF64D2FF), Color(0xFF00C7BE), Color(0xFF30D158), Color(0xFFA8D800),
    Color(0xFFFFD60A), Color(0xFFFF9F0A), Color(0xFFFF453A), Color(0xFFFF375F),
    Color(0xFFFF6482), Color(0xFFBF5AF2), Color(0xFFAF52DE), Color(0xFF5E5CE6), Color.White
)

private val gradientColors = listOf(
    Color(0xFF3E91FF), Color(0xFFBF5AF2), Color(0xFFFF6482),
    Color(0xFFFF9F0A), Color(0xFF3E91FF)
)
