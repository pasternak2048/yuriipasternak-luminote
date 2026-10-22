package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.*
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import org.junit.Assert.*
import org.junit.Test

class GpuImpulseContractTest {
    private fun base() = HaloEffectVisualPlanMapper.map(HaloConfig(motion = ImpulseAnimation.id)).emitters.single().copy(modifiers = emptyList())

    @Test fun `only normal base admitted and reminder optics wrong routes rejected`() {
        val emitter = base()
        assertTrue(GpuImpulseCapability.supports(emitter))
        assertFalse(GpuImpulseCapability.supports(emitter.copy(modifierProfile = VisualModifierProfile.DEFAULT)))
        for (modifier in listOf(VisualModifier.Glow(7f), VisualModifier.Blur(2f), VisualModifier.IntensityPulse(.1f)))
            assertFalse(GpuImpulseCapability.supports(emitter.copy(modifiers = listOf(modifier))))
        val frame = RenderFrame().apply { effectPhase = .43f; animationProgress = .7f }
        val command = FrameCommandCompiler.compile(VisualPlanSpec(listOf(emitter)),frame,FrameCommandBuffer())[0]
        assertEquals(.43f, command.phase, 0f)
        assertNull(GpuImpulseCapability.unsupportedReason(RenderIntent.DEBUG_GPU_IMPULSE,RenderMode.ANIMATION,1,command,true))
        assertNull(GpuImpulseCapability.unsupportedReason(RenderIntent.PRODUCTION,RenderMode.ANIMATION,1,command,true))
        assertNotNull(GpuImpulseCapability.unsupportedReason(RenderIntent.DEBUG_GPU_IMPULSE,RenderMode.ANIMATION,1,command,false))
        assertNotNull(GpuImpulseCapability.unsupportedReason(RenderIntent.DEBUG_GPU_IMPULSE,RenderMode.ANIMATION,2,command,true))
    }

    @Test fun `uniform packet reuses plan and retains shared birth convergence terminal semantics`() {
        val packet = GpuImpulseSourceProfile()
        val storage = packet.plan
        val endpoints = LightImpulseEndpoints(.95f,.35f,4000f,6000f)
        for (phase in listOf(0f,.045f,.09f,.14f,.4f,.6f,.79999f,.8f,.86f,.92f,.99f,1f)) {
            packet.update(phase,10000f,endpoints)
            assertSame(storage,packet.plan)
            assertEquals(phase,packet.progress,0f)
            val shared=lightImpulseProductionDrawPlan(phase,10000f,4000f,6000f)
            assertEquals(shared.mode,packet.plan.mode)
            assertEquals(shared.forwardFieldLength,packet.plan.forwardFieldLength,0f)
            assertEquals(shared.reverseFieldLength,packet.plan.reverseFieldLength,0f)
            assertEquals(shared.sourceBloomRadius,packet.plan.sourceBloomRadius,0f)
            assertEquals(lightImpulsePassEnergy(phase),packet.energy)
        }
        assertEquals(0f,packet.energy.bottomBloom,0f)
        packet.update(Float.NaN,10000f,endpoints)
        assertEquals(1f,packet.progress,0f)
    }

    @Test fun `unequal branches share progress and compact beam length without changing mapper`() {
        val normal=HaloEffectVisualPlanMapper.map(HaloConfig(motion=ImpulseAnimation.id)).emitters.single()
        assertTrue("production optics retained by mapper",normal.modifiers.isNotEmpty())
        assertEquals(GpuImpulseCapability.productionModifiers, normal.modifiers)
        assertTrue("exact production optics admitted",GpuImpulseCapability.supports(normal))
        val packet=GpuImpulseSourceProfile()
        val endpoints=LightImpulseEndpoints(0f,.4f,4000f,6000f)
        packet.update(.6f,10000f,endpoints)
        assertEquals(LightImpulseDrawMode.OWNED_TRAVEL_FIELDS,packet.plan.mode)
        assertEquals(1650f,packet.plan.forwardFieldLength,.001f)
        assertEquals(1650f,packet.plan.reverseFieldLength,.001f)
        val travel=lightImpulseTravelProgress(packet.progress)
        assertEquals(travel,(endpoints.forwardDistance*travel)/endpoints.forwardDistance,.00001f)
        assertEquals(travel,(endpoints.reverseDistance*travel)/endpoints.reverseDistance,.00001f)
        packet.update(.92f,10000f,endpoints)
        assertEquals(LightImpulseDrawMode.BOTTOM_BLOOM,packet.plan.mode)
        assertEquals(0f,packet.plan.forwardFieldLength,0f)
        assertEquals(0f,packet.plan.reverseFieldLength,0f)
        assertEquals(400f,packet.plan.sourceBloomRadius,.0001f)
    }
}
