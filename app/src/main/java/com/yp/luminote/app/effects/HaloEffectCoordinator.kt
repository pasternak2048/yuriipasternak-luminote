package com.yp.luminote.app.effects

import android.util.Log
import java.util.ArrayDeque

/**
 * Serializes transient notification Halo effects.
 *
 * Exactly one request can be active at a time. Additional requests wait in
 * FIFO order until the active animation reports natural completion.
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
            "enqueue package=${request.packageName}, " +
                    "active=${activeRequest?.packageName}, " +
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
                "coalesced active package=${request.packageName}, " +
                        "notificationKey=${request.notificationKey}"
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
                "coalesced pending package=${request.packageName}, " +
                        "notificationKey=${request.notificationKey}"
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
            "queued package=${request.packageName}, " +
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
                        "active=${activeRequest?.packageName}"
            )

            return
        }

        Log.d(
            TAG,
            "completed active=${request.packageName}, " +
                    "pending=${pendingRequests.size}"
        )

        activeRequest = null

        startNext()
    }

    fun clear() {
        pendingRequests.clear()

        activeRequest = null
        rendererOwner = null
        onRequestStarted = null
    }

    fun attachRenderer(
        owner: Any,
        onRequestStarted: (HaloEffectRequest) -> Unit
    ) {
        rendererOwner = owner
        this.onRequestStarted =
            onRequestStarted

        Log.d(
            TAG,
            "renderer attached: owner=${System.identityHashCode(owner)}"
        )
    }

    fun detachRenderer(
        owner: Any
    ) {
        if (rendererOwner !== owner) {
            Log.d(
                TAG,
                "Ignoring renderer detach from stale owner=" +
                        System.identityHashCode(owner)
            )

            return
        }

        Log.d(
            TAG,
            "renderer detached: owner=${System.identityHashCode(owner)}"
        )

        rendererOwner = null
        onRequestStarted = null
    }

    private fun startNext() {
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
        val renderer =
            onRequestStarted

        if (renderer == null) {
            /*
             * Do not discard the request.
             *
             * The application overlay service may currently be between
             * instances. Put the request back at the front and wait for a
             * renderer to attach.
             */
            pendingRequests.addFirst(
                request
            )

            Log.w(
                TAG,
                "No renderer available; waiting: " +
                        "package=${request.packageName}, " +
                        "pending=${pendingRequests.size}"
            )

            return
        }

        activeRequest =
            request

        Log.d(
            TAG,
            "start package=${request.packageName}, " +
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

        startNext()
    }

    private companion object {
        const val TAG =
            "HaloCoordinator"
    }
}