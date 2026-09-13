package com.yp.luminote.app.ui.easteregg

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.effects.HaloOverlayService

@Composable
fun EasterEggScreen(
    ambientSettings: LuminoteSettings,
    onClose: () -> Unit
) {
    HideSystemBarsForEasterEgg()
    SuspendAmbientHaloForEasterEgg(ambientSettings)
    val mainTextAlpha = remember { Animatable(0f) }
    val secondaryTextAlpha = remember { Animatable(0f) }
    val atmosphereAlpha = remember { Animatable(0f) }
    val atmosphereDissolve = remember { Animatable(0f) }
    var sequenceFinished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(HALO_START_DELAY_MS)
        coroutineScope {
            launch {
                atmosphereAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(HALO_FADE_IN_MS, easing = LinearOutSlowInEasing)
                )
            }
            delay(MAIN_TEXT_START_AFTER_HALO_MS)
            launch {
                mainTextAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(MAIN_TEXT_FADE_IN_MS, easing = LinearOutSlowInEasing)
                )
            }
            delay(SECOND_PHASE_START_DELAY_MS)
            launch {
                secondaryTextAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(SECONDARY_TEXT_FADE_IN_MS, easing = LinearOutSlowInEasing)
                )
            }
            launch {
                atmosphereDissolve.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(ATMOSPHERE_DISSOLVE_MS, easing = LinearEasing)
                )
            }
            delay(MAIN_TEXT_FADE_OUT_START_DELAY_MS)
            launch {
                mainTextAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(MAIN_TEXT_FADE_OUT_MS, easing = LinearOutSlowInEasing)
                )
            }
        }
        delay(CLOSE_INTERACTION_DELAY_MS)
        sequenceFinished = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NIGHT_BACKGROUND)
            .pointerInput(sequenceFinished) {
                if (sequenceFinished) {
                    detectTapGestures(onTap = { onClose() })
                }
            }
    ) {
        EasterEggAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .alpha(atmosphereAlpha.value),
            dissolveProgress = atmosphereDissolve.value
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Some lights never really fade.",
                modifier = Modifier.alpha(mainTextAlpha.value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = MAIN_TEXT_COLOR,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Just kidding.",
                modifier = Modifier.alpha(secondaryTextAlpha.value),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = SECONDARY_TEXT_COLOR,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SuspendAmbientHaloForEasterEgg(ambientSettings: LuminoteSettings) {
    val context = LocalContext.current

    DisposableEffect(context, ambientSettings.ambientEnabled) {
        val restoreAmbientHalo = ambientSettings.ambientEnabled
        if (restoreAmbientHalo) {
            HaloOverlayService.start(context, HaloOverlayService.createStopAmbientIntent(context))
        }

        onDispose {
            if (restoreAmbientHalo) {
                HaloOverlayService.start(context, HaloOverlayService.createAmbientIntent(context, ambientSettings))
            }
        }
    }
}

@Composable
private fun HideSystemBarsForEasterEgg() {
    val view = LocalView.current

    DisposableEffect(view) {
        val controller = view.context.findActivity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun EasterEggAtmosphere(
    modifier: Modifier = Modifier,
    dissolveProgress: Float
) {
    AtmosphericHaloBackdrop(
        modifier = modifier,
        dissolveProgress = dissolveProgress
    )
}

@Composable
private fun AtmosphericHaloBackdrop(
    modifier: Modifier = Modifier,
    dissolveProgress: Float
) {
    Canvas(modifier = modifier) {
        val radius = maxOf(size.width, size.height) * 0.95f
        drawRect(Color(0xFF0A1634))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x87394B83),
                    Color(0x4D182B55),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.08f),
                radius = radius
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x669E3D4F),
                    Color(0x33765FA8),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.92f),
                radius = radius
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x3DFFC86A),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.08f, size.height * 0.62f),
                radius = radius * 0.72f
            )
        )
        if (dissolveProgress > 0f) {
            val slowedProgress = dissolveProgress * dissolveProgress
            fun drawDissolveWave(center: Offset, progress: Float, scale: Float) {
                if (progress <= 0f) return
                val dissolveRadius = maxOf(size.width, size.height) * (0.05f + progress * scale)
                val coreAlpha = 0.96f * progress
                val edgeAlpha = 0.72f * progress
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = coreAlpha),
                        0.58f to Color.Black.copy(alpha = edgeAlpha),
                        1f to Color.Transparent,
                        center = center,
                        radius = dissolveRadius
                    ),
                    center = center,
                    radius = dissolveRadius
                )
            }

            drawDissolveWave(
                center = Offset(size.width * 0.5f, size.height * 0.56f),
                progress = slowedProgress,
                scale = 1.5f
            )
            drawDissolveWave(
                center = Offset(size.width * 0.28f, size.height * 0.69f),
                progress = ((slowedProgress - 0.14f) / 0.86f).coerceIn(0f, 1f),
                scale = 1.25f
            )
            drawDissolveWave(
                center = Offset(size.width * 0.73f, size.height * 0.34f),
                progress = ((slowedProgress - 0.27f) / 0.73f).coerceIn(0f, 1f),
                scale = 1.18f
            )
        }
    }
}

private const val HALO_START_DELAY_MS = 1_000L
private const val HALO_FADE_IN_MS = 8_000
private const val MAIN_TEXT_START_AFTER_HALO_MS = 3_000L
private const val MAIN_TEXT_FADE_IN_MS = 6_000
private const val SECOND_PHASE_START_DELAY_MS = 5_000L
private const val SECONDARY_TEXT_FADE_IN_MS = 9_000
private const val ATMOSPHERE_DISSOLVE_MS = 14_000
private const val MAIN_TEXT_FADE_OUT_START_DELAY_MS = 7_000L
private const val MAIN_TEXT_FADE_OUT_MS = 7_000
private const val CLOSE_INTERACTION_DELAY_MS = 7_000L

private val NIGHT_BACKGROUND = androidx.compose.ui.graphics.Color.Black
private val MAIN_TEXT_COLOR = androidx.compose.ui.graphics.Color(0xFFE7E7F0)
private val SECONDARY_TEXT_COLOR = androidx.compose.ui.graphics.Color(0xFF8D8B9B)

private val WARM_GOLD = 0xFFFFC86A.toInt()
private val SOFT_PEACH = 0xFFF29A73.toInt()
private val MUTED_PINK_PURPLE = 0xFFC77BCB.toInt()
private val DEEP_SEA_RED = 0xFF7E2C42.toInt()
private val SEA_CRIMSON = 0xFF9E3D4F.toInt()
private val VIOLET = 0xFF765FA8.toInt()
private val TWILIGHT_BLUE = 0xFF394B83.toInt()
private val DEEP_NIGHT_BLUE = 0xFF182B55.toInt()

private val EASTER_EGG_PALETTE = intArrayOf(
    DEEP_NIGHT_BLUE,
    TWILIGHT_BLUE,
    VIOLET,
    MUTED_PINK_PURPLE,
    DEEP_SEA_RED,
    SEA_CRIMSON,
    SOFT_PEACH,
    WARM_GOLD
)

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
