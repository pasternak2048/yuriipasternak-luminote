package com.yp.luminote.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.definition
import com.yp.luminote.app.R

@Composable
fun HaloAppearancePicker(
    modifier: Modifier = Modifier,
    frame: HaloFrame,
    motion: HaloMotion,
    availableMotions: List<HaloMotion> = frame.definition.supportedMotions,
    onMotionSelected: (HaloMotion) -> Unit,
) {
    LuminoteSettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.frame), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                HaloFrame.entries.forEach { candidate ->
                    val definition = candidate.definition
                    LuminoteSelectionRow(
                        title = stringResource(definition.titleRes),
                        description = stringResource(definition.descriptionRes),
                        selected = frame == candidate,
                        // CLASSIC is currently the only supported frame. Keep this a radio
                        // group so future frame options use the same accessible contract.
                        onClick = {},
                        control = LuminoteSelectionControl.Radio,
                        enabled = false
                    )
                }
            }

            Text(stringResource(R.string.animation), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableMotions.forEach { candidate ->
                    val definition = candidate.definition
                    LuminoteSelectionRow(
                        title = stringResource(definition.titleRes),
                        description = stringResource(definition.descriptionRes),
                        selected = motion == candidate,
                        onClick = { onMotionSelected(candidate) },
                        control = LuminoteSelectionControl.Radio
                    )
                }
            }
        }
    }
}
