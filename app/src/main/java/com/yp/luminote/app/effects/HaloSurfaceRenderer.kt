package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.view.WindowInsets
import com.yp.luminote.app.data.settings.HaloEffectCatalog
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion

/** A renderer for one animation surface. It owns all geometry specific to that surface. */
internal interface HaloSurfaceRenderer {
    fun update(config: HaloConfig)

    fun onSizeChanged(width: Int, height: Int)

    fun onInsetsChanged(insets: WindowInsets)

    fun draw(canvas: Canvas, state: HaloAnimationState)
}

/**
 * The only selection point between a configured surface and its renderer.
 * A renderer is never created for an unsupported frame/motion pair.
 */
internal class HaloSurfaceRendererRegistry(
    private val factories: Map<HaloFrame, HaloSurfaceRendererFactory> = defaultFactories,
    private val supports: (HaloFrame, HaloMotion) -> Boolean =
        HaloEffectCatalog::supports
) {
    fun create(config: HaloConfig, density: Float): HaloSurfaceRenderer? {
        if (!supports(config.frame, config.motion)) return null
        return factories[config.frame]?.create(config, density)
    }

    companion object {
        private val defaultFactories = mapOf<HaloFrame, HaloSurfaceRendererFactory>(
            HaloFrame.CLASSIC to EdgeFrameRendererFactory
        )
    }
}

internal fun interface HaloSurfaceRendererFactory {
    fun create(config: HaloConfig, density: Float): HaloSurfaceRenderer
}

private object EdgeFrameRendererFactory : HaloSurfaceRendererFactory {
    override fun create(config: HaloConfig, density: Float): HaloSurfaceRenderer =
        EdgeFrameRenderer(config, density)
}
