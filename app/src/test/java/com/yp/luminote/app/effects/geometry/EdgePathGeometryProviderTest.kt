package com.yp.luminote.app.effects.geometry
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class EdgePathGeometryProviderTest {
    @Test
    fun `provider reuses a matching contour and invalidates it for every geometry key dimension`() {
        val outline = DisplayOutline(density = 2f).apply { resize(100, 200) }
        val provider = EdgePathGeometryProvider(outline)

        val baseline = provider.pathFor(12f, 4f, 0f, 0.5f, 0f)
        assertSame(baseline, provider.pathFor(12f, 4f, 0f, 0.5f, 0f))
        assertNotSame(baseline, provider.pathFor(13f, 4f, 0f, 0.5f, 0f))
        assertNotSame(baseline, provider.pathFor(12f, 5f, 0f, 0.5f, 0f))
        assertNotSame(baseline, provider.pathFor(12f, 4f, 1f, 0.5f, 0f))
        assertNotSame(baseline, provider.pathFor(12f, 4f, 0f, 0.6f, 0f))
        assertNotSame(baseline, provider.pathFor(12f, 4f, 0f, 0.5f, 8f))

        val beforeResize = provider.pathFor(12f, 4f, 0f, 0.5f, 0f)
        outline.resize(200, 100)
        assertNotSame(beforeResize, provider.pathFor(12f, 4f, 0f, 0.5f, 0f))
    }
}
