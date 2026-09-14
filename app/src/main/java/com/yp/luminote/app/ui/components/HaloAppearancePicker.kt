package com.yp.luminote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.definition

@Composable
fun HaloAppearancePicker(
    modifier: Modifier = Modifier,
    frame: HaloFrame,
    motion: HaloMotion,
    availableMotions: List<HaloMotion> = frame.definition.supportedMotions,
    onMotionSelected: (HaloMotion) -> Unit,
) {
    Column(
        modifier = modifier
            .background(Color(0xFF101010), RoundedCornerShape(28.dp))
            .border(1.dp, Color(0xFF3D3D3D), RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Frame", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        AppearanceChoice(
            title = frame.definition.title,
            subtitle = frame.definition.description,
            selected = true
        )

        Text("Animation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            availableMotions.forEach { candidate ->
                val definition = candidate.definition
                AppearanceChoice(
                    title = definition.title,
                    subtitle = definition.description,
                    selected = motion == candidate,
                    onClick = { onMotionSelected(candidate) }
                )
            }
        }

    }
}

@Composable
private fun AppearanceChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.14f) else Color(0xFF1C1C1C),
                RoundedCornerShape(18.dp)
            )
            .border(
                if (selected) 1.dp else 0.dp,
                if (selected) Color.White else Color.Transparent,
                RoundedCornerShape(18.dp)
            )
            .then(onClick?.takeIf { enabled }?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color.White else Color(0xFF808080)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) Color(0xFFBDBDBD) else Color(0xFF808080)
            )
        }
    }
}
