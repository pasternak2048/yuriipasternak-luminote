package com.yp.luminote.app.effects
import com.yp.luminote.app.animation.HaloFiniteFrameScheduler
import com.yp.luminote.app.animation.HaloAnimation
import com.yp.luminote.app.animation.HaloAnimationRequest

import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloAnimationTest {

    @Test
    fun `finite frames start immediately then use eight millisecond cadence`() {
        val scheduler =
            FakeFiniteScheduler()
        val animation =
            HaloAnimation(
                onFrame = {},
                finiteFrameScheduler = scheduler,
                elapsedRealtimeMs = scheduler::nowMs
            )

        animation.start(request())

        assertEquals(listOf(0L), scheduler.postedDelays)

        scheduler.runNext()

        assertEquals(listOf(0L, 8L), scheduler.postedDelays)
    }

    @Test
    fun `cancel removes only animation runnable and stale finite runnable cannot complete`() {
        val scheduler =
            FakeFiniteScheduler()
        var completions =
            0
        val animation =
            HaloAnimation(
                onFrame = {},
                onCompleted = { completions++ },
                finiteFrameScheduler = scheduler,
                elapsedRealtimeMs = scheduler::nowMs
            )

        animation.start(request())

        val staleRunnable =
            scheduler.lastPostedRunnable()
        val unrelatedRunnable =
            Runnable {}

        scheduler.postDelayed(unrelatedRunnable, 100L)
        animation.cancel()

        assertTrue(scheduler.contains(unrelatedRunnable))

        staleRunnable.run()
        animation.start(request())
        scheduler.runUntil(700L)

        assertEquals(1, completions)
        assertFalse(scheduler.hasAnimationWork())
    }

    @Test
    fun `finite repeats use delayed restart without a display frame callback`() {
        val scheduler =
            FakeFiniteScheduler()
        var completions =
            0
        val animation =
            HaloAnimation(
                onFrame = {},
                onCompleted = { completions++ },
                finiteFrameScheduler = scheduler,
                elapsedRealtimeMs = scheduler::nowMs
            )

        animation.start(
            request(
                interval = 0.1f,
                cycles = 2,
                motion = HaloMotion.SNAKE
            )
        )

        scheduler.runUntil(1_500L)

        assertEquals(1, completions)
    }

    private fun request(
        duration: Float = 0.6f,
        interval: Float = 0f,
        cycles: Int = 1,
        motion: HaloMotion = HaloMotion.PULSE
    ) = HaloAnimationRequest(
        duration,
        interval,
        cycles != 1,
        cycles,
        false,
        motion
    )

    private class FakeFiniteScheduler : HaloFiniteFrameScheduler {
        private data class Scheduled(
            val runnable: Runnable,
            val dueAtMs: Long
        )

        private val scheduled =
            mutableListOf<Scheduled>()

        val postedDelays =
            mutableListOf<Long>()

        var nowMs =
            0L
            private set

        override fun postDelayed(
            runnable: Runnable,
            delayMs: Long
        ) {
            postedDelays += delayMs
            scheduled += Scheduled(runnable, nowMs + delayMs)
        }

        override fun removeCallbacks(
            runnable: Runnable
        ) {
            scheduled.removeAll {
                it.runnable === runnable
            }
        }

        fun lastPostedRunnable(): Runnable =
            requireNotNull(scheduled.lastOrNull()).runnable

        fun contains(
            runnable: Runnable
        ): Boolean = scheduled.any {
            it.runnable === runnable
        }

        fun runUntil(
            limitMs: Long
        ) {
            while (true) {
                val next =
                    scheduled.minByOrNull {
                        it.dueAtMs
                    } ?: return

                if (next.dueAtMs > limitMs) {
                    return
                }

                runNext()
            }
        }

        fun runNext() {
            val next =
                scheduled.minByOrNull {
                    it.dueAtMs
                } ?: return

            scheduled.remove(next)
            nowMs = next.dueAtMs
            next.runnable.run()
        }

        fun hasAnimationWork(): Boolean =
            scheduled.isNotEmpty()
    }
}
