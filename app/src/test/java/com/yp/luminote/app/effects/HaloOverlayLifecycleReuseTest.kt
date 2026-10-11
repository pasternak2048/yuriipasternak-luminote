package com.yp.luminote.app.effects

import com.yp.luminote.app.overlay.service.ApplicationOverlayReuseLifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloOverlayLifecycleReuseTest {
    @Test
    fun `static calibration survives the old finite cleanup deadline and matching pause removes it`() {
        val scheduler = ControlledScheduler()
        val overlay = FakeOverlayHost(attached = true)
        val lifecycle =
            ApplicationOverlayReuseLifecycle(
                cancelPendingFiniteRemoval = scheduler::cancelFiniteRemoval,
                discardDetachedCachedView = overlay::discardCachedView
            )

        scheduler.scheduleFiniteRemoval(overlay::remove)
        overlay.showStaticCalibration(lifecycle)
        scheduler.advancePastFiniteRemovalDeadline()

        assertTrue(overlay.staticCalibrationVisible)
        overlay.pauseStaticCalibration(lifecycle, matchingSession = true)
        assertFalse(overlay.staticCalibrationVisible)
    }

    @Test
    fun `detached cached overlay is discarded and replaced rather than updated invisibly`() {
        val overlay = FakeOverlayHost(attached = false)
        val lifecycle =
            ApplicationOverlayReuseLifecycle(
                cancelPendingFiniteRemoval = {},
                discardDetachedCachedView = overlay::discardCachedView
            )

        overlay.showFiniteOverlay(lifecycle)

        assertTrue(overlay.cachedViewWasDiscarded)
        assertTrue(overlay.replacementAttached)
        assertFalse(overlay.detachedViewWasUpdated)
    }

    private class ControlledScheduler {
        private var finiteRemoval: (() -> Unit)? = null

        fun scheduleFiniteRemoval(action: () -> Unit) {
            finiteRemoval = action
        }

        fun cancelFiniteRemoval() {
            finiteRemoval = null
        }

        fun advancePastFiniteRemovalDeadline() {
            finiteRemoval?.invoke()
        }
    }

    private class FakeOverlayHost(
        private var attached: Boolean
    ) {
        private var cachedViewPresent = true
        var cachedViewWasDiscarded = false
            private set
        var detachedViewWasUpdated = false
            private set
        var replacementAttached = false
            private set
        var staticCalibrationVisible = false
            private set

        fun showStaticCalibration(lifecycle: ApplicationOverlayReuseLifecycle) {
            lifecycle.prepareForShow(
                staticCalibration = true,
                cachedViewAttached = attached
            )
            staticCalibrationVisible = true
        }

        fun showFiniteOverlay(lifecycle: ApplicationOverlayReuseLifecycle) {
            lifecycle.prepareForShow(
                staticCalibration = false,
                cachedViewAttached = attached
            )
            if (cachedViewPresent) {
                detachedViewWasUpdated = true
            } else {
                cachedViewPresent = true
                attached = true
                replacementAttached = true
            }
        }

        fun discardCachedView() {
            cachedViewPresent = false
            cachedViewWasDiscarded = true
        }

        fun remove() {
            staticCalibrationVisible = false
        }

        fun pauseStaticCalibration(
            lifecycle: ApplicationOverlayReuseLifecycle,
            matchingSession: Boolean
        ) {
            lifecycle.removeOwnedStaticCalibration(matchingSession, ::remove)
        }
    }
}
