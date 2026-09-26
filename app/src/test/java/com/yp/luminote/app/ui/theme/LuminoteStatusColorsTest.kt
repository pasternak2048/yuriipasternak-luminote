package com.yp.luminote.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LuminoteStatusColorsTest {
    @Test
    fun `status text colors are adjusted for light surfaces`() {
        val light = luminoteStatusColors(darkTheme = false)
        val dark = luminoteStatusColors(darkTheme = true)

        assertNotEquals(LuminoteSuccess, light.successText)
        assertNotEquals(LuminoteWarning, light.warningText)
        assertEquals(LuminoteSuccess, dark.successText)
        assertEquals(LuminoteWarning, dark.warningText)
    }
}
