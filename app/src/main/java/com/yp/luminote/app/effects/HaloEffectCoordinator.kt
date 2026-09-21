package com.yp.luminote.app.effects

import android.util.Log
import java.util.ArrayDeque

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

    private var activeRequest:
            HaloEffectRequest? = null

    private var rendererOwner:
            Any? = null

    private var onRequestStarted:
            ((HaloEffectRequest) -> Unit)? = null

    fun enqueue(
        request: HaloEffectRequest
    ) {
        discardExpiredRequests()

        Log.d(
            TAG,
            "enqueue " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "activeKey=${activeRequest?.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        val active =
            activeRequest

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

        Log.d(
            TAG,
            "queued " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )
    }

    fun onRequestCompleted(
        request: HaloEffectRequest
    ) {
        if (
            activeRequest !==
            request
        ) {
            Log.d(
                TAG,
                "Ignoring stale completion: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}, " +
                        "activePackage=${activeRequest?.packageName}, " +
                        "activeKey=${activeRequest?.notificationKey}"
            )

            return
        }

        Log.d(
            TAG,
            "completed " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        activeRequest =
            null

        startNext()
    }

    fun clear() {
        pendingRequests.clear()

        activeRequest =
            null

        rendererOwner =
            null

        onRequestStarted =
            null
    }

    fun attachRenderer(
        owner: Any,
        onRequestStarted: (HaloEffectRequest) -> Unit
    ) {
        rendererOwner =
            owner

        this.onRequestStarted =
            onRequestStarted

        Log.d(
            TAG,
            "renderer attached: " +
                    "owner=${System.identityHashCode(owner)}, " +
                    "activeKey=${activeRequest?.notificationKey}, " +
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
                    "activeKey=${activeRequest?.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        rendererOwner =
            null

        onRequestStarted =
            null

        activeRequest?.let { request ->
            activeRequest =
                null

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
            activeRequest != null
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
            activeRequest != null
        ) {
            pendingRequests.addLast(
                request
            )

            Log.d(
                TAG,
                "deferred start because another request is active: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}, " +
                        "activeKey=${activeRequest?.notificationKey}, " +
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

        activeRequest =
            request

        Log.d(
            TAG,
            "start " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}, " +
                    "pending=${pendingRequests.size}"
        )

        renderer(
            request
        )
    }

    private fun startPendingIfIdle() {
        if (
            activeRequest != null ||
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
        android.os.SystemClock.elapsedRealtime() -
                request.enqueuedAt >=
                MAX_REQUEST_AGE_MS

    private companion object {
        const val TAG =
            "HaloCoordinator"

        const val MAX_PENDING_REQUESTS =
            8

        const val MAX_REQUEST_AGE_MS =
            15_000L
    }
}
