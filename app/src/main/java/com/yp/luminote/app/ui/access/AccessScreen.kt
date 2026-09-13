package com.yp.luminote.app.ui.access

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yp.luminote.app.effects.HaloAccessibilityService
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding

@Composable
fun AccessScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Text(
                    text = "Access",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Manage the permissions Luminote needs for alerts and edge personalization.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFAFAFB8)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding(),
        ) {
            AccessItem(
                title = "Display over other apps",
                description = "Lets Luminote draw the halo above the current app.",
                granted = overlayAllowed,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AccessItem(
                title = "Notification access",
                description = "Lets Luminote read active notifications and choose their color.",
                granted = notificationAccessAllowed,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AccessItem(
                title = "Lock screen halo",
                description = "Lets the halo appear after you turn on the lock screen. Enable Luminote lock screen halo in Accessibility.",
                granted = lockScreenAccessAllowed,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
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
            .background(Color(0xFF1B1B20))
            .border(1.dp, Color(0xFF303038), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAFAFB8)
            )
        }

        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = if (granted) "Allowed" else "Required",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (granted) Color(0xFF79D9A2) else Color(0xFFFFC870)
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
