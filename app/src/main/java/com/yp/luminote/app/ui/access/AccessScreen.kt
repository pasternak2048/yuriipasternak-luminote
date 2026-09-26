package com.yp.luminote.app.ui.access

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yp.luminote.app.effects.HaloAccessibilityService
import com.yp.luminote.app.R
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.theme.LuminoteDarkBackground
import com.yp.luminote.app.ui.theme.LuminoteDarkOnSurface
import com.yp.luminote.app.ui.theme.LuminoteDarkOutline
import com.yp.luminote.app.ui.theme.LuminoteDarkSecondaryText
import com.yp.luminote.app.ui.theme.LuminoteDarkSurface
import com.yp.luminote.app.ui.theme.LuminoteSuccess
import com.yp.luminote.app.ui.theme.LuminoteWarning
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

@Composable
fun AccessScreen(
    onBackClick: () -> Unit,
    viewModel: LuminoteSettingsViewModel
) {
    val context = LocalContext.current
    val backDescription = stringResource(R.string.back)
    var refreshKey by remember { mutableIntStateOf(0) }
    var showLockScreenDisclosure by remember { mutableStateOf(false) }

    val settings by
    viewModel.settings.collectAsState()

    val activity = remember(context) { context.findActivity() }
    activity?.let {
        DisposableEffect(activity) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshKey++
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    // Read the live system state again every time the user returns from Android Settings.
    val overlayAllowed = remember(refreshKey) { Settings.canDrawOverlays(context) }
    val notificationAccessAllowed = remember(refreshKey) { hasNotificationAccess(context) }
    val lockScreenAccessAllowed = remember(refreshKey) { hasLockScreenAccess(context) }

    val haloReady =
        overlayAllowed &&
                notificationAccessAllowed

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminoteDarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding()
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "‹",
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = backDescription }
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = LuminoteDarkOnSurface
                )
                Text(
                    text = stringResource(R.string.access),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LuminoteDarkOnSurface
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.access_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = LuminoteDarkSecondaryText
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding(),
        ) {
            AccessItem(
                title = stringResource(R.string.display_over_other_apps),
                description = stringResource(R.string.display_over_other_apps_description),
                granted = overlayAllowed,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AccessItem(
                title = stringResource(R.string.notification_access),
                description = stringResource(R.string.notification_access_description),
                granted = notificationAccessAllowed,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AccessItem(
                title = stringResource(R.string.lock_screen_halo),
                description = stringResource(R.string.lock_screen_halo_description),
                granted = lockScreenAccessAllowed,
                onClick = {
                    when (lockScreenAccessibilityAccessAction(lockScreenAccessAllowed)) {
                        LockScreenAccessibilityAccessAction.OPEN_SETTINGS -> {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }

                        LockScreenAccessibilityAccessAction.SHOW_DISCLOSURE -> {
                            showLockScreenDisclosure = true
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            AccessStatusCard(
                haloReady = haloReady,
                onTestHaloClick = {
                    HaloOverlayService.start(
                        context,
                        HaloOverlayService.createPreviewIntent(
                            context,
                            settings
                        )
                    )
                }
            )
        }
    }

    if (showLockScreenDisclosure) {
        LockScreenAccessibilityDisclosure(
            onDismiss = { showLockScreenDisclosure = false },
            onContinue = {
                showLockScreenDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
    }
}

@Composable
private fun LockScreenAccessibilityDisclosure(
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.lock_screen_halo_disclosure_title))
        },
        text = {
            Text(stringResource(R.string.lock_screen_halo_disclosure_message))
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lock_screen_halo_disclosure_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.lock_screen_halo_disclosure_continue))
            }
        }
    )
}

@Composable
private fun AccessStatusCard(
    haloReady: Boolean,
    onTestHaloClick: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(LuminoteDarkSurface)
                .border(1.dp, LuminoteDarkOutline, RoundedCornerShape(24.dp))
                .padding(20.dp)
    ) {
        Text(
            text =
                if (haloReady) {
                    stringResource(R.string.halo_ready)
                } else {
                    stringResource(R.string.halo_needs_access)
                },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (haloReady) LuminoteSuccess else LuminoteWarning
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text =
                if (haloReady) {
                    stringResource(R.string.halo_ready_description)
                } else {
                    stringResource(R.string.halo_needs_access_description)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = LuminoteDarkSecondaryText
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onTestHaloClick,
            enabled = haloReady,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = LuminoteDarkOnSurface,
                    contentColor = LuminoteDarkBackground,
                    disabledContainerColor = LuminoteDarkOutline,
                    disabledContentColor = LuminoteDarkSecondaryText
                )
        ) {
            Text(stringResource(R.string.test_halo))
        }
    }
}

@Composable
private fun AccessItem(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LuminoteDarkSurface)
            .border(1.dp, LuminoteDarkOutline, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LuminoteDarkOnSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = LuminoteDarkSecondaryText
            )
        }

        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(if (granted) R.string.allowed else R.string.required),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (granted) LuminoteSuccess else LuminoteWarning
        )
    }
}

private fun hasNotificationAccess(context: android.content.Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ).orEmpty()

    return enabledListeners
        .split(':')
        .mapNotNull(ComponentName::unflattenFromString)
        .any { it.packageName == context.packageName }
}

private fun hasLockScreenAccess(context: android.content.Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false

    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { service ->
            val info = service.resolveInfo?.serviceInfo ?: return@any false
            info.packageName == context.packageName &&
                info.name == HaloAccessibilityService::class.java.name
        }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
