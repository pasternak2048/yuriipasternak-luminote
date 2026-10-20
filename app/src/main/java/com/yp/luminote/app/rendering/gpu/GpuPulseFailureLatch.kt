package com.yp.luminote.app.rendering.gpu

/**
 * A stable pre-submit failure is safe to render through Canvas, but retrying its expensive GPU
 * setup on every VSYNC only obscures the original cause. The owner clears this latch whenever
 * the resource identity it keys changes (including surface recreation and a new debug plan).
 */
internal class GpuPulseFailureLatch<K> {
    private var entry: Entry<K>? = null

    fun failureFor(key: K): GpuPulsePreSubmitFailure? = entry?.takeIf { it.key == key }?.failure

    fun record(key: K, failure: GpuPulsePreSubmitFailure) {
        entry = Entry(key, failure)
    }

    fun clear() {
        entry = null
    }

    private data class Entry<K>(val key: K, val failure: GpuPulsePreSubmitFailure)
}

/** Keeps the original framework exception available through [Throwable.cause]. */
internal class GpuPulsePreSubmitFailure(
    val stage: String,
    val originalType: String,
    val originalMessage: String?,
    cause: RuntimeException
) : IllegalStateException(
    "GPU Pulse pre-submit failure at $stage: $originalType" +
        (originalMessage?.let { ": $it" } ?: ""),
    cause
) {
    val classification: String = "pre-submit-error:$stage:$originalType"

    companion object {
        fun at(stage: String, cause: RuntimeException) = GpuPulsePreSubmitFailure(
            stage = stage,
            originalType = cause.javaClass.simpleName,
            originalMessage = cause.message,
            cause = cause
        )
    }
}
