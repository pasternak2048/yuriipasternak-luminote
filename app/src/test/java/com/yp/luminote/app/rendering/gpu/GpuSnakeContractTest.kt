package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.definitions.SnakeAnimation
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import org.junit.Assert.*
import org.junit.Test

class GpuSnakeContractTest {
    private fun production() = HaloEffectVisualPlanMapper.map(HaloConfig(motion = SnakeAnimation.id)).emitters.single()

    @Test fun `normal production Snake and baseline admitted but unrelated demo stacks rejected`() {
        val emitter = production()
        assertTrue(GpuSnakeCapability.supports(emitter))
        assertEquals(GpuSnakeCapability.productionModifiers,emitter.modifiers)
        assertTrue(GpuSnakeCapability.supports(emitter.copy(modifiers = emptyList())))
        assertTrue(GpuSnakeCapability.supports(emitter.copy(modifiers = listOf(VisualModifier.Opacity(.4f))+emitter.modifiers)))
        assertFalse(GpuSnakeCapability.supports(emitter.copy(modifiers = listOf(VisualModifier.Blur(10f)))))
        assertFalse(GpuSnakeCapability.supports(emitter.copy(luminousSegmentAppearance = LuminousSegmentAppearance.DEFAULT)))
        assertFalse(GpuSnakeCapability.supports(emitter.copy(operation = PrimaryVisualOperation.FullContourPulse)))
        assertFalse(GpuSnakeCapability.supports(emitter.copy(operation = PrimaryVisualOperation.TravelingLuminousSegment(.51f))))
    }

    @Test fun `compiler retains host phase length and applies opacity and pulse exactly once`() {
        val emitter = production().copy(modifiers = listOf(VisualModifier.Opacity(.5f))+GpuSnakeCapability.productionModifiers)
        val frame = RenderFrame().apply { effectPhase = .94f; animationProgress = 1f; frameTimeNanos = 1_000_000_000L }
        val command = FrameCommandCompiler.compile(VisualPlanSpec(listOf(emitter)),frame,FrameCommandBuffer())[0]
        assertEquals(.94f,command.phase,0f)
        assertEquals(.18f,(command.operation as PrimaryVisualOperation.TravelingLuminousSegment).lengthFraction,0f)
        assertTrue(kotlin.math.abs((255f*emitter.appearance.intensity*.5f*intensityPulseMultiplier(emitter.modifiers,frame.frameTimeNanos)).toInt()-command.alpha) <= 1)
        assertEquals(.08f,GpuSnakeSourceProfile.relative(.02f,.94f),.00001f)
        assertNull(GpuSnakeCapability.unsupportedReason(RenderIntent.PRODUCTION,RenderMode.ANIMATION,1,command))
        assertNull(GpuSnakeCapability.unsupportedReason(RenderIntent.DEBUG_GPU_SNAKE,RenderMode.ANIMATION,1,command))
    }

    @Test fun `continuous tail body head retain approved endpoints monotonic energy and seam`() {
        assertEquals(.02f,GpuSnakeSourceProfile.emission(0f),.000001f)
        assertEquals(1f,GpuSnakeSourceProfile.emission(1f),.000001f)
        assertEquals(.76f,GpuSnakeSourceProfile.width(0f),.000001f)
        assertEquals(1.12f,GpuSnakeSourceProfile.width(1f),.000001f)
        var last = 0f
        for (i in 0..1000) {
            val value = GpuSnakeSourceProfile.emission(i/1000f)
            assertTrue(value >= last); last = value
        }
        assertEquals(GpuSnakeSourceProfile.emission(.74f-.00001f),GpuSnakeSourceProfile.emission(.74f+.00001f),.00001f)
        assertEquals(0f,GpuSnakeSourceProfile.interpolateProgress(.999f,.001f,.5f),.00001f)
        // A byte-carry boundary must interpolate decoded 16-bit progress, not individual channels.
        assertEquals(256f/65535f,GpuSnakeSourceProfile.interpolateProgress(255f/65535f,257f/65535f,.5f),.000001f)
    }
}
