---
name: luminote-android-compose
description: Android, Kotlin and Jetpack Compose implementation rules for Luminote.
---
# Luminote Android / Compose Engineering

Follow repository style before generic Android patterns. Do not introduce a new architecture framework just to implement a feature.

Persistent application settings belong in repository/ViewModel state, not independent Composable state. Local Compose state is for genuinely local UI behavior. Avoid duplicate sources of truth.

Use Compose effects according to ownership: `LaunchedEffect` for coroutine work tied to composition keys, `DisposableEffect` for resources/listeners requiring cleanup, `remember` for composition-scoped values. Never rely on a Composable body running once and do not perform persistence/service starts/listener registration/heavy repository work directly during normal composition.

Every coroutine needs an intentional lifecycle owner. Prefer `viewModelScope`, service-owned scopes and composition effects. Cancellation must leave state consistent; do not use arbitrary delays as race-condition fixes.

Prefer Flow/StateFlow where the project already models streams. Be careful with optimistic state, DataStore state and debounced writes: older persisted values must not overwrite newer edits.

Luminote uses NotificationListenerService, a foreground overlay service and AccessibilityService. Tolerate destruction, recreation, reconnect, permission changes and renderer replacement. Do not leak Activity contexts.

Notification callbacks may arrive rapidly. Preserve queue/coalescing semantics unless explicitly changing them.

Halo rendering is performance-sensitive. Avoid unnecessary per-frame allocations, preserve hardware acceleration, and consider display geometry, clipping, stroke bounds, rounded corners, density and animation cancellation/completion. Do not hardcode geometry for one phone.

Luminote uses Material 3 with intentional One UI-inspired behavior. Preserve existing typography, spacing, component shapes, controls and hierarchy unless redesign is requested.

The current Gradle configuration is authoritative for Android compatibility. At creation of this skill it is minSdk 36, targetSdk 37, compileSdk 37.

For Android/Compose changes: compile affected code, run relevant tests and lint/static checks when available, and explicitly identify emulator/device validation. Compilation is not proof of runtime or visual correctness.
