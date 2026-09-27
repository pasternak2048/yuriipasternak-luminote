package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class HaloEffectCoordinatorTest {

    @Test
    fun `coalesces events from an application while its effect is active`() {
        val coordinator =
            HaloEffectCoordinator()

        val started =
            mutableListOf<HaloEffectDelivery>()

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
            started.map(HaloEffectDelivery::request)
        )
    }

    @Test
    fun `resumes an active effect after its renderer is recreated`() {
        val coordinator =
            HaloEffectCoordinator()

        val firstOwner =
            Any()

        val startedByFirstRenderer =
            mutableListOf<HaloEffectDelivery>()

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
            mutableListOf<HaloEffectDelivery>()

        coordinator.attachRenderer(Any()) { started ->
            startedByReplacementRenderer += started
        }

        assertEquals(
            listOf(request),
            startedByFirstRenderer.map(HaloEffectDelivery::request)
        )

        assertEquals(
            listOf(request),
            startedByReplacementRenderer.map(HaloEffectDelivery::request)
        )
    }

    @Test
    fun `stale completion from detached delivery cannot complete its requeued request`() {
        val coordinator = HaloEffectCoordinator()
        val request = request("com.example.chat", "message")
        val firstOwner = Any()
        val deliveries = mutableListOf<HaloEffectDelivery>()

        coordinator.attachRenderer(firstOwner) { deliveries += it }
        coordinator.enqueue(request)
        val stale = deliveries.single()
        coordinator.detachRenderer(firstOwner)
        coordinator.attachRenderer(Any()) { deliveries += it }
        val replacement = deliveries.last()

        coordinator.onRequestCompleted(stale)
        assertEquals(replacement, deliveries.last())

        coordinator.onRequestCompleted(replacement)
    }

    @Test
    fun `only replacement delivery completion advances queued fifo request`() {
        val coordinator = HaloEffectCoordinator()
        val owner = Any()
        val started = mutableListOf<HaloEffectDelivery>()
        val first = request("com.example.first", "first")
        val next = request("com.example.next", "next")
        coordinator.attachRenderer(owner) { started += it }
        coordinator.enqueue(first)
        coordinator.enqueue(next)
        val stale = started.single()
        coordinator.detachRenderer(owner)
        coordinator.attachRenderer(Any()) { started += it }
        val replacement = started.last()

        coordinator.onRequestCompleted(stale)
        assertEquals(listOf(first, first), started.map(HaloEffectDelivery::request))

        coordinator.onRequestCompleted(replacement)
        assertEquals(listOf(first, first, next), started.map(HaloEffectDelivery::request))
    }

    @Test
    fun `limits the number of queued effects from different applications`() {
        val coordinator =
            HaloEffectCoordinator()

        val started =
            mutableListOf<HaloEffectDelivery>()

        coordinator.attachRenderer(Any()) { request ->
            started += request
        }

        repeat(11) { index ->
            coordinator.enqueue(
                request(
                    packageName = "com.example.app$index",
                    notificationKey = "notification$index"
                )
            )
        }

        repeat(8) {
            coordinator.onRequestCompleted(
                started.last()
            )
        }

        assertEquals(
            9,
            started.size
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
