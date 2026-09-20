package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class HaloEffectCoordinatorTest {

    @Test
    fun `coalesces events from an application while its effect is active`() {
        val coordinator =
            HaloEffectCoordinator()

        val started =
            mutableListOf<HaloEffectRequest>()

        coordinator.attachRenderer(Any()) { request ->
            started += request
        }

        val first =
            request(
                packageName = "com.example.chat",
                notificationKey = "first"
            )

        coordinator.enqueue(first)
        coordinator.enqueue(
            request(
                packageName = "com.example.chat",
                notificationKey = "second"
            )
        )

        assertEquals(
            listOf(first),
            started
        )
    }

    @Test
    fun `resumes an active effect after its renderer is recreated`() {
        val coordinator =
            HaloEffectCoordinator()

        val firstOwner =
            Any()

        val startedByFirstRenderer =
            mutableListOf<HaloEffectRequest>()

        val request =
            request(
                packageName = "com.example.chat",
                notificationKey = "message"
            )

        coordinator.attachRenderer(firstOwner) { started ->
            startedByFirstRenderer += started
        }

        coordinator.enqueue(request)
        coordinator.detachRenderer(firstOwner)

        val startedByReplacementRenderer =
            mutableListOf<HaloEffectRequest>()

        coordinator.attachRenderer(Any()) { started ->
            startedByReplacementRenderer += started
        }

        assertEquals(
            listOf(request),
            startedByFirstRenderer
        )

        assertEquals(
            listOf(request),
            startedByReplacementRenderer
        )
    }

    private fun request(
        packageName: String,
        notificationKey: String
    ): HaloEffectRequest =
        HaloEffectRequest(
            packageName = packageName,
            notificationKey = notificationKey,
            config = HaloConfig(),
            paletteColors = null
        )
}
