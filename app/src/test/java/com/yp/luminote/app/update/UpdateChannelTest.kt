package com.yp.luminote.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {

    @Test
    fun `stable channel excludes prerelease tags`() {
        assertTrue(UpdateChannel.STABLE.acceptsTag("v0.1.5"))
        assertFalse(UpdateChannel.STABLE.acceptsTag("v0.1.5.qa.3"))
        assertFalse(UpdateChannel.STABLE.acceptsTag("v0.1.5.dev.12"))
    }

    @Test
    fun `qa and dev channels only accept their own tags`() {
        assertTrue(UpdateChannel.QA.acceptsTag("v0.1.5.qa.3"))
        assertFalse(UpdateChannel.QA.acceptsTag("v0.1.5.dev.12"))
        assertTrue(UpdateChannel.DEV.acceptsTag("v0.1.5.dev.12"))
        assertFalse(UpdateChannel.DEV.acceptsTag("v0.1.5.qa.3"))
    }
}
