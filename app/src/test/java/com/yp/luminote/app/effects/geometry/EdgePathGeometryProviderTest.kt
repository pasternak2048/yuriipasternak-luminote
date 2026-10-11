package com.yp.luminote.app.effects.geometry
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider

import android.graphics.Path
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

    @Test
    fun `neutral calibration resolves the optical baseline before user calibration and envelope`() {
        val outline = DisplayOutline(density = 2f).apply { resize(100, 200) }

        val resolved = outline.centerlinePath(
            strokeWidth = 12f,
            edgeCalibrationPx = outline.opticalInsetPx + 3f,
            cornerCalibrationPx = 0f,
            cornerShape = 0.5f,
            extraEnvelopePx = 18f
        )

        // 6px half stroke + 4px automatic optical inset + 3px user calibration + 18px envelope.
        assertSame(outline.strokePath(31f), resolved)
    }

    @Test
    fun `zero envelope consumers and Force Blades envelope retain distinct cached contours`() {
        val outline = DisplayOutline(density = 2f).apply { resize(100, 200) }
        val provider = EdgePathGeometryProvider(outline)

        val zeroEnvelope = provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 0f)
        val forceBlades = provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 18f)

        assertNotSame(zeroEnvelope, forceBlades)
        assertSame(forceBlades, provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 18f))
    }

    @Test
    fun `provider rejects zero dimensions and invalidates after size or display shape updates`() {
        val outline = DisplayOutline(density = 2f)
        val provider = EdgePathGeometryProvider(outline)

        assertNull(provider.geometryFor(12f, outline.opticalInsetPx, 0f, 0.5f, 0f))

        outline.resize(100, 200)
        val beforeShapeUpdate = provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 0f)
        outline.updateDisplayShape(Path().apply { addRect(0f, 0f, 100f, 200f, Path.Direction.CW) })
        val afterShapeUpdate = provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 0f)
        assertNotSame(beforeShapeUpdate, afterShapeUpdate)

        outline.resize(200, 100)
        assertNotSame(afterShapeUpdate, provider.pathFor(12f, outline.opticalInsetPx, 0f, 0.5f, 0f))
    }
}
