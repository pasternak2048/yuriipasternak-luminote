package com.yp.luminote.app.resonance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in native session-0 gate. Starts no audio; external source evidence must be recorded separately. */
@RunWith(AndroidJUnit4::class)
class VisualizerCompatibilityGateTest {
    @Test fun closeBeforeStartRejectsRestartWithoutCreatingWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = VisualizerAudioProvider(context, 12, { true }, {}, { _, _, _ -> }, {})
        val first = provider.close()
        assertSame(first, provider.close())
        first.get(1, TimeUnit.SECONDS)
        val rejected = runCatching { provider.start() }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
    }
    @Test fun realOutputMixInitializationCallbacksAndRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Opt in to session-0 native gate", InstrumentationRegistry.getArguments().getString("visualizerGate") == "true")
        val context = instrumentation.targetContext
        // Normal runtime permission grant scoped explicitly to this opt-in device test.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        val current = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val frames = CountDownLatch(8)
        val callbacks = AtomicInteger(0)
        val nonzero = AtomicInteger(0)
        val mainCallbacks = AtomicInteger(0)
        var configuration: VisualizerConfiguration? = null
        var failure: String? = null
        val provider = VisualizerAudioProvider(context, 11, { current.get() },
            onReady = { configuration = it; ready.countDown() },
            onFeature = { _, _, active ->
                callbacks.incrementAndGet()
                if (active) nonzero.incrementAndGet()
                if (Looper.myLooper() == Looper.getMainLooper()) mainCallbacks.incrementAndGet()
                frames.countDown()
            },
            onFailure = { failure = it; ready.countDown(); repeat(8) { frames.countDown() } })
        try {
            provider.start()
            assertTrue("Provider initialization deadline", ready.await(10, TimeUnit.SECONDS))
            assertNull("Output-mix initialization failure", failure)
            assertNotNull(configuration)
            assertTrue("Native FFT callback delivery", frames.await(15, TimeUnit.SECONDS))
            assertNull(failure)
            assertTrue(callbacks.get() >= 8)
            assertEquals(0, mainCallbacks.get())
        } finally {
            current.set(false)
            provider.close().get(10, TimeUnit.SECONDS)
        }
        val stoppedCallbacks = callbacks.get()
        Thread.sleep(300)
        assertEquals("No feature callbacks after close completes", stoppedCallbacks, callbacks.get())
        Log.i("ResonanceVisualizerGate", "config=$configuration callbacks=${callbacks.get()} nonzero=${nonzero.get()} mainCallbacks=${mainCallbacks.get()} closed=true externalSource=NOT_ATTRIBUTED")
        // Nonzero is reported honestly; native initialization/delivery alone does not prove music support.
    }
}
