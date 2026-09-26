package com.yp.luminote.app.ui.about

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yp.luminote.app.ui.components.LuminoteExternalLinkIcon
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.R
import com.yp.luminote.app.update.UpdateChannel
import com.yp.luminote.app.update.UpdateInstaller
import com.yp.luminote.app.update.UpdateUiState
import com.yp.luminote.app.update.UpdateViewModel

@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onEasterEggClick: () -> Unit,
    showUpdatesOnly: Boolean = false,
    updateViewModel: UpdateViewModel = viewModel()
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    }
    val versionInfo = stringResource(
        R.string.version_info,
        packageInfo.versionName ?: stringResource(R.string.unknown),
        packageInfo.longVersionCode
    )
    val backDescription = stringResource(R.string.back)
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
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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

            LuminoteScreenHeader(
                title = stringResource(if (showUpdatesOnly) R.string.updates else R.string.about),
                backContentDescription = backDescription,
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(24.dp))

        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showUpdatesOnly) {
                UpdateBlock(
                    showTitle = false,
                    channel = updateChannel,
                    state = updateState,
                    notificationsEnabled = updateNotificationsEnabled,
                    notificationsAllowed = notificationsAllowed,
                    onChannelSelected = updateViewModel::selectChannel,
                    onCheckClick = updateViewModel::checkForUpdate,
                    onDownloadClick = updateViewModel::download,
                    onEnableNotificationsClick = {
                        updateViewModel.setNotificationsEnabled(true)
                        if (!notificationsAllowed) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onDisableNotificationsClick = { updateViewModel.setNotificationsEnabled(false) },
                    onInstallClick = { apk ->
                        if (UpdateInstaller.canInstallPackages(context)) UpdateInstaller.install(context, apk)
                        else UpdateInstaller.openInstallPermission(context)
                    }
                )
            } else {
            AboutBlock(title = stringResource(R.string.about_luminote)) {
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AboutBlock(title = stringResource(R.string.created_by)) {
                Text(
                    text = stringResource(R.string.author_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            AboutLink(
                title = stringResource(R.string.github),
                subtitle = "github.com/pasternak2048",
                onClick = { uriHandler.openUri("https://github.com/pasternak2048") }
            )

            AboutLink(
                title = stringResource(R.string.source_code),
                subtitle = "yuriipasternak-luminote",
                onClick = {
                    uriHandler.openUri("https://github.com/pasternak2048/yuriipasternak-luminote")
                }
            )

            AboutBlock(
                title = stringResource(R.string.app_version),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
}

private const val EASTER_EGG_TAP_COUNT = 7

@Composable
private fun AboutBlock(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    LuminoteSettingsCard(modifier = modifier) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    LuminoteSettingsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LuminoteExternalLinkIcon(tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun UpdateBlock(
    showTitle: Boolean = true,
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
    AboutBlock(title = if (showTitle) stringResource(R.string.updates) else null) {
        Text(
            text = stringResource(R.string.update_channel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            UpdateChannel.entries.forEach { option ->
                LuminoteSelectionRow(
                    title = stringResource(option.labelRes),
                    selected = channel == option,
                    onClick = {
                        onChannelSelected(option)
                    },
                    control = LuminoteSelectionControl.Radio
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (state) {
            UpdateUiState.Checking ->
                UpdateStatus(stringResource(R.string.checking_updates))

            UpdateUiState.UpToDate ->
                UpdateStatus(stringResource(R.string.no_update_available, stringResource(channel.labelRes)))

            is UpdateUiState.Available -> {
                UpdateStatus(
                    stringResource(R.string.update_available, state.update.versionName)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    onClick = {
                        onDownloadClick(state.update)
                    }
                ) {
                    Text(stringResource(R.string.download_update))
                }
            }

            is UpdateUiState.Downloading ->
                UpdateStatus(stringResource(R.string.downloading_update, state.update.versionName))

            is UpdateUiState.ReadyToInstall -> {
                UpdateStatus(stringResource(R.string.update_ready_to_install, state.update.versionName))
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    onClick = {
                        onInstallClick(state.apk)
                    }
                ) {
                    Text(stringResource(R.string.install_update))
                }
            }

            UpdateUiState.CheckError -> UpdateStatus(stringResource(R.string.update_check_failed))
            UpdateUiState.DownloadError -> UpdateStatus(stringResource(R.string.update_download_failed))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            onClick = onCheckClick
        ) {
            Text(stringResource(R.string.check_now))
        }

        if (notificationsEnabled && notificationsAllowed) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                onClick = onDisableNotificationsClick
            ) {
                Text(stringResource(R.string.disable_update_notifications))
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                onClick = onEnableNotificationsClick
            ) {
                Text(
                    if (notificationsAllowed) {
                        stringResource(R.string.enable_update_notifications)
                    } else {
                        stringResource(R.string.allow_update_notifications)
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
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
