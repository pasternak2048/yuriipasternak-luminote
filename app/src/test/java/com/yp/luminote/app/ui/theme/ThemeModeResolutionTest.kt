package com.yp.luminote.app.ui.theme

import com.yp.luminote.app.data.settings.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeResolutionTest {

    @Test
    fun `system mode tracks system appearance`() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(false))
    }

    @Test
    fun `explicit modes override system appearance`() {
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(true))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(false))
    }
}
