package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourOpticalBackendRoutingTest {
    @Test
    fun `snake including ambient snake is eligible for the shared gpu backend`() {
        assertTrue(usesContourOpticalBackend(HaloConfig(motion = HaloMotion.SNAKE)))
        assertTrue(usesContourOpticalBackend(HaloConfig(motion = HaloMotion.SNAKE, renderMode = HaloRenderMode.AMBIENT)))
    }

    @Test
    fun `every registered motion is routed through the shared gpu backend`() {
        HaloMotion.entries.forEach { motion ->
            assertTrue("Expected $motion to use the GPU backend", usesContourOpticalBackend(HaloConfig(motion = motion)))
        }
    }

    @Test
    fun `impulse and reminder impulse use the shared contour backend`() {
        assertTrue(usesContourOpticalBackend(HaloConfig(motion = HaloMotion.IMPULSE)))
        assertTrue(usesContourOpticalBackend(HaloConfig(motion = HaloMotion.IMPULSE, renderMode = HaloRenderMode.LIGHT_IMPULSE)))
    }

    @Test
    fun `spatial gradient uses bounded gpu palette routing`() {
        assertTrue(
            usesContourOpticalBackend(
                HaloConfig(
                    motion = HaloMotion.SNAKE,
                    colorMode = HaloColorMode.GRADIENT,
                    palette = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
                )
            )
        )
    }

    @Test
    fun `palette animation uses shared gpu routing`() {
        assertTrue(
            usesContourOpticalBackend(
                HaloConfig(
                    motion = HaloMotion.SNAKE,
                    palette = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
                )
            )
        )
    }

    @Test
    fun `normal and ambient snake keep their respective supplied alpha envelope`() {
        val normal = HaloConfig(motion = HaloMotion.SNAKE, intensity = 0.8f)
        val ambient = normal.copy(renderMode = HaloRenderMode.AMBIENT)
        assertTrue(contourOpticalIntensity(normal, 0.25f) == 0.2f)
        assertTrue(contourOpticalIntensity(ambient, 1f) == 0.8f)
    }

    @Test
    fun `reminder impulse uses its configured intensity while phase owns its optical fade`() {
        val reminder = HaloConfig(
            motion = HaloMotion.IMPULSE,
            intensity = 0.7f,
            renderMode = HaloRenderMode.LIGHT_IMPULSE
        )

        assertTrue(contourOpticalIntensity(reminder, 0f) == 0.7f)
        assertTrue(contourOpticalIntensity(reminder, 0.984f) == 0.7f)
    }

    @Test
    fun `gpu renderer construction is safe before a hardware mesh draw`() {
        ContourOpticalRenderer()
    }
}
