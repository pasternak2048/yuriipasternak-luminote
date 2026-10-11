package com.yp.luminote.app.data.settings

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.yp.luminote.app.animation.definitions.AnimationPropertyOverrides
import com.yp.luminote.app.animation.definitions.AnimationPropertyValue
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnimationPropertyStorageTest {
    private val snake = HaloAnimationId("SNAKE")
    private val segment = LuminoteHaloAnimations.registry.definition(snake).properties.single()
    private val persistedKey = floatPreferencesKey(AnimationPropertyStorage.key(snake, segment))

    @Test fun `typed override persists restores and default removal keeps legacy globals`() {
        val preferences = mutablePreferencesOf(floatPreferencesKey("halo_intensity") to 0.65f)
        val override = AnimationPropertyOverrides().withValue(snake, segment.key, AnimationPropertyValue.FloatValue(0.3f))

        AnimationPropertyStorage.write(preferences, override)
        assertEquals(0.3f, (AnimationPropertyStorage.read(preferences).value(snake, segment.key) as AnimationPropertyValue.FloatValue).value)
        assertEquals(0.65f, preferences[floatPreferencesKey("halo_intensity")])

        AnimationPropertyStorage.write(preferences, AnimationPropertyOverrides())
        assertFalse(preferences.asMap().containsKey(persistedKey))
        assertEquals(0.65f, preferences[floatPreferencesKey("halo_intensity")])
    }
}
