package com.yp.luminote.app.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.MAX_DISPLAY_CALIBRATION_DP
import com.yp.luminote.app.data.settings.MIN_DISPLAY_CALIBRATION_DP
import com.yp.luminote.app.data.settings.MIN_DISPLAY_CORNER_SHAPE
import com.yp.luminote.app.data.settings.MAX_DISPLAY_CORNER_SHAPE
import com.yp.luminote.app.data.settings.sanitizeDisplayCalibration
import com.yp.luminote.app.data.settings.sanitizeDisplayCornerShape
import com.yp.luminote.app.effects.HaloEffectController
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSliderSetting
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun DisplayCalibrationScreen(onBackClick: () -> Unit, viewModel: LuminoteSettingsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val token = remember { java.util.UUID.randomUUID().toString() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density
    var edge by remember(settings.displayEdgeCalibrationDp) { mutableFloatStateOf(sanitizeDisplayCalibration(settings.displayEdgeCalibrationDp)) }
    var corner by remember(settings.displayCornerCalibrationDp) { mutableFloatStateOf(sanitizeDisplayCalibration(settings.displayCornerCalibrationDp)) }
    var cornerShape by remember(settings.displayCornerShape) { mutableFloatStateOf(sanitizeDisplayCornerShape(settings.displayCornerShape)) }
    val currentDraft by rememberUpdatedState {
        settings.copy(
            displayEdgeCalibrationDp = edge,
            displayCornerCalibrationDp = corner,
            displayCornerShape = cornerShape
        )
    }
    var lastGeneration by remember { mutableLongStateOf(NO_CALIBRATION_GENERATION) }
    val currentGeneration by rememberUpdatedState(lastGeneration)
    fun startCurrentDraft() {
        lastGeneration = HaloEffectController.startCalibration(context, currentDraft(), token)
    }
    fun updatePreview() {
        val generation = lastGeneration
        if (generation == NO_CALIBRATION_GENERATION) return
        HaloEffectController.updateCalibration(context, currentDraft(), token, generation)
    }
    DisposableEffect(lifecycleOwner, token) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startCurrentDraft()
                Lifecycle.Event.ON_STOP -> {
                    val generation = lastGeneration
                    if (generation != NO_CALIBRATION_GENERATION) {
                        HaloEffectController.pauseCalibration(context, token, generation)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        startCurrentDraft()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (currentGeneration != NO_CALIBRATION_GENERATION) {
                HaloEffectController.stopCalibration(context, token, currentGeneration)
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding()
        ) {
            Spacer(Modifier.height(24.dp))
            LuminoteScreenHeader(stringResource(R.string.display_calibration), stringResource(R.string.back), onBackClick)
        }

        Column(Modifier.fillMaxWidth().luminoteSafeHorizontalPadding()) {
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.display_calibration_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            LuminoteSliderSetting(stringResource(R.string.edge_calibration), edge, "%.0f dp  (%+.0f px)".format(edge, edge * density), MIN_DISPLAY_CALIBRATION_DP..MAX_DISPLAY_CALIBRATION_DP, 47, { edge = sanitizeDisplayCalibration(it); updatePreview() })
            Spacer(Modifier.height(8.dp))
            CalibrationPixelControls(
                onDecrease = { edge = adjustCalibrationByPhysicalPx(edge, -1, density); updatePreview() },
                onIncrease = { edge = adjustCalibrationByPhysicalPx(edge, 1, density); updatePreview() }
            )
            Spacer(Modifier.height(16.dp))
            LuminoteSliderSetting(stringResource(R.string.corner_calibration), corner, "%.0f dp".format(corner), MIN_DISPLAY_CALIBRATION_DP..MAX_DISPLAY_CALIBRATION_DP, 47, { corner = sanitizeDisplayCalibration(it); updatePreview() })
            Spacer(Modifier.height(16.dp))
            LuminoteSliderSetting(stringResource(R.string.corner_shape), cornerShape, "%.0f%%".format(cornerShape * 100f), MIN_DISPLAY_CORNER_SHAPE..MAX_DISPLAY_CORNER_SHAPE, 100, { cornerShape = sanitizeDisplayCornerShape(it); updatePreview() })
            Spacer(Modifier.height(24.dp))
            Button({ edge = 0f; corner = 0f; cornerShape = 0.5f; updatePreview() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.reset)) }
            Spacer(Modifier.height(12.dp))
            Button({
                val generation = lastGeneration
                viewModel.applyDisplayCalibration(edge, corner, cornerShape)
                if (generation != NO_CALIBRATION_GENERATION) {
                    HaloEffectController.stopCalibration(context, token, generation)
                }
                onBackClick()
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
private fun CalibrationPixelControls(onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Button(onClick = onDecrease, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.calibration_decrease_one_px)) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(0.08f))
        Button(onClick = onIncrease, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.calibration_increase_one_px)) }
    }
}

/** The draft stays persisted in dp while each tap moves exactly one physical pixel. */
internal fun adjustCalibrationByPhysicalPx(currentDp: Float, deltaPx: Int, density: Float): Float {
    if (!density.isFinite() || density <= 0f) return sanitizeDisplayCalibration(currentDp)
    return sanitizeDisplayCalibration(currentDp + deltaPx / density)
}

private const val NO_CALIBRATION_GENERATION = -1L
