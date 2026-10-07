package com.yp.luminote.app.data.settings

import com.yp.luminote.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpulseCatalogTest {

    @Test
    fun `registry exposes the locked normal catalog order`() {
        val motions = HaloAnimationRegistry.normalDefinitions.map { it.motion }

        assertEquals(listOf(HaloMotion.IMPULSE, HaloMotion.PULSE, HaloMotion.SNAKE, HaloMotion.AZURE_BLADE, HaloMotion.CRIMSON_BLADE, HaloMotion.FORCE_CLASH), motions)
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

    @Test
    fun `removed unknown and non ambient identifiers normalize by capability`() {
        assertEquals(HaloMotion.IMPULSE, HaloAnimationRegistry.resolveNormal("CORNER_PULSE"))
        assertEquals(HaloMotion.IMPULSE, HaloAnimationRegistry.resolveNormal("unknown"))
        assertEquals(HaloMotion.PULSE, HaloAnimationRegistry.resolveAmbient("FORCE_CLASH"))
        assertEquals(HaloMotion.PULSE, HaloAnimationRegistry.resolveAmbient("RAIN"))
        assertEquals(listOf(HaloMotion.PULSE, HaloMotion.SNAKE), HaloAnimationRegistry.ambientDefinitions.map { it.motion })
    }
}
