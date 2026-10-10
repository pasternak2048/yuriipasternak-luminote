package com.yp.luminote.app.effects

import android.graphics.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ContourOpticalCompositingTest {
    @Test
    fun `mesh compositing preserves fragment output rather than default opaque paint`() {
        // android.graphics.Paint's host-JVM stub does not retain assigned color. Device probe
        // verifies white neutrality; this test protects the deterministic blend selection.
        // Neutral opaque white preserves AGSL RGB/alpha under Mesh's documented modulation;
        // black/transparent Paint respectively erases or suppresses shader output on device.
        assertEquals(BlendMode.MODULATE, CONTOUR_OPTICAL_MESH_BLEND_MODE)
    }
}
