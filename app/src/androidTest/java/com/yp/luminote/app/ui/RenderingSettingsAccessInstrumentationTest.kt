package com.yp.luminote.app.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.data.settings.RenderingEngine
import com.yp.luminote.app.data.settings.ThemeMode
import com.yp.luminote.app.ui.access.AccessScreen
import com.yp.luminote.app.ui.settings.SettingsScreen
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

@RunWith(AndroidJUnit4::class)
class RenderingSettingsAccessInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // Use the test APK's DataStore, so these checks never overwrite the user's renderer preference.
    private val repository = LuminoteSettingsRepository(InstrumentationRegistry.getInstrumentation().context)
    private val stores = mutableListOf<ViewModelStore>()

    private fun newViewModel(): LuminoteSettingsViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, LuminoteSettingsViewModelFactory(repository))[LuminoteSettingsViewModel::class.java]
    }

    @After fun clearViewModels() {
        compose.runOnIdle { stores.forEach(ViewModelStore::clear) }
    }

    @Test fun rendererSelectionPersistsAcrossViewModelRecreationAndThemeChanges() {
        val initial = runBlocking { repository.settings.first() }
        runBlocking { repository.saveSettings(initial.copy(renderingEngine = RenderingEngine.CANVAS)) }
        lateinit var model: LuminoteSettingsViewModel
        var current by mutableStateOf<LuminoteSettingsViewModel?>(null)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.runOnIdle { model = newViewModel(); current = model }
        compose.setContent {
            LuminoteTheme(themeMode = theme) {
                current?.let { settingsModel ->
                    SettingsScreen({}, {}, {}, {}, {}, {}, settingsModel)
                }
            }
        }
        val context = compose.activity
        val title = context.getString(R.string.rendering_engine)
        val gpu = context.getString(R.string.rendering_engine_gpu)
        try {
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(gpu).performClick().assertIsSelected()
            compose.onNodeWithText(context.getString(R.string.done)).performClick()
            compose.waitUntil(5_000) {
                runBlocking { repository.settings.first().renderingEngine == RenderingEngine.GPU }
            }
            compose.runOnIdle {
                stores.forEach(ViewModelStore::clear)
                current = newViewModel()
                theme = ThemeMode.DARK
            }
            compose.waitUntil(5_000) { current!!.settingsLoaded.value }
            compose.onNodeWithText(context.getString(R.string.rendering_engine_selected, gpu)).assertExists()
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(gpu).assertIsSelected()
            assertEquals(RenderingEngine.GPU, runBlocking { repository.settings.first().renderingEngine })
        } finally {
            runBlocking { repository.saveSettings(initial) }
        }
    }

    @Test fun accessScreenReflectsActualAndroidAudioPermission() {
        lateinit var model: LuminoteSettingsViewModel
        compose.runOnIdle { model = newViewModel() }
        compose.setContent { LuminoteTheme { AccessScreen({}, model) } }
        val context = compose.activity
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val title = context.getString(R.string.audio_visualization_access)
        val status = context.getString(if (granted) R.string.allowed else R.string.required)
        compose.onNode(hasText(title) and hasText(status)).performScrollTo().assertExists()
    }

    @Test fun accessScreenRefreshesGrantAndRevocationOnResume() {
        lateinit var model: LuminoteSettingsViewModel
        lateinit var permissions: AudioPermissionContext
        compose.runOnIdle {
            model = newViewModel()
            permissions = AudioPermissionContext(compose.activity)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides permissions) {
                LuminoteTheme { AccessScreen({}, model) }
            }
        }
        val title = compose.activity.getString(R.string.audio_visualization_access)
        fun assertStatus(granted: Boolean) {
            val status = compose.activity.getString(if (granted) R.string.allowed else R.string.required)
            compose.onNode(hasText(title) and hasText(status)).performScrollTo().assertExists()
        }
        assertStatus(false)
        // Framework grant/revoke is intentionally injected: revoking the target APK kills the
        // instrumentation process. The real Activity lifecycle still drives the production refresh.
        for (granted in listOf(true, false)) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            permissions.audioGranted = granted
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertStatus(granted)
        }
    }

    private class AudioPermissionContext(base: Context) : ContextWrapper(base) {
        @Volatile var audioGranted = false
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) {
                if (audioGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            } else super.checkPermission(permission, pid, uid)
    }
}
