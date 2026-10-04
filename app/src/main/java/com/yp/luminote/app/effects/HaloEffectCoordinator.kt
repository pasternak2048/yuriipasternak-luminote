package com.yp.luminote.app.effects

import android.util.Log
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** A renderer attempt. Identity, not request equality, owns completion. */
internal data class HaloEffectDelivery(
    val request: HaloEffectRequest,
    val lease: Long
)

/**
 * Serializes transient notification Halo effects.
 *
 * Exactly one request can be active at a time. Additional requests wait in
 * FIFO order until the active animation reports natural completion.
 *
 * While an effect from an application is active or waiting, further events
 * from that application are coalesced. This prevents group chats from
 * replaying a long burst of already-seen effects.
 *
 * The coordinator itself does not own a renderer. The latest available
 * renderer is supplied by a HaloOverlayService instance.
 *
 * Renderer ownership is tracked explicitly so a destroyed service cannot
 * accidentally detach a newer renderer.
 *
 * All methods are expected to be called from the main thread.
 */
internal class HaloEffectCoordinator {

    private val pendingRequests =
        ArrayDeque<HaloEffectRequest>()

    /** Normal requests held by the active multi-colour reminder do not age out mid-cycle. */
    private val expirySuspendedRequests =
        Collections.newSetFromMap(IdentityHashMap<HaloEffectRequest, Boolean>())

    private var activeDelivery:
            HaloEffectDelivery? = null

    private var nextDeliveryLease =
        0L

    private var rendererOwner:
            Any? = null

    private var onRequestStarted:
            ((HaloEffectDelivery) -> Unit)? = null

    fun enqueue(
        request: HaloEffectRequest
    ) {
        discardExpiredRequests()

        Log.d(
            TAG,
            "enqueue " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "activeKey=${activeDelivery?.request?.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        val active =
            activeDelivery?.request

        if (
            active?.packageName ==
            request.packageName
        ) {
            Log.d(
                TAG,
                "coalesced active application " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}"
            )

            return
        }

        if (
            pendingRequests.any {
                it.packageName ==
                        request.packageName
            }
        ) {
            Log.d(
                TAG,
                "coalesced pending application " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}"
            )

            return
        }

        if (active == null) {
            start(
                request
            )

            return
        }

        if (
            pendingRequests.size >=
            MAX_PENDING_REQUESTS
        ) {
            Log.w(
                TAG,
                "Dropped effect because the queue is full: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}"
            )

            return
        }

        pendingRequests.addLast(
            request
        )

        if (active?.isReminder() == true && !request.isReminder()) {
            expirySuspendedRequests += request
        }

        Log.d(
            TAG,
            "queued " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )
    }

    fun onRequestCompleted(
        delivery: HaloEffectDelivery
    ) {
        if (
            activeDelivery !== delivery
        ) {
            Log.d(
                TAG,
                "Ignoring stale completion: " +
                        "package=${delivery.request.packageName}, " +
                        "key=${delivery.request.notificationKey}, " +
                        "activePackage=${activeDelivery?.request?.packageName}, " +
                        "activeKey=${activeDelivery?.request?.notificationKey}"
            )

            return
        }

        Log.d(
            TAG,
            "completed " +
                    "package=${delivery.request.packageName}, " +
                    "key=${delivery.request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        val completedReminder = activeDelivery?.request?.isReminder() == true
        activeDelivery = null

        if (completedReminder) {
            rebaseNormalRequestsHeldByReminder()
        }

        startNext()
    }

    fun clear() {
        pendingRequests.clear()
        expirySuspendedRequests.clear()

        activeDelivery =
            null

        rendererOwner =
            null

        onRequestStarted =
            null
    }

    /** Main-thread ownership query used to defer calibration without disturbing FIFO playback. */
    fun isBusy(): Boolean = activeDelivery != null || pendingRequests.isNotEmpty()

    fun attachRenderer(
        owner: Any,
        onRequestStarted: (HaloEffectDelivery) -> Unit
    ) {
        rendererOwner =
            owner

        this.onRequestStarted =
            onRequestStarted

        Log.d(
            TAG,
            "renderer attached: " +
                    "owner=${System.identityHashCode(owner)}, " +
                    "activeKey=${activeDelivery?.request?.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        startPendingIfIdle()
    }

    fun detachRenderer(
        owner: Any
    ) {
        if (
            rendererOwner !==
            owner
        ) {
            Log.d(
                TAG,
                "Ignoring renderer detach from stale owner=" +
                        System.identityHashCode(owner)
            )

            return
        }

        Log.d(
            TAG,
            "renderer detached: " +
                    "owner=${System.identityHashCode(owner)}, " +
                    "activeKey=${activeDelivery?.request?.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        rendererOwner =
            null

        onRequestStarted =
            null

        activeDelivery?.let { delivery ->
            activeDelivery =
                null

            val request = delivery.request

            if (isExpired(request)) {
                Log.d(
                    TAG,
                    "Dropped expired active effect after renderer detach: " +
                            "package=${request.packageName}, " +
                            "key=${request.notificationKey}"
                )
            } else {
                pendingRequests.addFirst(
                    request
                )

                Log.d(
                    TAG,
                    "Requeued active effect after renderer detach: " +
                            "package=${request.packageName}, " +
                            "key=${request.notificationKey}"
                )
            }
        }
    }

    private fun startNext() {
        discardExpiredRequests()

        if (
            activeDelivery != null
        ) {
            return
        }

        val next =
            pendingRequests.pollFirst()
                ?: return

        start(
            next
        )
    }

    private fun start(
        request: HaloEffectRequest
    ) {
        if (
            activeDelivery != null
        ) {
            pendingRequests.addLast(
                request
            )

            Log.d(
                TAG,
                "deferred start because another request is active: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}, " +
                    "activeKey=${activeDelivery?.request?.notificationKey}, " +
                        "pending=${pendingRequests.size}"
            )

            return
        }

        val renderer =
            onRequestStarted

        if (renderer == null) {
            pendingRequests.addFirst(
                request
            )

            Log.w(
                TAG,
                "No renderer available; waiting: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}, " +
                        "pending=${pendingRequests.size}"
            )

            return
        }

        val delivery =
            HaloEffectDelivery(
                request = request,
                lease = ++nextDeliveryLease
            )

        activeDelivery =
            delivery

        Log.d(
            TAG,
            "start " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        renderer(delivery)
    }

    private fun startPendingIfIdle() {
        if (
            activeDelivery != null ||
            pendingRequests.isEmpty()
        ) {
            return
        }

        Log.d(
            TAG,
            "renderer available; resuming queued playback " +
                    "pending=${pendingRequests.size}"
        )

        startNext()
    }

    private fun discardExpiredRequests() {
        while (
            pendingRequests.firstOrNull()?.let(::isExpired) ==
            true
        ) {
            val expired =
                pendingRequests.removeFirst()

            Log.d(
                TAG,
                "Dropped expired queued effect: " +
                        "package=${expired.packageName}, " +
                        "key=${expired.notificationKey}"
            )
        }
    }

    private fun isExpired(
        request: HaloEffectRequest
    ): Boolean =
        request !in expirySuspendedRequests &&
        android.os.SystemClock.elapsedRealtime() -
                request.enqueuedAt >=
                MAX_REQUEST_AGE_MS

    private fun rebaseNormalRequestsHeldByReminder() {
        val now = android.os.SystemClock.elapsedRealtime()
        expirySuspendedRequests.forEach { request ->
            request.enqueuedAt = now
        }
        expirySuspendedRequests.clear()
    }

    private fun HaloEffectRequest.isReminder(): Boolean =
        config.renderMode == HaloRenderMode.LIGHT_IMPULSE &&
                reminderColors.isNotEmpty()

    private companion object {
        const val TAG =
            "HaloCoordinator"

        const val MAX_PENDING_REQUESTS =
            8

        const val MAX_REQUEST_AGE_MS =
            15_000L
    }
}
