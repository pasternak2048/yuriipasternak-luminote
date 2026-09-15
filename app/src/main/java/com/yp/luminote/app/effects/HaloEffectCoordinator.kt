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
 * renderer is supplied when requests are enqueued.
 *
 * All methods are expected to be called from the main thread.
 */
internal class HaloEffectCoordinator {

    private val pendingRequests = ArrayDeque<HaloEffectRequest>()

    private var activeRequest: HaloEffectRequest? = null
    private var onRequestStarted: ((HaloEffectRequest) -> Unit)? = null

    fun enqueue(request: HaloEffectRequest) {
        Log.d(
            TAG,
            "enqueue package=${request.packageName}, " +
                    "active=${activeRequest?.packageName}, " +
                    "pending=${pendingRequests.size}"
        )

        val active = activeRequest

        if (active?.packageName == request.packageName) {
            Log.d(
                TAG,
                "coalesced active package=${request.packageName}, " +
                        "notificationKey=${request.notificationKey}"
            )
            return
        }

        if (
            pendingRequests.any {
                it.packageName == request.packageName
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
            start(request)
            return
        }

        pendingRequests.addLast(request)

        Log.d(
            TAG,
            "queued package=${request.packageName}, " +
                    "pending=${pendingRequests.size}"
        )
    }

    fun onRequestCompleted(request: HaloEffectRequest) {
        if (activeRequest !== request) {
            Log.d(
                TAG,
                "Ignoring stale completion: package=${request.packageName}, " +
                        "active=${activeRequest?.packageName}"
            )
            return
        }

        Log.d(
            TAG,
            "completed active=${request.packageName}, pending=${pendingRequests.size}"
        )

        activeRequest = null
        startNext()
    }

    fun clear() {
        pendingRequests.clear()
        activeRequest = null
        onRequestStarted = null
    }

    fun updateRenderer(onRequestStarted: (HaloEffectRequest) -> Unit) {
        this.onRequestStarted = onRequestStarted
    }

    private fun startNext() {
        val next = pendingRequests.pollFirst() ?: return
        start(next)
    }

    private fun start(request: HaloEffectRequest) {
        activeRequest = request

        Log.d(
            TAG,
            "start package=${request.packageName}, pending=${pendingRequests.size}"
        )

        val renderer = onRequestStarted

        if (renderer == null) {
            Log.w(
                TAG,
                "No renderer available for package=${request.packageName}"
            )
            activeRequest = null
            startNext()
            return
        }

        renderer(request)
    }

    private companion object {
        const val TAG = "HaloCoordinator"
    }
}