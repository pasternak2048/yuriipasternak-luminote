package com.yp.luminote.app.ui.access

import org.junit.Assert.assertEquals
import org.junit.Test

class LockScreenAccessibilityAccessActionTest {

    @Test
    fun `disabled accessibility access requires the standalone disclosure`() {
        assertEquals(
            LockScreenAccessibilityAccessAction.SHOW_DISCLOSURE,
            lockScreenAccessibilityAccessAction(
                accessAlreadyAllowed = false
            )
        )
    }

    @Test
    fun `enabled accessibility access opens Android settings directly`() {
        assertEquals(
            LockScreenAccessibilityAccessAction.OPEN_SETTINGS,
            lockScreenAccessibilityAccessAction(
                accessAlreadyAllowed = true
            )
        )
    }
}
