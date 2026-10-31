package com.yp.luminote.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.data.settings.ThemeMode
import com.yp.luminote.app.overlay.service.ResonanceAccessState
import com.yp.luminote.app.resonance.ResonanceRuntime
import com.yp.luminote.app.resonance.ResonanceState
import com.yp.luminote.app.resonance.ResonanceStatus
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.home.HomeScreen
import com.yp.luminote.app.ui.resonance.ResonanceModeStatusCard
import com.yp.luminote.app.ui.resonance.ResonanceScreen
import com.yp.luminote.app.ui.theme.LuminoteTheme
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the test APK DataStore, never the installed app's selected mode. */
@RunWith(AndroidJUnit4::class)
class ResonanceNativeModeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository = LuminoteSettingsRepository(InstrumentationRegistry.getInstrumentation().context)
    private val stores = mutableListOf<ViewModelStore>()

    private fun newViewModel(): LuminoteSettingsViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, LuminoteSettingsViewModelFactory(repository))[LuminoteSettingsViewModel::class.java]
    }

    @After fun clearViewModels() {
        compose.runOnIdle { stores.forEach(ViewModelStore::clear) }
    }

    @Test fun desiredRadioPersistsWithoutNavigationOrDirectRuntimeStartup() {
        val initial = runBlocking { repository.settings.first() }
        runBlocking { repository.saveSettings(initial.copy(haloMode = HaloMode.NOTIFICATIONS)) }
        var current by mutableStateOf<LuminoteSettingsViewModel?>(null)
        var settingsNavigation = 0
        val generationBefore = ResonanceRuntime.activeGeneration
        compose.runOnIdle { current = newViewModel() }
        compose.setContent {
            LuminoteTheme {
                current?.let { model ->
                    HomeScreen({}, {}, {}, {}, {}, LuminoteWindowSizeClass.COMPACT, model,
                        onResonanceClick = { settingsNavigation++ })
                }
            }
        }
        val context = compose.activity
        val resonance = context.getString(R.string.resonance_title)
        val entry = context.getString(R.string.resonance_settings)
        try {
            compose.waitUntil(5_000) { current!!.settingsLoaded.value }
            compose.onNodeWithText(entry).assertDoesNotExist()
            compose.onNodeWithText(resonance).performClick().assertIsSelected()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            assertEquals("Selecting the radio must not open settings", 0, settingsNavigation)
            // This ComponentActivity has no foreground runtime owner. Home must only publish
            // desired mode; activation belongs to MainActivity's existing controller boundary.
            assertEquals(generationBefore, ResonanceRuntime.activeGeneration)
            compose.waitUntil(5_000) {
                runBlocking { repository.settings.first().haloMode == HaloMode.RESONANCE }
            }
            compose.runOnIdle {
                stores.forEach(ViewModelStore::clear)
                current = newViewModel()
            }
            compose.waitUntil(5_000) { current!!.settingsLoaded.value }
            compose.onNodeWithText(resonance).assertIsSelected()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            compose.onNodeWithText(entry).performScrollTo().performClick()
            assertEquals(1, settingsNavigation)
            compose.onNodeWithText(context.getString(R.string.ambient)).performScrollTo().performClick().assertIsSelected()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            compose.onNodeWithText(entry).assertDoesNotExist()
        } finally {
            runBlocking { repository.saveSettings(initial) }
        }
    }

    @Test fun missingAccessKeepsSelectionAndProvidesCentralAccessPathAcrossThemes() {
        var accessNavigation = 0
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            LuminoteTheme(themeMode = theme) {
                ResonanceModeStatusCard(
                    ResonanceAccessState(audioGranted = false, overlayGranted = false),
                    ResonanceState(status = ResonanceStatus.STOPPED),
                    selected = true,
                    onAccessClick = { accessNavigation++ }
                )
            }
        }
        val context = compose.activity
        fun assertBlockedSelection() {
            compose.onNodeWithText(context.getString(R.string.resonance_selected)).assertExists()
            compose.onNodeWithText(context.getString(R.string.resonance_waiting_access)).assertExists()
            compose.onNodeWithText(context.getString(R.string.resonance_audio_required)).assertExists()
            compose.onNodeWithText(context.getString(R.string.resonance_overlay_required)).assertExists()
        }
        assertBlockedSelection()
        compose.onNodeWithText(context.getString(R.string.access)).performClick()
        assertEquals(1, accessNavigation)
        compose.runOnIdle { theme = ThemeMode.DARK }
        assertBlockedSelection()
    }

    @Test fun resonanceSettingsExposeAccessibleControlsWithoutLifecycleButtons() {
        val initial = runBlocking { repository.settings.first() }
        runBlocking { repository.saveSettings(initial.copy(haloMode = HaloMode.RESONANCE)) }
        lateinit var model: LuminoteSettingsViewModel
        compose.runOnIdle { model = newViewModel() }
        compose.setContent { LuminoteTheme { ResonanceScreen({}, model) } }
        val context = compose.activity
        try {
            compose.waitUntil(5_000) { model.settingsLoaded.value }
            compose.onNodeWithText(context.getString(R.string.resonance_settings)).assertExists()
            compose.onNodeWithText(context.getString(R.string.resonance_start)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.resonance_stop)).assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.resonance_intensity))
                .performScrollTo().assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.resonance_beat_response))
                .performScrollTo().assertExists()
        } finally {
            runBlocking { repository.saveSettings(initial) }
        }
    }
}
