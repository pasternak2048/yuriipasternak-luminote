package com.yp.luminote.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceBladeCatalogTest {

    @Test
    fun `force blade family is available on the edge frame with independent durations`() {
        val supported = HaloEffectCatalog.frame(HaloFrame.CLASSIC).supportedMotions

        assertTrue(HaloMotion.AZURE_BLADE in supported)
        assertTrue(HaloMotion.CRIMSON_BLADE in supported)
        assertTrue(HaloMotion.FORCE_CLASH in supported)
        assertEquals(3.4f, HaloMotion.AZURE_BLADE.definition.baseDurationSeconds)
        assertEquals(3.0f, HaloMotion.CRIMSON_BLADE.definition.baseDurationSeconds)
        assertEquals(3.6f, HaloMotion.FORCE_CLASH.definition.baseDurationSeconds)
    }
}
