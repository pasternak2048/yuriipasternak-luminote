package com.yp.luminote.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminoteSettingsContractTest {

    @Test
    fun `default settings enable notification halo with stable defaults`() {
        val settings = LuminoteSettings()

        assertEquals(HaloMode.NOTIFICATIONS, settings.haloMode)
        assertTrue(settings.haloEnabled)
        assertFalse(settings.ambientEnabled)
        assertEquals(NotificationSource.ALL_APPS, settings.notificationSource)
        assertEquals(NotificationPlayback.ONCE, settings.notificationPlayback)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertTrue(settings.selectedApps.isEmpty())
    }

    @Test
    fun `mode derived flags remain mutually exclusive`() {
        val ambient = LuminoteSettings(haloMode = HaloMode.AMBIENT)
        val off = LuminoteSettings(haloMode = HaloMode.OFF)

        assertFalse(ambient.haloEnabled)
        assertTrue(ambient.ambientEnabled)
        assertFalse(off.haloEnabled)
        assertFalse(off.ambientEnabled)
    }

    @Test
    fun `theme mode parser falls back to system for missing or malformed data`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("night"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("LIGHT"))
    }
}
