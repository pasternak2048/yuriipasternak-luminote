package com.yp.luminote.app.effects

import android.view.Choreographer
import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HaloAnimationTest {

    @Test
    fun `finite completion is cadence independent at 60 90 and 120 hz`() {
        listOf(16_666_667L, 11_111_111L, 8_333_333L).forEach { cadence ->
            val scheduler = FakeScheduler()
            var completions = 0
            val animation = HaloAnimation({}, onCompleted = { completions++ }, frameScheduler = scheduler)
            animation.start(request())
            scheduler.runUntil(700_000_000L, cadence)
            assertEquals(1, completions)
            assertFalse(scheduler.hasFrame)
        }
    }

    @Test
    fun `cancel before first frame and stale frame after restart do not complete`() {
        val scheduler = FakeScheduler()
        var completions = 0
        val animation = HaloAnimation({}, onCompleted = { completions++ }, frameScheduler = scheduler)
        animation.start(request())
        val stale = scheduler.take()
        animation.cancel()
        stale.doFrame(1L)
        animation.start(request())
        stale.doFrame(700_000_000L)
        scheduler.runUntil(700_000_000L, 16_666_667L)
        assertEquals(1, completions)
    }

    @Test
    fun `slow snake repeats complete without watchdog style stall`() {
        val scheduler = FakeScheduler()
        var completions = 0
        val animation = HaloAnimation({}, onCompleted = { completions++ }, frameScheduler = scheduler)
        animation.start(request(duration = 0.6f, interval = 0.2f, cycles = 3, motion = HaloMotion.SNAKE))
        scheduler.runUntil(2_500_000_000L, 16_666_667L)
        assertEquals(1, completions)
    }

    private fun request(duration: Float = 0.6f, interval: Float = 0f, cycles: Int = 1, motion: HaloMotion = HaloMotion.PULSE) =
        HaloAnimationRequest(duration, interval, cycles != 1, cycles, false, motion)

    private class FakeScheduler : HaloFrameScheduler {
        private var callback: Choreographer.FrameCallback? = null
        var hasFrame = false
            private set
        override fun post(callback: Choreographer.FrameCallback) { this.callback = callback; hasFrame = true }
        override fun remove(callback: Choreographer.FrameCallback) { if (this.callback === callback) { this.callback = null; hasFrame = false } }
        fun take(): Choreographer.FrameCallback = requireNotNull(callback)
        fun runUntil(limit: Long, cadence: Long) {
            var time = 0L
            while (hasFrame && time <= limit) {
                val next = take()
                hasFrame = false
                callback = null
                next.doFrame(time)
                time += cadence
            }
        }
    }
}
