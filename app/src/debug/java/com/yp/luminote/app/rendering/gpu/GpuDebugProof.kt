package com.yp.luminote.app.rendering.gpu

import android.util.Log
import com.yp.luminote.app.BuildConfig

/** Debug source-set only: neither witness shader code nor Log calls are packaged in release. */
internal object GpuDebugProof {
    const val COMPILED_IN = true
    const val TAG = "LuminoteGpuProof"
    const val COMPOSITE_TAG = "LuminoteGpuProofComposite"

    fun enabled(): Boolean = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)
    fun compositeEnabled(witnessEnabled: Boolean = enabled()): Boolean =
        gpuDebugCompositeEnabled(witnessEnabled, Log.isLoggable(COMPOSITE_TAG, Log.DEBUG))
    fun log(message: String) = Log.d(TAG, message)
}

internal fun gpuDebugCompositeEnabled(witnessEnabled: Boolean, compositeTagLoggable: Boolean): Boolean =
    witnessEnabled && compositeTagLoggable
