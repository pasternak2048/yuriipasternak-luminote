package com.yp.luminote.app.overlay.coordination

import com.yp.luminote.app.effects.model.HaloRenderMode
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Stable FIFO lease; retries never allocate another delivery. */
internal data class HaloEffectDelivery(val request: HaloEffectRequest, val lease: Long)
internal enum class HaloEffectRoute { ACCESSIBILITY, OVERLAY }
internal class HaloEffectAttempt(
    val delivery: HaloEffectDelivery,
    val lease: Long,
    val segment: Int,
    val route: HaloEffectRoute
) { internal var started = false }
internal enum class HaloEffectAttemptOutcome { Started, Completed, RetryableBeforeStart, TerminalBeforeStart, AbandonedAfterStart, Cancelled }

/** Main-thread FIFO owner. An attempt callback may mutate state only while it owns [activeAttempt]. */
internal class HaloEffectCoordinator(private val nowMillis: () -> Long = ::monotonicMillis) {
    private val pendingRequests = ArrayDeque<HaloEffectRequest>()
    private val expirySuspendedRequests = Collections.newSetFromMap(IdentityHashMap<HaloEffectRequest, Boolean>())
    private var activeDelivery: HaloEffectDelivery? = null
    private var activeAttempt: HaloEffectAttempt? = null
    private var nextDeliveryLease = 0L
    private var nextAttemptLease = 0L
    private var rendererOwner: Any? = null
    private var onAttemptStarted: ((HaloEffectAttempt) -> Unit)? = null
    private var legacyRenderer = false

    fun enqueue(request: HaloEffectRequest) {
        discardExpiredRequests()
        val active = activeDelivery?.request
        if (active?.packageName == request.packageName || pendingRequests.any { it.packageName == request.packageName }) return
        if (active == null) { start(request); return }
        if (pendingRequests.size >= MAX_PENDING_REQUESTS) return
        pendingRequests.addLast(request)
        if (active.isReminder() && !request.isReminder()) expirySuspendedRequests += request
    }

    /** Registers a lease before synchronously invoking the host. */
    fun attachAttemptRenderer(owner: Any, onAttemptStarted: (HaloEffectAttempt) -> Unit) {
        rendererOwner = owner
        legacyRenderer = false
        this.onAttemptStarted = onAttemptStarted
        startPendingIfIdle()
    }

    /** Compatibility surface for existing pure FIFO callers. */
    fun attachRenderer(owner: Any, onRequestStarted: (HaloEffectDelivery) -> Unit) {
        attachAttemptRenderer(owner) { onRequestStarted(it.delivery) }
        legacyRenderer = true
    }

    fun detachRenderer(owner: Any, terminalActiveAttempt: Boolean = false) {
        if (rendererOwner !== owner) return
        rendererOwner = null
        onAttemptStarted = null
        activeAttempt?.let { attempt ->
            activeAttempt = null
            if (terminalActiveAttempt) {
                // Terminal host loss ends this delivery, including the normal
                // reminder-held FIFO rebase performed by finishActive.
                finishActive()
            } else if (!attempt.started) {
                activeDelivery?.let { pendingRequests.addFirst(it.request) }
                activeDelivery = null
            } else finishActive()
        }
    }

    /** The start/failure race linearizes here: first accepted event wins. */
    fun onAttemptOutcome(attempt: HaloEffectAttempt, outcome: HaloEffectAttemptOutcome) {
        if (activeAttempt !== attempt || activeDelivery !== attempt.delivery) return
        when (outcome) {
            HaloEffectAttemptOutcome.Started -> attempt.started = true
            HaloEffectAttemptOutcome.Completed -> if (attempt.started) finishSegmentOrDelivery(attempt)
            HaloEffectAttemptOutcome.RetryableBeforeStart -> if (!attempt.started && attempt.route == HaloEffectRoute.ACCESSIBILITY) fallback(attempt)
            HaloEffectAttemptOutcome.TerminalBeforeStart -> if (!attempt.started) finishActive()
            HaloEffectAttemptOutcome.AbandonedAfterStart -> if (attempt.started) finishActive()
            HaloEffectAttemptOutcome.Cancelled -> { activeAttempt = null; activeDelivery = null }
        }
    }

    /** Existing callers represent a renderer that did start and then completed. */
    fun onRequestCompleted(delivery: HaloEffectDelivery) {
        val attempt = activeAttempt ?: return
        if (attempt.delivery !== delivery) return
        if (legacyRenderer) { finishActive(); return }
        onAttemptOutcome(attempt, HaloEffectAttemptOutcome.Started)
        onAttemptOutcome(attempt, HaloEffectAttemptOutcome.Completed)
    }

    /** Must run before host cancellation so teardown callbacks are stale. */
    fun clear() { pendingRequests.clear(); expirySuspendedRequests.clear(); activeAttempt = null; activeDelivery = null; rendererOwner = null; onAttemptStarted = null; legacyRenderer = false }
    fun isBusy(): Boolean = activeDelivery != null || pendingRequests.isNotEmpty()

    private fun start(request: HaloEffectRequest) {
        if (activeDelivery != null) { pendingRequests.addLast(request); return }
        if (onAttemptStarted == null) { pendingRequests.addFirst(request); return }
        activeDelivery = HaloEffectDelivery(request, ++nextDeliveryLease)
        dispatchAttempt(0, HaloEffectRoute.ACCESSIBILITY)
    }
    private fun dispatchAttempt(segment: Int, route: HaloEffectRoute) {
        val delivery = activeDelivery ?: return
        if (onAttemptStarted == null) return
        if (isExpired(delivery.request)) { finishActive(); return }
        HaloEffectAttempt(delivery, ++nextAttemptLease, segment, route).also { attempt ->
            activeAttempt = attempt
            onAttemptStarted?.invoke(attempt)
        }
    }
    private fun fallback(attempt: HaloEffectAttempt) {
        if (isExpired(attempt.delivery.request)) { finishActive(); return }
        activeAttempt = null // old host's synchronous teardown callbacks are stale
        dispatchAttempt(attempt.segment, HaloEffectRoute.OVERLAY)
    }
    private fun finishSegmentOrDelivery(attempt: HaloEffectAttempt) {
        val colors = attempt.delivery.request.reminderColors
        if (colors.isNotEmpty() && attempt.segment < colors.lastIndex) {
            activeAttempt = null
            dispatchAttempt(attempt.segment + 1, HaloEffectRoute.ACCESSIBILITY)
        } else finishActive()
    }
    private fun finishActive() {
        val reminder = activeDelivery?.request?.isReminder() == true
        activeAttempt = null; activeDelivery = null
        if (reminder) rebaseNormalRequestsHeldByReminder()
        startNext()
    }
    private fun startNext() { discardExpiredRequests(); if (activeDelivery == null) pendingRequests.pollFirst()?.let(::start) }
    private fun startPendingIfIdle() { if (activeDelivery == null && pendingRequests.isNotEmpty()) startNext() }
    private fun discardExpiredRequests() { while (pendingRequests.firstOrNull()?.let(::isExpired) == true) pendingRequests.removeFirst() }
    private fun isExpired(request: HaloEffectRequest) = request !in expirySuspendedRequests && nowMillis() - request.enqueuedAt >= MAX_REQUEST_AGE_MS
    private fun rebaseNormalRequestsHeldByReminder() { val now = nowMillis(); expirySuspendedRequests.forEach { it.enqueuedAt = now }; expirySuspendedRequests.clear() }
    private fun HaloEffectRequest.isReminder() = config.renderMode == HaloRenderMode.LIGHT_IMPULSE && reminderColors.isNotEmpty()
    private companion object { const val MAX_PENDING_REQUESTS = 8; const val MAX_REQUEST_AGE_MS = 15_000L }
}
