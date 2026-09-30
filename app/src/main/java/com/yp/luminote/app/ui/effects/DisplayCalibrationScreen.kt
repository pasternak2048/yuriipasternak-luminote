package com.yp.luminote.app.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.MAX_DISPLAY_CALIBRATION_DP
import com.yp.luminote.app.data.settings.MIN_DISPLAY_CALIBRATION_DP
import com.yp.luminote.app.data.settings.MIN_DISPLAY_CORNER_SHAPE
import com.yp.luminote.app.data.settings.MAX_DISPLAY_CORNER_SHAPE
import com.yp.luminote.app.data.settings.sanitizeDisplayCalibration
import com.yp.luminote.app.data.settings.sanitizeDisplayCornerShape
import com.yp.luminote.app.effects.CalibrationPreviewSession
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSliderSetting
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun DisplayCalibrationScreen(onBackClick: () -> Unit, viewModel: LuminoteSettingsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val token = remember { CalibrationPreviewSession.start() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var edge by remember(settings.displayEdgeCalibrationDp) { mutableFloatStateOf(sanitizeDisplayCalibration(settings.displayEdgeCalibrationDp)) }
    var corner by remember(settings.displayCornerCalibrationDp) { mutableFloatStateOf(sanitizeDisplayCalibration(settings.displayCornerCalibrationDp)) }
    var cornerShape by remember(settings.displayCornerShape) { mutableFloatStateOf(sanitizeDisplayCornerShape(settings.displayCornerShape)) }
    fun preview(start: Boolean) = HaloOverlayService.start(
        context,
        HaloOverlayService.createCalibrationIntent(context, settings.copy(displayEdgeCalibrationDp = edge, displayCornerCalibrationDp = corner, displayCornerShape = cornerShape), token, start)
    )
    DisposableEffect(token) {
        preview(true)
        onDispose { HaloOverlayService.start(context, HaloOverlayService.createStopCalibrationIntent(context, token)) }
    }
    DisposableEffect(lifecycleOwner, token) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                HaloOverlayService.start(context, HaloOverlayService.createStopCalibrationIntent(context, token))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            LuminoteSliderSetting(stringResource(R.string.edge_calibration), edge, "%.0f dp".format(edge), MIN_DISPLAY_CALIBRATION_DP..MAX_DISPLAY_CALIBRATION_DP, 47, { edge = sanitizeDisplayCalibration(it); preview(false) })
            Spacer(Modifier.height(16.dp))
            LuminoteSliderSetting(stringResource(R.string.corner_calibration), corner, "%.0f dp".format(corner), MIN_DISPLAY_CALIBRATION_DP..MAX_DISPLAY_CALIBRATION_DP, 47, { corner = sanitizeDisplayCalibration(it); preview(false) })
            Spacer(Modifier.height(16.dp))
            LuminoteSliderSetting(stringResource(R.string.corner_shape), cornerShape, "%.0f%%".format(cornerShape * 100f), MIN_DISPLAY_CORNER_SHAPE..MAX_DISPLAY_CORNER_SHAPE, 100, { cornerShape = sanitizeDisplayCornerShape(it); preview(false) })
            Spacer(Modifier.height(24.dp))
            Button({ edge = 0f; corner = 0f; cornerShape = 0.5f; preview(false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.reset)) }
            Spacer(Modifier.height(12.dp))
            Button({ viewModel.applyDisplayCalibration(edge, corner, cornerShape); HaloOverlayService.start(context, HaloOverlayService.createStopCalibrationIntent(context, token)); onBackClick() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
        }
    }
}
