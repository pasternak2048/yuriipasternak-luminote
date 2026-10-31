package com.yp.luminote.app.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.*
import com.yp.luminote.app.notification.NotificationEffectAdmission
import com.yp.luminote.app.notification.NotificationEffectAdmissionPolicy
import com.yp.luminote.app.overlay.service.HaloEffectController
import com.yp.luminote.app.resonance.ResonanceRuntime
import com.yp.luminote.app.ui.adaptive.LuminoteWindowSizeClass
import com.yp.luminote.app.ui.home.HomeScreen
import com.yp.luminote.app.ui.theme.LuminoteTheme
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Real VM/UI/controller handoff with isolated writes; never revokes a device permission. */
@RunWith(AndroidJUnit4::class)
class NativeModePublicationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedCommitAcrossThreeOwnersKeepsOneSelectedModeAndAcceptsNotifications() = exercise(false)
    @Test fun failedCommitAcrossThreeOwnersKeepsVisibleSelectionAndNewUserChoiceWins() = exercise(true)

    private fun exercise(failOld: Boolean) {
        assumeTrue("Requires an idle native session", ResonanceRuntime.canStart())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val repository = LuminoteSettingsRepository(instrumentation.context)
        val saved = runBlocking { repository.settings.first() }
        runBlocking { repository.saveSettings(saved.copy(haloMode = HaloMode.NOTIFICATIONS)) }
        val deniedAudio = object : ContextWrapper(target) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        val stores = mutableListOf<ViewModelStore>()
        val oldGate = CompletableDeferred<Unit>()
        val oldFinished = AtomicBoolean(false)
        val allowReplacementSave = AtomicBoolean(false)
        var lease = 0L
        lateinit var first: LuminoteSettingsViewModel
        lateinit var latest: LuminoteSettingsViewModel
        var latestCreated = false
        fun model(repo: LuminoteSettingsRepository, writer: suspend (LuminoteSettings) -> Unit): LuminoteSettingsViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LuminoteSettingsViewModel(repo, writer) as T
            })[LuminoteSettingsViewModel::class.java]
        }
        try {
            compose.runOnIdle {
                first = model(repository) { settings ->
                    try {
                        // Delay acknowledgment after the old write, not DataStore's serialized
                        // commit order: a late owner callback must not overwrite a newer choice.
                        if (!failOld) repository.saveSettings(settings)
                        oldGate.await()
                        if (failOld) throw IOException("delayed old-owner failure")
                    } finally { oldFinished.set(true) }
                }
            }
            compose.waitUntil(5_000) { first.settingsLoaded.value }
            compose.runOnIdle {
                lease = HaloEffectController.bindSelectedMode(deniedAudio, first)
                first.setHaloMode(HaloMode.RESONANCE)
                latest = first
                latestCreated = true
            }
            repeat(3) {
                compose.runOnIdle {
                    HaloEffectController.releaseSelectedModeForeground(lease)
                    val replacement = model(repository) { settings ->
                        if (!allowReplacementSave.get()) throw IOException("replacement save unavailable")
                        repository.saveSettings(settings)
                    }
                    // This is the same pre-render adoption used by MainActivity.
                    HaloEffectController.prepareSelectedModeViewModel(replacement)
                    assertTrue(replacement.settingsLoaded.value)
                    assertEquals(HaloMode.RESONANCE, replacement.settings.value.haloMode)
                    assertFalse(replacement.publication.value.committed)
                    lease = HaloEffectController.bindSelectedMode(deniedAudio, replacement)
                    latest = replacement
                }
            }
            compose.setContent {
                LuminoteTheme { HomeScreen({}, {}, {}, {}, {}, LuminoteWindowSizeClass.COMPACT, latest) }
            }
            compose.onNodeWithText(compose.activity.getString(R.string.resonance_title)).assertIsSelected()
            compose.runOnIdle {
                val effective = HaloEffectController.currentModeSettings(LuminoteSettings(haloMode = HaloMode.OFF))!!
                assertEquals(latest.settings.value, effective)
                assertEquals(NotificationEffectAdmission.Accepted, NotificationEffectAdmissionPolicy.evaluate(
                    "com.fixture", "before-commit", target.packageName, effective))
            }
            compose.onNodeWithText(compose.activity.getString(R.string.resonance_title)).assertIsSelected()
            compose.runOnIdle { allowReplacementSave.set(true) }
            compose.onNodeWithText(compose.activity.getString(R.string.alerts)).performClick().assertIsSelected()
            compose.waitUntil(5_000) { latest.publication.value.committed }
            oldGate.complete(Unit)
            compose.waitUntil(5_000) { oldFinished.get() }
            compose.runOnIdle {
                assertEquals(HaloMode.NOTIFICATIONS, HaloEffectController.currentModeSettings(null)!!.haloMode)
                assertEquals(HaloMode.NOTIFICATIONS, latest.settings.value.haloMode)
            }
        } finally {
            oldGate.complete(Unit)
            compose.waitUntil(5_000) { oldFinished.get() }
            if (latestCreated) {
                compose.runOnIdle { allowReplacementSave.set(true); latest.setHaloMode(HaloMode.OFF) }
                compose.waitUntil(5_000) { latest.publication.value.committed }
            }
            compose.runOnIdle { HaloEffectController.releaseSelectedModeForeground(lease) }
            // Restore the actual product source through the normal VM/controller path.
            val targetRepository = LuminoteSettingsRepository(target)
            lateinit var genuine: LuminoteSettingsViewModel
            compose.runOnIdle { genuine = model(targetRepository, targetRepository::saveSettings) }
            compose.waitUntil(5_000) { genuine.settingsLoaded.value }
            compose.runOnIdle {
                val genuineLease = HaloEffectController.bindSelectedMode(deniedAudio, genuine)
                HaloEffectController.releaseSelectedModeForeground(genuineLease)
                HaloEffectController.refreshSelectedModeAccess(target)
                stores.forEach(ViewModelStore::clear)
            }
            runBlocking { repository.saveSettings(saved) }
        }
    }
}
