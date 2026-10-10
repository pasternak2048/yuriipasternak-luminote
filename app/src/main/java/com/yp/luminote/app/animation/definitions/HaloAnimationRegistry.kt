package com.yp.luminote.app.animation.definitions

/**
 * Lightweight composition root for animation definitions.
 *
 * This registry intentionally owns only ordering, validation, and identity
 * resolution. Runtime behavior and fallback policy belong to callers and
 * definitions respectively.
 */
class HaloAnimationRegistry(definitions: List<HaloAnimationDefinition>) {
    val definitions: List<HaloAnimationDefinition> = definitions.toList()

    private val byId: Map<HaloAnimationId, HaloAnimationDefinition> =
        this.definitions.associateBy(HaloAnimationDefinition::id)

    init {
        require(this.definitions.size == byId.size) { "Animation definition IDs must be unique." }
    }

    val ambientDefinitions: List<HaloAnimationDefinition> =
        this.definitions.filter(HaloAnimationDefinition::ambientEligible)

    fun definition(id: HaloAnimationId): HaloAnimationDefinition =
        requireNotNull(byId[id]) { "No animation definition is registered for $id." }

    fun find(id: HaloAnimationId): HaloAnimationDefinition? = byId[id]
}
