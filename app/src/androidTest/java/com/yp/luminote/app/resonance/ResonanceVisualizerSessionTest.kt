package com.yp.luminote.app.resonance

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Starts no playback. Run only with independently attributed real external music already playing. */
@RunWith(AndroidJUnit4::class)
class ResonanceVisualizerSessionTest {
    @Test fun realExternalMusicDrivesIntegratedGpuAndStopsCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Opt in with real externally playing music", InstrumentationRegistry.getArguments().getString("resonanceRealPlayback") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Human must grant overlay through Luminote Access", Settings.canDrawOverlays(context))
        assumeTrue("Audio visualization runtime permission must be granted", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { assertTrue("Explicit foreground start", ResonanceController.start(it)) }
            await("Native Visualizer ready + nonzero real features + strict GPU frames") {
                val state = ResonanceController.state.value
                state.providerInitialized && state.nonzeroFeatureFrames >= 5 && state.gpuFrames >= 5
            }
            val active = ResonanceController.state.value
            val feature = ResonanceRuntime.snapshot
            Log.i("ResonanceRealPlayback", "providerReady=${active.providerInitialized} nonzero=${active.nonzeroFeatureFrames} analysis=${active.analysisFrames} gpu=${active.gpuFrames} low=${feature.low} mid=${feature.mid} high=${feature.high} loudness=${feature.loudness}; source attribution required independently")
            scenario.onActivity { ResonanceController.stop(it) }
            await("Native provider released and session ended") {
                val state = ResonanceController.state.value
                state.status == ResonanceStatus.STOPPED && !state.cleanupPending && !state.providerInitialized
            }
            val count = ResonanceRuntime.analysisCount
            val gpu = ResonanceRuntime.gpuFrames
            Thread.sleep(500)
            assertEquals("No feature callbacks after Stop", count, ResonanceRuntime.analysisCount)
            assertEquals("No GPU submissions after Stop", gpu, ResonanceRuntime.gpuFrames)
            assertEquals(0L, ResonanceRuntime.activeGeneration)
            Log.i("ResonanceRealPlayback", "stopReleased=true analysis=$count gpu=$gpu stableAfterStopMs=500")
        } finally { ResonanceController.stop(context); scenario.close() }
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        fail("$message; state=${ResonanceController.state.value}")
    }
}
