package com.yp.luminote.app.data.settings

import com.yp.luminote.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpulseCatalogTest {

    @Test
    fun `classic catalog exposes impulse once immediately after pulse`() {
        val motions = HaloEffectCatalog.frame(HaloFrame.CLASSIC).supportedMotions

        assertEquals(HaloMotion.IMPULSE, motions[motions.indexOf(HaloMotion.PULSE) + 1])
        assertEquals(1, motions.count { it == HaloMotion.IMPULSE })
        assertEquals(R.string.motion_impulse, HaloMotion.IMPULSE.definition.titleRes)
        assertEquals(2.2f, HaloMotion.IMPULSE.definition.baseDurationSeconds)
    }

    @Test
    fun `storage parser migrates former ripple edge and rejects unknown values`() {
        assertEquals(HaloMotion.IMPULSE, HaloMotion.fromStorage("RIPPLE_EDGE"))
        assertEquals(HaloMotion.IMPULSE, HaloMotion.fromStorage("IMPULSE"))
        assertNull(HaloMotion.fromStorage("ripple_edge"))
        assertNull(HaloMotion.fromStorage(null))
        assertFalse(HaloMotion.entries.any { it.name == "RIPPLE_EDGE" })
    }

    @Test
    fun `migrated impulse remains unavailable to ambient selection`() {
        val parsed = HaloMotion.fromStorage("RIPPLE_EDGE")

        assertTrue(parsed !in setOf(HaloMotion.PULSE, HaloMotion.SNAKE))
    }
}
