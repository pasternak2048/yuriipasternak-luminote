package com.yp.luminote.app.debug

import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuValidationLifecycleInstrumentationTest {
    @Test fun backgroundCancelsSelfTestDisposesItsBackendAndRetainsSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = FrameLayout(activity)
                activity.setContentView(container)
                val controller = VisualEffectsDemoController(activity, container)
                try {
                    controller.previewAnimation(VisualEffectsDemoEngine.GPU, VisualEffectsDemoFamily.FULL_CONTOUR, VisualEffectsDemoMode.BASELINE)
                    val running = controller.runGpuSelfTest()
                    assertEquals(GpuValidationPhase.RUNNING, running.phase)
                    controller.onHostStopped()
                    val cancelled = controller.gpuValidationSnapshot()
                    assertEquals(GpuValidationPhase.CANCELLED, cancelled.phase)
                    assertEquals(false, cancelled.active)
                    assertEquals(running.generation, cancelled.generation)
                    assertTrue(GpuPulseEvidence.snapshot(running.generation!!)!!.disposals > 0)
                    assertEquals(VisualEffectsDemoRequestedBackend.GPU_PULSE, controller.diagnosticsSnapshot().requestedBackend)
                    assertEquals(VisualEffectsDemoFamily.FULL_CONTOUR, controller.selectedFamily)
                    assertEquals(cancelled, controller.gpuValidationSnapshot())
                } finally { controller.dispose() }
            }
        }
    }
}
