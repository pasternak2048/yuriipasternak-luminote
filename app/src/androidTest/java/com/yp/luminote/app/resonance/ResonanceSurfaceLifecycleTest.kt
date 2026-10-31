package com.yp.luminote.app.resonance

import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real accelerated View detach/attach; does not claim keyguard or audio capture coverage. */
@RunWith(AndroidJUnit4::class)
class ResonanceSurfaceLifecycleTest {
    @Test fun repeatedSurfaceRecreationRetainsSessionAndRestartsGpuFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val keyguardLocked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val displayState = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.state
        assumeTrue("Requires a visible unlocked display: interactive=$interactive, displayState=$displayState, keyguardLocked=$keyguardLocked; screen-off/keyguard rendering is separate device QA",
            interactive && displayState == Display.STATE_ON && !keyguardLocked)
        assumeTrue("Requires an idle session", ResonanceRuntime.canStart())
        val generation = ResonanceRuntime.begin()
        var failure: String? = null
        var view: HaloView? = null
        var container: FrameLayout? = null
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                container = FrameLayout(activity)
                activity.setContentView(container)
                view = HaloView(activity, HaloConfig())
                container!!.addView(view)
                view!!.startResonance(generation) { failure = it }
                assertTrue("Hardware acceleration required for Resonance", view!!.isHardwareAccelerated)
            }
            awaitFrames(0)
            repeat(3) {
                val frames = ResonanceRuntime.gpuFrames
                scenario.onActivity { container!!.removeView(view) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(ResonanceRuntime.isCurrent(generation))
                scenario.onActivity { container!!.addView(view) }
                awaitFrames(frames)
                assertNull(failure)
                assertTrue(ResonanceRuntime.isCurrent(generation))
            }
        } finally {
            scenario.onActivity { view?.stopResonance(); it.setContentView(View(it)) }
            scenario.close()
            ResonanceRuntime.end(generation)
            ResonanceRuntime.cleanupComplete(generation)
        }
    }

    private fun awaitFrames(previous: Long) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline && ResonanceRuntime.gpuFrames <= previous + 2) Thread.sleep(25)
        assertTrue("Recreated GPU surface must resume submissions", ResonanceRuntime.gpuFrames > previous + 2)
    }
}
