package com.yp.luminote.app.animation

import org.junit.Assert.assertFalse
import org.junit.Test

class AnimationPlanContractTest {
    @Test
    fun `blade choreography exposes no Android graphics or overlay API`() {
        val forbidden = listOf("android.graphics", "android.view", "overlay", "service")
        val plans = listOf(
            BladeLifecycle::class.java,
            BladeRetraction::class.java,
            DuelLifecycle::class.java,
            DuelRetraction::class.java
        )
        plans.flatMap { it.declaredMethods.asIterable() }
            .flatMap { sequenceOf(it.returnType, *it.parameterTypes).asIterable() }
            .forEach { type -> forbidden.forEach { prefix -> assertFalse(type.name.startsWith(prefix)) } }
    }
}
