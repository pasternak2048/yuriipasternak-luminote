package com.yp.luminote.app.overlay.coordination

import android.os.Bundle
import com.yp.luminote.app.animation.definitions.AnimationPropertyOverrides
import com.yp.luminote.app.animation.definitions.AnimationPropertyValue
import com.yp.luminote.app.animation.definitions.BooleanAnimationProperty
import com.yp.luminote.app.animation.definitions.FloatAnimationProperty
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations

/** Explicit service-boundary transport for typed overrides; defaults are not marshalled. */
internal object AnimationPropertyIntentCodec {
    fun write(overrides: AnimationPropertyOverrides): Bundle = Bundle().apply {
        LuminoteHaloAnimations.all.forEach { definition -> definition.properties.forEach { spec ->
            when (val value = overrides.value(definition.id, spec.key)) {
                is AnimationPropertyValue.FloatValue -> if (spec is FloatAnimationProperty &&
                    value.value.isFinite() && spec.normalize(value.value) == value.value) {
                    putFloat(key(definition.id.value, spec.key), value.value)
                }
                is AnimationPropertyValue.BooleanValue -> if (spec is BooleanAnimationProperty) {
                    putBoolean(key(definition.id.value, spec.key), value.value)
                }
                null -> Unit
            }
        } }
    }

    fun read(bundle: Bundle?): AnimationPropertyOverrides {
        if (bundle == null) return AnimationPropertyOverrides()
        var overrides = AnimationPropertyOverrides()
        LuminoteHaloAnimations.all.forEach { definition -> definition.properties.forEach { spec ->
            val key = key(definition.id.value, spec.key)
            when (spec) {
                is FloatAnimationProperty -> (bundle.get(key) as? Float)
                    ?.takeIf { acceptsFloat(spec, it) }
                    ?.let { overrides = overrides.withValue(definition.id, spec.key, AnimationPropertyValue.FloatValue(it)) }
                is BooleanAnimationProperty -> (bundle.get(key) as? Boolean)
                    ?.let { overrides = overrides.withValue(definition.id, spec.key, AnimationPropertyValue.BooleanValue(it)) }
            }
        } }
        return overrides
    }

    internal fun key(id: String, property: String): String = "${id.length}:$id:${property.length}:$property"

    internal fun acceptsFloat(spec: FloatAnimationProperty, raw: Any?): Boolean =
        (raw as? Float)?.let { it.isFinite() && spec.normalize(it) == it } == true
}
