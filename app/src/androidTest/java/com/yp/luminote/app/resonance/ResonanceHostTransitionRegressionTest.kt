package com.yp.luminote.app.resonance

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.removeOverlayWindowImmediately
import com.yp.luminote.app.overlay.service.HaloEffectController
import com.yp.luminote.app.overlay.service.HaloOverlayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResonanceHostTransitionRegressionTest {
    @Test fun rejectedStartEndsGenerationBeforeWarmSettingsRestorationBegins() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        // Preload the same DataStore used by restoration: first() need not suspend afterward.
        runBlocking { LuminoteSettingsRepository(target).settings.first() }
        assumeTrue("Requires an idle session", ResonanceRuntime.canStart())
        val generation = ResonanceRuntime.begin()
        var rejected = false
        var restorationSawActiveGeneration: Boolean? = null
        val rejectingContext = object : ContextWrapper(target) {
            override fun startForegroundService(service: Intent): ComponentName? {
                rejected = true
                throw IllegalStateException("Regression fixture rejects initial presentation service")
            }
            override fun getApplicationContext(): Context {
                // restoreAmbientAfterResonance reads applicationContext before launching its
                // immediately-resuming warm-settings coroutine. Observe that actual boundary.
                if (rejected) restorationSawActiveGeneration = ResonanceRuntime.isCurrent(generation)
                return target.applicationContext
            }
        }
        try {
            instrumentation.runOnMainSync {
                assertFalse(HaloOverlayService.startResonance(rejectingContext, generation, HaloConfig()))
                assertEquals("End must precede final restoration", false, restorationSawActiveGeneration)
                assertFalse(ResonanceRuntime.isCurrent(generation))
                assertFalse(HaloEffectController.hasResonance(target))
            }
        } finally {
            instrumentation.runOnMainSync { HaloOverlayService.stopResonance(target, generation) }
            ResonanceRuntime.end(generation)
            ResonanceController.cleanupComplete(generation)
        }
    }

    @Test fun immediateStopRemovesRegisteredRootBeforeFirstTraversal() {
        var pending: View? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = activity.getSystemService(WindowManager::class.java)
                pending = View(activity)
                try {
                    manager.addView(pending, panelParams(activity.window.decorView.windowToken))
                    assertNotNull("addView registers the root before traversal", pending!!.parent)
                    assertFalse("Same main callback must precede first traversal", pending!!.isAttachedToWindow)
                    removeOverlayWindowImmediately(manager, pending!!)
                    assertNull(pending!!.parent)
                    assertFalse(pending!!.isAttachedToWindow)
                } finally { removeOverlayWindowImmediately(manager, pending!!) }
            }
            awaitTraversal()
            scenario.onActivity {
                assertNull("Removed root must not attach on a later traversal", pending!!.parent)
                assertFalse(pending!!.isAttachedToWindow)
            }
        }
    }

    @Test fun controlledWindowHandoffDetachesOldRootBeforeReplacementAdmission() {
        var old: View? = null
        var replacement: View? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    old = View(activity)
                    activity.getSystemService(WindowManager::class.java)
                        .addView(old, panelParams(activity.window.decorView.windowToken))
                }
                // Attachment happens during traversal, not synchronously in addView.
                awaitTraversal()
                scenario.onActivity { activity ->
                    val manager = activity.getSystemService(WindowManager::class.java)
                    assertTrue("Old fixture must complete traversal first", old!!.isAttachedToWindow)
                    removeOverlayWindowImmediately(manager, old!!)
                    assertFalse("Old root must detach before replacement admission", old!!.isAttachedToWindow)
                    assertNull(old!!.parent)
                    replacement = View(activity)
                    manager.addView(replacement, panelParams(activity.window.decorView.windowToken))
                    assertNotNull(replacement!!.parent)
                    assertNull(old!!.parent)
                }
                awaitTraversal()
                scenario.onActivity { activity ->
                    assertTrue(replacement!!.isAttachedToWindow)
                    removeOverlayWindowImmediately(activity.getSystemService(WindowManager::class.java), replacement!!)
                    assertNull(replacement!!.parent)
                    assertFalse(replacement!!.isAttachedToWindow)
                }
            } finally {
                scenario.onActivity { activity ->
                    val manager = activity.getSystemService(WindowManager::class.java)
                    old?.let { removeOverlayWindowImmediately(manager, it) }
                    replacement?.let { removeOverlayWindowImmediately(manager, it) }
                }
            }
        }
    }

    private fun awaitTraversal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frame = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
        }
        assertTrue("A display frame must be delivered", frame.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun panelParams(token: android.os.IBinder) = WindowManager.LayoutParams(64, 64,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT).apply {
        this.token = token
        gravity = Gravity.TOP or Gravity.START
    }
}
