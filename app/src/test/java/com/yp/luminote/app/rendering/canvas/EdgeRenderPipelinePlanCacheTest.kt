package com.yp.luminote.app.rendering.canvas

import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class EdgeRenderPipelinePlanCacheTest {
    @Test fun `effect target rebinds only when resolved effect plan changes`() {
        val pipeline = EdgeRenderPipeline(HaloConfig(motion = com.yp.luminote.app.animation.definitions.HaloAnimationId("SNAKE")), DisplayOutline(density = 1f))
        val initial = pipeline.cachedEffectTargetForTest()

        pipeline.update(HaloConfig(motion = com.yp.luminote.app.animation.definitions.HaloAnimationId("SNAKE")))
        assertSame(initial, pipeline.cachedEffectTargetForTest())

        pipeline.update(HaloConfig(motion = com.yp.luminote.app.animation.definitions.HaloAnimationId("SNAKE"), effectPlan = HaloEffectSpec.LuminousSegment(0.3f)))
        assertNotSame(initial, pipeline.cachedEffectTargetForTest())
    }
}
