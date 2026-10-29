package com.yp.luminote.app.resonance

import org.junit.Assert.*
import org.junit.Test

class ResonanceRuntimeTest {
    @Test fun staleGenerationCannotTerminateNewSessionOrPublishAudio() {
        val first = ResonanceRuntime.begin()
        assertTrue(ResonanceRuntime.end(first))
        assertTrue(ResonanceRuntime.cleanupPending)
        // Mode-off may arrive immediately after Stop invalidates the active session.
        assertEquals(first, ResonanceRuntime.generationForTermination())
        assertFalse(ResonanceRuntime.canStart())
        ResonanceRuntime.cleanupComplete(first)
        assertTrue(ResonanceRuntime.canStart())
        assertEquals(0L, ResonanceRuntime.generationForTermination())
        val second = ResonanceRuntime.begin()
        assertFalse(ResonanceRuntime.end(first))
        ResonanceController.publish(AudioFeatureSnapshot(first, 1, loudness = 1f), 100)
        assertEquals(second, ResonanceRuntime.snapshot.generation)
        assertTrue(ResonanceRuntime.end(second))
        ResonanceRuntime.cleanupComplete(second)
    }
    @Test fun statisticsContinuePublishingBeyondFirstSnapshot() {
        val generation = ResonanceRuntime.begin()
        repeat(12) { ResonanceController.publish(AudioFeatureSnapshot(generation, it.toLong(), loudness = 1f), 1) }
        assertEquals(11L, ResonanceController.state.value.analysisFrames)
        assertEquals(ResonanceStatus.RUNNING, ResonanceController.state.value.status)
        ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation)
    }
    @Test fun initializedProviderDoesNotClaimAudioUntilNonzeroFeaturesArrive() {
        val generation = ResonanceRuntime.begin()
        ResonanceController.providerReady(generation)
        assertTrue(ResonanceController.state.value.providerInitialized)
        assertEquals(ResonanceStatus.WAITING_FOR_AUDIO, ResonanceController.state.value.status)
        repeat(6) { ResonanceController.publish(AudioFeatureSnapshot(generation, it.toLong()), 1, false) }
        assertEquals(0L, ResonanceController.state.value.nonzeroFeatureFrames)
        repeat(6) { ResonanceController.publish(AudioFeatureSnapshot(generation, it.toLong(), loudness = .8f), 1, true) }
        assertEquals(ResonanceStatus.RUNNING, ResonanceController.state.value.status)
        assertTrue(ResonanceController.state.value.nonzeroFeatureFrames > 0)
        ResonanceController.waitingForFrames(generation)
        assertEquals(ResonanceStatus.WAITING_FOR_AUDIO, ResonanceController.state.value.status)
        ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation)
        ResonanceController.providerReady(generation)
        assertEquals(ResonanceStatus.WAITING_FOR_AUDIO, ResonanceController.state.value.status)
    }
}
