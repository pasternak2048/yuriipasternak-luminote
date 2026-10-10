package com.yp.luminote.app.animation

/** Backend-neutral time and ownership plans for the Force Blade family. */
internal enum class BladeState { EXTENDING, SEALED, HOLDING, RETRACTING, FINISHED }

internal class MutableBladePhase(
    var state: BladeState = BladeState.EXTENDING,
    var localPhase: Float = 0f,
    var extent: Float = 0f
)

internal object BladeLifecycle {
    fun azure(phase: Float): MutableBladePhase = azure(phase, MutableBladePhase())
    fun crimson(phase: Float): MutableBladePhase = crimson(phase, MutableBladePhase())
    fun azure(phase: Float, out: MutableBladePhase): MutableBladePhase = phaseFor(phase, 0.55f, 0.63f, 0.82f, out)
    fun crimson(phase: Float, out: MutableBladePhase): MutableBladePhase = phaseFor(phase, 0.47f, 0.55f, 0.80f, out)

    private fun phaseFor(phase: Float, extendEnd: Float, sealEnd: Float, holdEnd: Float, out: MutableBladePhase): MutableBladePhase {
        val t = phase.coerceIn(0f, 1f)
        return when {
            t < extendEnd -> out.apply { state = BladeState.EXTENDING; localPhase = t / extendEnd; extent = 0.02f + 0.98f * (1f - (1f - localPhase) * (1f - localPhase)) }
            t < sealEnd -> out.apply { state = BladeState.SEALED; localPhase = (t - extendEnd) / (sealEnd - extendEnd); extent = 1f }
            t < holdEnd -> out.apply { state = BladeState.HOLDING; localPhase = (t - sealEnd) / (holdEnd - sealEnd); extent = 1f }
            t < 0.985f -> out.apply { state = BladeState.RETRACTING; localPhase = (t - holdEnd) / (0.985f - holdEnd); extent = 0f }
            else -> out.apply { state = BladeState.FINISHED; localPhase = 1f; extent = 0f }
        }
    }
}

internal data class RetractionSegment(val start: Float, val length: Float, val head: Float)
internal class MutableRetractionSegment(var start: Float = 0f, var length: Float = 0f, var head: Float = 0f)

internal object BladeRetraction {
    fun remainingSegment(origin: Float, direction: Int, progress: Float, maximumExtent: Float = 1f): RetractionSegment {
        val segment = MutableRetractionSegment()
        remainingSegment(origin, direction, progress, maximumExtent, segment)
        return RetractionSegment(segment.start, segment.length, segment.head)
    }
    fun remainingSegment(origin: Float, direction: Int, progress: Float, maximumExtent: Float, out: MutableRetractionSegment): MutableRetractionSegment {
        val consumed = progress.coerceIn(0f, 1f) * maximumExtent
        out.length = maximumExtent - consumed
        out.head = origin + direction * consumed
        out.start = if (direction > 0) out.head else origin
        return out
    }
}

internal object DuelRetraction {
    fun remainingInterval(origin: Float, collisionBoundary: Float, progress: Float, directionTowardOrigin: Int, out: MutableRetractionSegment): MutableRetractionSegment {
        val length = kotlin.math.abs(collisionBoundary - origin)
        val safeProgress = progress.coerceIn(0f, 1f)
        out.length = length * (1f - safeProgress)
        out.head = collisionBoundary + directionTowardOrigin * length * safeProgress
        out.start = if (directionTowardOrigin < 0) origin else out.head
        return out
    }
}

internal enum class DuelState { CLASHING, STRUGGLING, OVERLOADING, RETRACTING }
internal class MutableDuelPhase(var state: DuelState = DuelState.CLASHING, var localPhase: Float = 0f, var azureShare: Float = 0.5f, var energy: Float = 1f, var clashPhase: Float = 0f)

internal object DuelLifecycle {
    fun at(phase: Float): MutableDuelPhase = at(phase, MutableDuelPhase())
    fun at(phase: Float, out: MutableDuelPhase): MutableDuelPhase {
        val t = phase.coerceIn(0f, 1f)
        return when {
            t < 0.38f -> out.apply { state = DuelState.CLASHING; localPhase = (t - 0.30f) / 0.08f; azureShare = 0.50f; energy = 1f; clashPhase = localPhase }
            t < 0.76f -> { val p = (t - 0.38f) / 0.38f; out.apply { state = DuelState.STRUGGLING; localPhase = p; azureShare = azureShare(p); energy = 0.94f; clashPhase = p } }
            t < 0.86f -> out.apply { state = DuelState.OVERLOADING; localPhase = (t - 0.76f) / 0.10f; azureShare = 0.50f; energy = 1.15f; clashPhase = 0f }
            else -> out.apply { state = DuelState.RETRACTING; localPhase = (t - 0.86f) / 0.14f; azureShare = 0.50f; energy = 1f; clashPhase = 1f }
        }
    }
    fun azureShare(progress: Float): Float {
        val scaled = progress.coerceIn(0f, 1f) * 4f
        val index = scaled.toInt().coerceAtMost(3)
        val fraction = scaled - index
        return AZURE_SHARES[index] + (AZURE_SHARES[index + 1] - AZURE_SHARES[index]) * (fraction * fraction * (3f - 2f * fraction))
    }
    private val AZURE_SHARES = floatArrayOf(0.50f, 0.60f, 0.45f, 0.55f, 0.50f)
}

internal class MutableDuelBoundaries(var left: Float = 0f, var right: Float = 0f)
internal data class DuelBoundaries(val left: Float, val right: Float) {
    companion object {
        fun fromAzureShare(azureShare: Float): DuelBoundaries { val half = azureShare.coerceIn(0f, 1f) / 2f; return DuelBoundaries(0.08f - half, 0.08f + half) }
        fun fromAzureShare(azureShare: Float, out: MutableDuelBoundaries) { val half = azureShare.coerceIn(0f, 1f) / 2f; out.left = 0.08f - half; out.right = 0.08f + half }
    }
}
