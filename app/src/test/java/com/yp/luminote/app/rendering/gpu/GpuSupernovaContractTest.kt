package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.canvas.supernovaCycleProgress
import com.yp.luminote.app.rendering.canvas.supernovaIntensity
import com.yp.luminote.app.rendering.api.*
import org.junit.Assert.*
import org.junit.Test

class GpuSupernovaContractTest {
    private fun production() = HaloEffectVisualPlanMapper.map(HaloConfig(motion=SupernovaAnimation.id)).emitters.single()

    @Test fun `source cycle matches Canvas ignition release fade and repeat`() {
        for (phase in listOf(-.1f,0f,.001f,.045f,.09f,.3f,.7f,.99f,1f,1.09f,2.7f)) {
            assertEquals(supernovaIntensity(supernovaCycleProgress(phase)!!),GpuSupernovaSourceProfile.intensity(phase),.000001f)
        }
        assertEquals(0f,GpuSupernovaSourceProfile.intensity(Float.NaN),0f)
    }
    @Test fun `capability admits exact production and baseline and rejects omitted optics`() {
        val emitter=production()
        assertEquals(GpuSupernovaCapability.productionModifiers,emitter.modifiers)
        assertTrue(GpuSupernovaCapability.supports(emitter))
        assertTrue(GpuSupernovaCapability.supports(emitter.copy(modifiers=emptyList())))
        assertTrue(GpuSupernovaCapability.supports(emitter.copy(modifiers=listOf(VisualModifier.Opacity(.4f))+emitter.modifiers)))
        assertFalse(GpuSupernovaCapability.supports(emitter.copy(modifiers=listOf(VisualModifier.Blur(4.5f)))))
        assertFalse(GpuSupernovaCapability.supports(emitter.copy(operation=PrimaryVisualOperation.FullContourPulse)))
        val command=FrameCommandCompiler.compile(VisualPlanSpec(listOf(emitter)),RenderFrame(),FrameCommandBuffer())[0]
        assertNull(GpuSupernovaCapability.unsupportedReason(RenderIntent.DEBUG_GPU_SUPERNOVA,RenderMode.ANIMATION,1,command))
        assertNull(GpuSupernovaCapability.unsupportedReason(RenderIntent.PRODUCTION,RenderMode.ANIMATION,1,command))
    }
}
