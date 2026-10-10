package com.yp.luminote.app.data.settings

import com.yp.luminote.app.R
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpulseCatalogTest {

    @Test
    fun `registry exposes the locked normal catalog order`() {
        val motions = LuminoteHaloAnimations.all.map { it.id }

        assertEquals(listOf("IMPULSE", "PULSE", "SNAKE", "AZURE_BLADE", "CRIMSON_BLADE", "FORCE_CLASH").map(::HaloAnimationId), motions)
        assertEquals(1, motions.count { it == HaloAnimationId("IMPULSE") })
        assertEquals(R.string.motion_impulse, LuminoteHaloAnimations.registry.definition(HaloAnimationId("IMPULSE")).titleRes)
        assertEquals(2.2f, LuminoteHaloAnimations.registry.definition(HaloAnimationId("IMPULSE")).baseDurationSeconds)
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
        assertEquals(HaloAnimationId("IMPULSE"), HaloAnimationStorage.resolveNormal("CORNER_PULSE"))
        assertEquals(HaloAnimationId("IMPULSE"), HaloAnimationStorage.resolveNormal("unknown"))
        assertEquals(HaloAnimationId("PULSE"), HaloAnimationStorage.resolveAmbient("FORCE_CLASH"))
        assertEquals(HaloAnimationId("PULSE"), HaloAnimationStorage.resolveAmbient("RAIN"))
        assertEquals(listOf(HaloAnimationId("PULSE"), HaloAnimationId("SNAKE")), LuminoteHaloAnimations.ambient.map { it.id })
    }
}
