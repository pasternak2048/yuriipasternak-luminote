package com.yp.luminote.app.effects

import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConventionalPathCacheKeyTest {
    @Test fun `independent calibration changes invalidate conventional path identity`() {
        val automatic = ConventionalPathCacheKey(4, 12f, 0f, 0f)
        assertNotEquals(automatic, automatic.copy(edgeCalibrationDp = -2f))
        assertNotEquals(automatic, automatic.copy(cornerCalibrationDp = 2f))
        assertNotEquals(automatic, automatic.copy(cornerShape = 1f))
        assertNotEquals(automatic, automatic.copy(outlineVersion = 5))
    }
}
