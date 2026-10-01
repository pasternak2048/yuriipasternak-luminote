package com.yp.luminote.app.effects

import java.util.UUID

/** Process-wide lease prevents a stale screen/service command from owning calibration preview. */
internal object CalibrationPreviewSession {
    private var token: String? = null
    private var generation = 0L

    /** Makes [candidate] the foreground calibration owner and returns its new generation. */
    @Synchronized fun activate(candidate: String): Long {
        token = candidate
        generation += 1
        return generation
    }

    /** Kept for older callers while they migrate to the token + generation protocol. */
    @Synchronized fun start(): String = UUID.randomUUID().toString().also(::activate)

    @Synchronized fun isCurrent(candidate: String?, candidateGeneration: Long): Boolean =
        candidate != null && candidate == token && candidateGeneration == generation

    /** Compare-and-clear for terminal commands: a stale generation cannot release a resumed session. */
    @Synchronized fun stop(candidate: String?, candidateGeneration: Long): Boolean =
        if (isCurrent(candidate, candidateGeneration)) {
            token = null
            true
        } else {
            false
        }

    @Synchronized fun isCurrent(candidate: String?): Boolean = candidate != null && candidate == token
    /** Compare-and-clear is atomic: an old screen's disposal cannot stop a newer session. */
    @Synchronized fun stop(candidate: String?): Boolean =
        if (candidate != null && candidate == token) { token = null; true } else false
}
