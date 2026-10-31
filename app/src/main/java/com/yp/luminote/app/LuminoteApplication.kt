package com.yp.luminote.app

import android.app.Application
import com.yp.luminote.app.diagnostics.CrashDelegator
import com.yp.luminote.app.diagnostics.DiagnosticsRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Installs process-wide, best-effort local diagnostics before any service can start. */
class LuminoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.yp.luminote.app.overlay.service.HaloEffectController.initialize(this)
        val diagnostics = DiagnosticsRepository.get(this)
        diagnostics.inspectPreviousExit()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = CrashDelegator(
            persist = diagnostics::recordCrash,
            flush = { diagnostics.flush(CRASH_FLUSH_TIMEOUT_MS) },
            delegate = { thread, error ->
                if (previous != null) previous.uncaughtException(thread, error) else throw error
            }
        )
        Thread.setDefaultUncaughtExceptionHandler(handler::handle)
    }

    private companion object {
        const val CRASH_FLUSH_TIMEOUT_MS = 150L
    }
}
