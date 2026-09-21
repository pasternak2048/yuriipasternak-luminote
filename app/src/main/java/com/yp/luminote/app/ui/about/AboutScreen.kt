package com.yp.luminote.app.ui.about

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.update.UpdateChannel
import com.yp.luminote.app.update.UpdateInstaller
import com.yp.luminote.app.update.UpdateUiState
import com.yp.luminote.app.update.UpdateViewModel

@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onEasterEggClick: () -> Unit,
    updateViewModel: UpdateViewModel = viewModel()
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionInfo = remember(context) {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        "Version ${packageInfo.versionName ?: "Unknown"} (${packageInfo.longVersionCode})"
    }
    var versionTapCount by remember { mutableIntStateOf(0) }
    val updateChannel by updateViewModel.channel.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val updateNotificationsEnabled by
        updateViewModel.notificationsEnabled.collectAsState()
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
    val notificationsAllowed =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
                .background(Color.Black)
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
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Text(
                    text = "About",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Luminote",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Edge lighting for alerts and ambient style.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFBDBDBD)
            )

        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AboutBlock(title = "About Luminote") {
                Text(
                    text = "Luminote brings notification lighting and ambient edge personalization " +
                        "to modern displays. It follows the physical contour of the screen while " +
                        "keeping the experience fast and unobtrusive.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFBDBDBD)
                )
            }

            AboutBlock(title = "Created by") {
                Text(
                    text = "Yurii Pasternak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            AboutLink(
                title = "GitHub",
                subtitle = "github.com/pasternak2048",
                onClick = { uriHandler.openUri("https://github.com/pasternak2048") }
            )

            AboutLink(
                title = "Source code",
                subtitle = "yuriipasternak-luminote",
                onClick = {
                    uriHandler.openUri("https://github.com/pasternak2048/yuriipasternak-luminote")
                }
            )

            AboutBlock(
                title = "App version",
                modifier = Modifier.clickable {
                    versionTapCount += 1
                    if (versionTapCount >= EASTER_EGG_TAP_COUNT) {
                        versionTapCount = 0
                        onEasterEggClick()
                    }
                }
            ) {
                Text(
                    text = versionInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBDBDBD)
                )
            }

            UpdateBlock(
                channel = updateChannel,
                state = updateState,
                notificationsEnabled = updateNotificationsEnabled,
                notificationsAllowed = notificationsAllowed,
                onChannelSelected = updateViewModel::selectChannel,
                onCheckClick = updateViewModel::checkForUpdate,
                onDownloadClick = updateViewModel::download,
                onEnableNotificationsClick = {
                    updateViewModel.setNotificationsEnabled(true)
                    if (!notificationsAllowed) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                },
                onDisableNotificationsClick = {
                    updateViewModel.setNotificationsEnabled(false)
                },
                onInstallClick = { apk ->
                    if (UpdateInstaller.canInstallPackages(context)) {
                        UpdateInstaller.install(context, apk)
                    } else {
                        UpdateInstaller.openInstallPermission(context)
                    }
                }
            )
        }
    }
}

private const val EASTER_EGG_TAP_COUNT = 7

@Composable
private fun AboutBlock(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF101010))
            .border(1.dp, Color(0xFF3D3D3D), RoundedCornerShape(28.dp))
            .padding(20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF101010))
            .border(1.dp, Color(0xFF3D3D3D), RoundedCornerShape(24.dp))
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
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBDBDBD)
            )
        }
        Text(
            text = "↗",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
    }
}

@Composable
private fun UpdateBlock(
    channel: UpdateChannel,
    state: UpdateUiState,
    notificationsEnabled: Boolean,
    notificationsAllowed: Boolean,
    onChannelSelected: (UpdateChannel) -> Unit,
    onCheckClick: () -> Unit,
    onDownloadClick: (com.yp.luminote.app.update.UpdateInfo) -> Unit,
    onEnableNotificationsClick: () -> Unit,
    onDisableNotificationsClick: () -> Unit,
    onInstallClick: (java.io.File) -> Unit
) {
    AboutBlock(title = "Updates") {
        Text(
            text = "Channel",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBDBDBD)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UpdateChannel.entries.forEach { option ->
                FilterChip(
                    selected = channel == option,
                    onClick = {
                        onChannelSelected(option)
                    },
                    label = {
                        Text(option.label)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (state) {
            UpdateUiState.Checking ->
                UpdateStatus("Checking GitHub Releases…")

            UpdateUiState.UpToDate ->
                UpdateStatus("No newer ${channel.label} update is available.")

            is UpdateUiState.Available -> {
                UpdateStatus(
                    "${state.update.versionName} is available."
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onDownloadClick(state.update)
                    }
                ) {
                    Text("Download update")
                }
            }

            is UpdateUiState.Downloading ->
                UpdateStatus("Downloading ${state.update.versionName}…")

            is UpdateUiState.ReadyToInstall -> {
                UpdateStatus("${state.update.versionName} is ready to install.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onInstallClick(state.apk)
                    }
                ) {
                    Text("Install update")
                }
            }

            is UpdateUiState.Error ->
                UpdateStatus(state.message)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onCheckClick) {
            Text("Check now")
        }

        if (notificationsEnabled && notificationsAllowed) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onDisableNotificationsClick) {
                Text("Disable update notifications")
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onEnableNotificationsClick) {
                Text(
                    if (notificationsAllowed) {
                        "Enable update notifications"
                    } else {
                        "Allow update notifications"
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdateStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFBDBDBD)
    )
}
