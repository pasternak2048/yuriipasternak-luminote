package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.rendering.canvas.HaloRenderer

import android.graphics.Path
import org.junit.Assert.assertSame
import org.junit.Test

class StaticCalibrationContourTest {
    @Test fun `static preview reuses runtime contour with DisplayShape and non-neutral corner calibration`() {
        val displayShape = Path().apply { addRect(0f, 0f, 100f, 200f, Path.Direction.CW) }
        val outline = DisplayOutline(density = 2f).apply {
            resize(100, 200)
            updateDisplayShape(displayShape)
        }
        val renderer = HaloRenderer(
            HaloConfig(cornerCalibrationDp = 3f, cornerShape = 0.8f),
            outline
        )

        assertSame(
            renderer.stylePathForCurrentConfig(),
            renderer.staticPreviewPathForCurrentConfig()
        )
    }
}
