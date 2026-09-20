package com.yp.luminote.app.effects

import android.util.Log
import java.util.ArrayDeque

/**
 * Serializes transient notification Halo effects.
 *
 * Exactly one request can be active at a time. Additional requests wait in
 * FIFO order until the active animation reports natural completion.
 *
 * Requests are coalesced only when they represent the same concrete
 * notification key. Different notifications from the same application must
 * remain independent animation requests.
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
            active?.notificationKey ==
            request.notificationKey
        ) {
            Log.d(
                TAG,
                "coalesced active " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}"
            )

            return
        }

        if (
            pendingRequests.any {
                it.notificationKey ==
                        request.notificationKey
            }
        ) {
            Log.d(
                TAG,
                "coalesced pending " +
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
    }

    private fun startNext() {
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

    private companion object {
        const val TAG =
            "HaloCoordinator"
    }
}