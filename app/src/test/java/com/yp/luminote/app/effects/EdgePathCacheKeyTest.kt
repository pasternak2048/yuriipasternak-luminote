package com.yp.luminote.app.effects

import org.junit.Assert.assertNotEquals
import org.junit.Test

class EdgePathCacheKeyTest {
    @Test fun `all geometry dimensions participate in cache identity`() {
        val key = EdgePathCacheKey(4, 12f, 3f, -2f, 0.8f, 0f)

        assertNotEquals(key, key.copy(outlineVersion = 5))
        assertNotEquals(key, key.copy(strokeWidth = 13f))
        assertNotEquals(key, key.copy(edgeCalibrationPx = 4f))
        assertNotEquals(key, key.copy(cornerCalibrationPx = -1f))
        assertNotEquals(key, key.copy(cornerShape = 0.6f))
        assertNotEquals(key, key.copy(extraEnvelopePx = 18f))
    }
}
