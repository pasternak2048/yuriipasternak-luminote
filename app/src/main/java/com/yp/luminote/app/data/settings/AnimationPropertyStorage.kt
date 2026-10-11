package com.yp.luminote.app.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.yp.luminote.app.animation.definitions.AnimationPropertyOverrides
import com.yp.luminote.app.animation.definitions.AnimationPropertySpec
import com.yp.luminote.app.animation.definitions.AnimationPropertyValue
import com.yp.luminote.app.animation.definitions.BooleanAnimationProperty
import com.yp.luminote.app.animation.definitions.FloatAnimationProperty
import com.yp.luminote.app.animation.definitions.HaloAnimationDefinition
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations

/** DataStore adapter for definition-owned settings; this never writes defaults. */
internal object AnimationPropertyStorage {
    private const val PREFIX = "animation_property_v1_"

    fun read(preferences: Preferences): AnimationPropertyOverrides = AnimationPropertyOverrides(
        LuminoteHaloAnimations.all.associate { definition ->
            definition.id to definition.properties.mapNotNull { spec ->
                read(preferences, definition.id, spec)?.let { spec.key to it }
            }.toMap()
        }.filterValues { it.isNotEmpty() }
    )

    fun write(preferences: androidx.datastore.preferences.core.MutablePreferences, overrides: AnimationPropertyOverrides) {
        LuminoteHaloAnimations.all.forEach { definition ->
            definition.properties.forEach { spec ->
                val key = key(definition.id, spec)
                when (spec) {
                    is FloatAnimationProperty -> {
                        val value = overrides.value(definition.id, spec.key) as? AnimationPropertyValue.FloatValue
                        if (value == null || spec.normalize(value.value) == spec.defaultValue) preferences.remove(floatPreferencesKey(key))
                        else preferences[floatPreferencesKey(key)] = spec.normalize(value.value)
                    }
                    is BooleanAnimationProperty -> {
                        val value = overrides.value(definition.id, spec.key) as? AnimationPropertyValue.BooleanValue
                        if (value == null || value.value == spec.defaultValue) preferences.remove(booleanPreferencesKey(key))
                        else preferences[booleanPreferencesKey(key)] = value.value
                    }
                }
            }
        }
    }

    private fun read(preferences: Preferences, id: HaloAnimationId, spec: AnimationPropertySpec<*>): AnimationPropertyValue? =
        when (spec) {
            is FloatAnimationProperty -> preferences[floatPreferencesKey(key(id, spec))]
                ?.takeIf(Float::isFinite)?.let(AnimationPropertyValue::FloatValue)
            is BooleanAnimationProperty -> preferences[booleanPreferencesKey(key(id, spec))]
                ?.let(AnimationPropertyValue::BooleanValue)
        }

    /** Length prefixes make pairs unambiguous even if future IDs contain separators. */
    internal fun key(id: HaloAnimationId, spec: AnimationPropertySpec<*>): String =
        "$PREFIX${id.value.length}_${id.value}_${spec.key.length}_${spec.key}"
}
