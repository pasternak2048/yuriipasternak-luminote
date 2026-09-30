package com.yp.luminote.app.effects

import java.util.UUID

/** Process-wide lease prevents a stale screen/service command from owning calibration preview. */
internal object CalibrationPreviewSession {
    private var token: String? = null

    @Synchronized fun start(): String = UUID.randomUUID().toString().also { token = it }
    @Synchronized fun isCurrent(candidate: String?): Boolean = candidate != null && candidate == token
    /** Compare-and-clear is atomic: an old screen's disposal cannot stop a newer session. */
    @Synchronized fun stop(candidate: String?): Boolean =
        if (candidate != null && candidate == token) { token = null; true } else false
}
