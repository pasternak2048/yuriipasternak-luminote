package com.yp.luminote.app.animation.definitions

/**
 * A typed, definition-owned setting.  Values here deliberately have no
 * renderer or Android dependency: definitions describe a value, while the
 * settings repository persists only user overrides.
 */
sealed interface AnimationPropertySpec<T : Any> {
    val key: String
    val defaultValue: T
    val userConfigurable: Boolean
    fun normalize(value: T): T
    fun read(raw: AnimationPropertyValue?): T?
    fun write(value: T): AnimationPropertyValue
}

data class FloatAnimationProperty(
    override val key: String,
    override val defaultValue: Float,
    val min: Float,
    val max: Float,
    override val userConfigurable: Boolean = false
) : AnimationPropertySpec<Float> {
    init {
        require(key.matches(PROPERTY_KEY)) { "Property keys must be stable lowercase identifiers." }
        require(defaultValue.isFinite() && min.isFinite() && max.isFinite() && min <= max)
        require(defaultValue in min..max)
    }
    override fun normalize(value: Float): Float = value.takeIf(Float::isFinite)?.coerceIn(min, max) ?: defaultValue
    override fun read(raw: AnimationPropertyValue?): Float? = (raw as? AnimationPropertyValue.FloatValue)?.value
    override fun write(value: Float): AnimationPropertyValue = AnimationPropertyValue.FloatValue(normalize(value))
}

data class BooleanAnimationProperty(
    override val key: String,
    override val defaultValue: Boolean,
    override val userConfigurable: Boolean = false
) : AnimationPropertySpec<Boolean> {
    init { require(key.matches(PROPERTY_KEY)) { "Property keys must be stable lowercase identifiers." } }
    override fun normalize(value: Boolean): Boolean = value
    override fun read(raw: AnimationPropertyValue?): Boolean? = (raw as? AnimationPropertyValue.BooleanValue)?.value
    override fun write(value: Boolean): AnimationPropertyValue = AnimationPropertyValue.BooleanValue(value)
}

sealed interface AnimationPropertyValue {
    data class FloatValue(val value: Float) : AnimationPropertyValue
    data class BooleanValue(val value: Boolean) : AnimationPropertyValue
}

/** Persisted overrides keyed by animation ID then property key; defaults are never stored. */
data class AnimationPropertyOverrides(
    val values: Map<HaloAnimationId, Map<String, AnimationPropertyValue>> = emptyMap()
) {
    fun value(animation: HaloAnimationId, key: String): AnimationPropertyValue? = values[animation]?.get(key)

    fun withValue(animation: HaloAnimationId, key: String, value: AnimationPropertyValue): AnimationPropertyOverrides =
        copy(values = values + (animation to ((values[animation].orEmpty()) + (key to value))))
}

/** Immutable output of resolution, suitable for a service/renderer session. */
class ResolvedAnimationProperties internal constructor(
    val animationId: HaloAnimationId,
    private val values: Map<String, AnimationPropertyValue>
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(spec: AnimationPropertySpec<T>): T =
        (spec.read(values[spec.key]) ?: spec.defaultValue).let(spec::normalize)
}

object AnimationPropertyResolver {
    fun resolve(
        definition: HaloAnimationDefinition,
        overrides: AnimationPropertyOverrides
    ): ResolvedAnimationProperties = ResolvedAnimationProperties(
        animationId = definition.id,
        values = definition.properties.associate { spec ->
            spec.key to when (spec) {
                is FloatAnimationProperty -> spec.write(spec.normalize(spec.read(overrides.value(definition.id, spec.key)) ?: spec.defaultValue))
                is BooleanAnimationProperty -> spec.write(spec.normalize(spec.read(overrides.value(definition.id, spec.key)) ?: spec.defaultValue))
            }
        }
    )
}

private val PROPERTY_KEY = Regex("[a-z][a-z0-9_]*")
