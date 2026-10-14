package com.yp.luminote.app.rendering.gpu

/** Release source-set implementation keeps diagnostic shader text and logging out of the APK. */
internal object GpuDebugProof {
    const val COMPILED_IN = false

    fun enabled(): Boolean = false
    fun log(message: String) = Unit
}
