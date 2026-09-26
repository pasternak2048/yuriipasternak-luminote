package com.yp.luminote.app.ui.apps

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yp.luminote.app.data.apps.InstalledApp
import com.yp.luminote.app.R
import com.yp.luminote.app.data.apps.InstalledAppsRepository
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.NotificationSource
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.ui.components.LuminoteExpandIcon
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

@Composable
fun AppsScreen(
    onBackClick: () -> Unit,
    windowSizeClass: LuminoteWindowSizeClass,
    viewModel: LuminoteSettingsViewModel
) {
    val context = LocalContext.current
    val backDescription = stringResource(R.string.back)

    val appsRepository =
        remember {
            InstalledAppsRepository(context)
        }

    val activity =
        remember(context) {
            context.findActivity()
        }

    var refreshKey by
    remember {
        mutableIntStateOf(0)
    }

    activity?.let { currentActivity ->
        DisposableEffect(currentActivity) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        refreshKey++
                    }
                }

            currentActivity.lifecycle.addObserver(observer)

            onDispose {
                currentActivity.lifecycle.removeObserver(observer)
            }
        }
    }

    val settings by
    viewModel.settings.collectAsState()

    val installedApps by produceState<List<InstalledApp>>(
        initialValue = emptyList(),
        key1 = appsRepository,
        key2 = refreshKey
    ) {
        value =
            withContext(Dispatchers.IO) {
                appsRepository.refreshInstalledApps()
            }
    }

    var sourceExpanded by
    rememberSaveable {
        mutableStateOf(false)
    }

    var searchQuery by
    remember {
        mutableStateOf("")
    }

    val filteredApps =
        remember(
            installedApps,
            searchQuery
        ) {
            val query =
                searchQuery.trim()

            if (query.isEmpty()) {
                installedApps
            } else {
                installedApps.filter { app ->
                    app.name.contains(
                        query,
                        ignoreCase = true
                    ) ||
                            app.packageName.contains(
                                query,
                                ignoreCase = true
                            )
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
    ) {

        /*
         * =========================================================
         * HEADER
         * =========================================================
         */

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .luminoteSafeHorizontalPadding()
        ) {

            Spacer(
                modifier =
                    Modifier.height(32.dp)
            )

            LuminoteScreenHeader(
                title = stringResource(R.string.apps),
                backContentDescription = backDescription,
                onBackClick = onBackClick
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Text(
                text =
                    stringResource(R.string.apps_intro),
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        when (windowSizeClass) {

            LuminoteWindowSizeClass.COMPACT -> {

                CompactAppsContent(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    settings =
                        settings,
                    installedApps =
                        filteredApps,
                    searchQuery =
                        searchQuery,
                    sourceExpanded =
                        sourceExpanded,
                    onSourceToggle = {
                        sourceExpanded =
                            !sourceExpanded
                    },
                    onSourceSelected = { source ->

                        viewModel.setNotificationSource(
                            source
                        )

                        sourceExpanded = false
                    },
                    onSearchQueryChange = {
                        searchQuery = it
                    },
                    onAppClick = { app ->

                        viewModel.setAppSelected(
                            packageName =
                                app.packageName,
                            selected =
                                app.packageName !in
                                        settings.selectedApps
                        )
                    }
                )
            }

            LuminoteWindowSizeClass.MEDIUM,
            LuminoteWindowSizeClass.EXPANDED -> {

                WideAppsContent(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    settings =
                        settings,
                    installedApps =
                        filteredApps,
                    searchQuery =
                        searchQuery,
                    sourceExpanded =
                        sourceExpanded,
                    onSourceToggle = {
                        sourceExpanded =
                            !sourceExpanded
                    },
                    onSourceSelected = { source ->

                        viewModel.setNotificationSource(
                            source
                        )

                        sourceExpanded = false
                    },
                    onSearchQueryChange = {
                        searchQuery = it
                    },
                    onAppClick = { app ->

                        viewModel.setAppSelected(
                            packageName =
                                app.packageName,
                            selected =
                                app.packageName !in
                                        settings.selectedApps
                        )
                    }
                )
            }
        }
    }
}

/*
 * ================================================================
 * COMPACT
 * ================================================================
 */

@Composable
private fun CompactAppsContent(
    modifier: Modifier,
    settings: LuminoteSettings,
    installedApps: List<InstalledApp>,
    searchQuery: String,
    sourceExpanded: Boolean,
    onSourceToggle: () -> Unit,
    onSourceSelected: (NotificationSource) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAppClick: (InstalledApp) -> Unit
) {
    Column(
        modifier =
            modifier
                .luminoteSafeHorizontalPadding()
    ) {

        NotificationSourceGroup(
            settings =
                settings,
            expanded =
                sourceExpanded,
            onToggle =
                onSourceToggle,
            onSourceSelected =
                onSourceSelected
        )

        if (
            settings.notificationSource ==
            NotificationSource.SELECTED_APPS
        ) {

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )

            Text(
                text = stringResource(R.string.applications),
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.SemiBold,
                color =
                    MaterialTheme
                        .colorScheme
                        .onBackground
            )

            Text(
                text =
                    androidx.compose.ui.res.pluralStringResource(R.plurals.selected_apps, settings.selectedApps.size, settings.selectedApps.size),
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onBackground
                        .copy(alpha = 0.65f)
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            AppSearchField(
                query =
                    searchQuery,
                onQueryChange =
                    onSearchQueryChange
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            AppsListContent(
                modifier =
                    Modifier.weight(1f),
                installedApps =
                    installedApps,
                selectedApps =
                    settings.selectedApps,
                onAppClick =
                    onAppClick
            )

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )
        }
    }
}

/*
 * ================================================================
 * MEDIUM / EXPANDED
 * ================================================================
 */

@Composable
private fun WideAppsContent(
    modifier: Modifier,
    settings: LuminoteSettings,
    installedApps: List<InstalledApp>,
    searchQuery: String,
    sourceExpanded: Boolean,
    onSourceToggle: () -> Unit,
    onSourceSelected: (NotificationSource) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAppClick: (InstalledApp) -> Unit
) {
    Row(
        modifier =
            modifier
                .luminoteSafeHorizontalPadding()
                .padding(
                    bottom = 24.dp
                ),
        horizontalArrangement =
            Arrangement.spacedBy(24.dp),
        verticalAlignment =
            Alignment.Top
    ) {

        /*
         * --------------------------------------------------------
         * LEFT — SETTINGS
         * --------------------------------------------------------
         */

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
        ) {

            NotificationSourceGroup(
                settings =
                    settings,
                expanded =
                    sourceExpanded,
                onToggle =
                    onSourceToggle,
                onSourceSelected =
                    onSourceSelected
            )
        }

        /*
         * --------------------------------------------------------
         * RIGHT — APPLICATIONS
         * --------------------------------------------------------
         */

        if (
            settings.notificationSource ==
            NotificationSource.SELECTED_APPS
        ) {

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
            ) {

                Text(
                    text = stringResource(R.string.applications),
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground
                )

                Text(
                    text =
                        androidx.compose.ui.res.pluralStringResource(R.plurals.selected_apps, settings.selectedApps.size, settings.selectedApps.size),
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground
                            .copy(alpha = 0.65f)
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                AppSearchField(
                    query =
                        searchQuery,
                    onQueryChange =
                        onSearchQueryChange
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                AppsListContent(
                    modifier =
                        Modifier.weight(1f),
                    installedApps =
                        installedApps,
                    selectedApps =
                        settings.selectedApps,
                    onAppClick =
                        onAppClick
                )
            }

        } else {

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        stringResource(R.string.all_apps_can_trigger),
                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground
                            .copy(
                                alpha = 0.55f
                            )
                )
            }
        }
    }
}

/*
 * ================================================================
 * SEARCH
 * ================================================================
 */

@Composable
private fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value =
            query,
        onValueChange =
            onQueryChange,
        modifier =
            Modifier.fillMaxWidth(),
        singleLine =
            true,
        placeholder = {
            Text(
                text =
                    stringResource(R.string.search_apps),
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurface
                        .copy(
                            alpha = 0.45f
                        )
            )
        },
        shape =
            RoundedCornerShape(18.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor =
                    MaterialTheme.colorScheme.surface,
                unfocusedContainerColor =
                    MaterialTheme.colorScheme.surface,
                disabledContainerColor =
                    MaterialTheme.colorScheme.surface,
                focusedTextColor =
                    MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor =
                    MaterialTheme.colorScheme.onSurface,
                focusedBorderColor =
                    MaterialTheme.colorScheme.primary,
                unfocusedBorderColor =
                    MaterialTheme.colorScheme.outline,
                cursorColor =
                    MaterialTheme.colorScheme.primary
            )
    )
}

/*
 * ================================================================
 * NOTIFICATION SOURCE PICKER
 * ================================================================
 */

@Composable
private fun NotificationSourceGroup(
    settings: LuminoteSettings,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSourceSelected: (NotificationSource) -> Unit
) {
    val sourceLabel = stringResource(R.string.alerts_from)
    val sourceStateLabel = stringResource(
        if (expanded) R.string.expanded_state else R.string.collapsed_state
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "notification source chevron"
    )
    LuminoteSettingsCard(
        modifier =
            Modifier
                .fillMaxWidth(),
        borderColor = MaterialTheme.colorScheme.outline
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                )
        ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = sourceLabel
                        stateDescription = sourceStateLabel
                    }
                    .clickable(
                        onClick =
                            onToggle
                    ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        stringResource(R.string.alerts_from),
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface
                )

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text =
                        when (
                            settings.notificationSource
                        ) {

                            NotificationSource.ALL_APPS ->
                                stringResource(R.string.all_apps)

                            NotificationSource.SELECTED_APPS ->
                                stringResource(R.string.selected_apps_label)
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            LuminoteExpandIcon(
                expanded = expanded,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            SourceOption(
                title =
                    stringResource(R.string.all_apps),
                description =
                    stringResource(R.string.notifications_from_every_app),
                selected =
                    settings.notificationSource ==
                            NotificationSource.ALL_APPS,
                onClick = {
                    onSourceSelected(
                        NotificationSource.ALL_APPS
                    )
                }
            )

            Spacer(
                modifier =
                    Modifier.height(4.dp)
            )

            SourceOption(
                title =
                    stringResource(R.string.selected_apps_label),
                description =
                    stringResource(R.string.choose_apps_individually),
                selected =
                    settings.notificationSource ==
                            NotificationSource.SELECTED_APPS,
                onClick = {
                    onSourceSelected(
                        NotificationSource.SELECTED_APPS
                    )
                }
            )
            }
        }
        }
    }
}

/*
 * ================================================================
 * SOURCE OPTION
 * ================================================================
 */

@Composable
private fun SourceOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val selectedBackground =
        MaterialTheme
            .colorScheme
            .surfaceVariant

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(16.dp)
                )
                .background(
                    if (selected) {
                        selectedBackground
                    } else {
                        Color.Transparent
                    }
                )
                .clickable(
                    onClick =
                        onClick
                )
                .padding(
                    horizontal = 12.dp,
                    vertical = 12.dp
                ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        SelectionIndicator(
            selected =
                selected
        )

        Spacer(
            modifier =
                Modifier.width(16.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text =
                    title,
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                fontWeight =
                    if (selected) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Medium
                    },
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurface
            )

            Spacer(
                modifier =
                    Modifier.height(2.dp)
            )

            Text(
                text =
                    description,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

/*
 * ================================================================
 * APPLICATIONS LIST CONTENT
 * ================================================================
 */

@Composable
private fun AppsListContent(
    modifier: Modifier,
    installedApps: List<InstalledApp>,
    selectedApps: Set<String>,
    onAppClick: (InstalledApp) -> Unit
) {
    if (installedApps.isEmpty()) {

        Box(
            modifier =
                modifier
                    .fillMaxWidth(),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text =
                    stringResource(R.string.no_apps_found),
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                color =
                    MaterialTheme
                        .colorScheme
                        .onBackground
                        .copy(
                            alpha = 0.5f
                        )
            )
        }

    } else {

        AppsList(
            modifier =
                modifier,
            installedApps =
                installedApps,
            selectedApps =
                selectedApps,
            onAppClick =
                onAppClick
        )
    }
}

/*
 * ================================================================
 * APPLICATIONS LIST
 * ================================================================
 */

@Composable
private fun AppsList(
    modifier: Modifier,
    installedApps: List<InstalledApp>,
    selectedApps: Set<String>,
    onAppClick: (InstalledApp) -> Unit
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(24.dp)
                )
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(24.dp)
                ),
        contentPadding =
            PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp
            )
    ) {

        items(
            items =
                installedApps,
            key =
                { it.packageName }
        ) { app ->

            AppItemRow(
                app =
                    app,
                selected =
                    app.packageName in
                            selectedApps,
                onClick = {
                    onAppClick(app)
                }
            )
        }
    }
}

/*
 * ================================================================
 * APP ROW
 * ================================================================
 */

@Composable
private fun AppItemRow(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context =
        LocalContext.current

    val iconSizePx =
        with(LocalDensity.current) {
            32.dp.roundToPx()
        }

    val bitmap =
        remember(
            app.packageName,
            iconSizePx
        ) {
            AppIconBitmapCache.get(
                packageName =
                    app.packageName,
                sizePx =
                    iconSizePx
            ) ?: run {

                val drawable =
                    runCatching {
                        context
                            .packageManager
                            .getApplicationIcon(
                                app.packageName
                            )
                    }.getOrElse {
                        context
                            .packageManager
                            .defaultActivityIcon
                    }

                drawableToBitmap(
                    drawable =
                        drawable,
                    sizePx =
                        iconSizePx
                ).also { generatedBitmap ->

                    AppIconBitmapCache.put(
                        packageName =
                            app.packageName,
                        sizePx =
                            iconSizePx,
                        bitmap =
                            generatedBitmap
                    )
                }
            }
        }

    Column {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(
                        onClick =
                            onClick
                    ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Image(
                bitmap =
                    bitmap.asImageBitmap(),
                contentDescription =
                    app.name,
                modifier =
                    Modifier.size(32.dp)
            )

            Spacer(
                modifier =
                    Modifier.width(16.dp)
            )

            Text(
                text =
                    app.name,
                modifier =
                    Modifier.weight(1f),
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                fontWeight =
                    FontWeight.Medium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurface
            )

            SelectionIndicator(
                selected =
                    selected
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .onSurface
                            .copy(
                                alpha = 0.10f
                            )
                    )
        )
    }
}

/*
 * ================================================================
 * SELECTION INDICATOR
 * ================================================================
 */

@Composable
private fun SelectionIndicator(
    selected: Boolean
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier =
            Modifier.size(22.dp)
    ) {

        drawCircle(
            color =
                if (selected) {
                    selectedColor
                } else {
                    unselectedColor
                },
            style =
                Stroke(
                    width = 2.dp.toPx()
                )
        )

        if (selected) {

            drawCircle(
                color =
                    selectedColor,
                radius =
                    size.minDimension * 0.22f
            )
        }
    }
}

/*
 * ================================================================
 * ICON CACHE
 * ================================================================
 */

private object AppIconBitmapCache {

    private val cache =
        object : LruCache<String, Bitmap>(
            4 * 1024 * 1024
        ) {

            override fun sizeOf(
                key: String,
                value: Bitmap
            ): Int =
                value.byteCount
        }

    fun get(
        packageName: String,
        sizePx: Int
    ): Bitmap? =
        cache.get(
            cacheKey(
                packageName,
                sizePx
            )
        )

    fun put(
        packageName: String,
        sizePx: Int,
        bitmap: Bitmap
    ) {
        cache.put(
            cacheKey(
                packageName,
                sizePx
            ),
            bitmap
        )
    }

    private fun cacheKey(
        packageName: String,
        sizePx: Int
    ): String =
        "$packageName@$sizePx"
}

/*
 * ================================================================
 * DRAWABLE -> BITMAP
 * ================================================================
 */

private fun drawableToBitmap(
    drawable: android.graphics.drawable.Drawable,
    sizePx: Int
): Bitmap {
    val bitmap =
        createBitmap(sizePx, sizePx)

    val canvas =
        AndroidCanvas(bitmap)

    drawable.setBounds(
        0,
        0,
        sizePx,
        sizePx
    )

    drawable.draw(canvas)

    return bitmap
}

private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity ->
            this

        is ContextWrapper ->
            baseContext.findActivity()

        else ->
            null
    }
