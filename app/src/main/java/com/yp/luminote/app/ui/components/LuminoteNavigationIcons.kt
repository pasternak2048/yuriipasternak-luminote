package com.yp.luminote.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun LuminoteBackButton(
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
fun LuminoteExpandIcon(
    expanded: Boolean,
    tint: Color,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

@Composable
fun LuminoteExternalLinkIcon(tint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        modifier = modifier,
        tint = tint
    )
}

@Composable
fun LuminoteForwardIcon(tint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        modifier = modifier,
        tint = tint
    )
}
