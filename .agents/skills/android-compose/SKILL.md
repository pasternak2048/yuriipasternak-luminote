---
name: luminote-android-compose
description: Compact Android, Kotlin and Compose engineering rules for Luminote.
---
Use only for Android/Kotlin/Compose-sensitive work; do not load when the routed evidence already suffices.
# Android / Compose Rules
Follow repository patterns before generic architecture. Persistent app settings belong in repository/ViewModel state; local Compose state only for local UI. Avoid duplicate sources of truth.

Use `LaunchedEffect` for coroutine work tied to composition keys, `DisposableEffect` for resources/listeners requiring cleanup, `remember` for composition-scoped values. No persistence/service starts/listener registration/heavy work in normal composition.

Every coroutine needs an intentional lifecycle owner; cancellation must leave consistent state. Prefer existing Flow/StateFlow patterns. Do not use arbitrary delays to hide races. Handle service destruction/recreation/reconnect, permission changes and renderer replacement; do not leak Activity contexts.

For rendering avoid unnecessary per-frame allocations and device-specific geometry assumptions. Preserve existing Material3/One UI-inspired hierarchy unless redesign is requested. Gradle is authoritative for compatibility. Compile/test/lint as relevant; compilation is not runtime/visual proof.
