---
name: luminote-context
description: Repository-specific architecture, invariants and engineering context for Luminote.
---
# Luminote Repository Context

Application ID: `com.yp.luminote.app`.

Current `dev` Gradle configuration at creation of Cyfrowa Sharaga v1.0: compileSdk 37, targetSdk 37, minSdk 36. The current Gradle files are always authoritative.

Stack includes Kotlin, Jetpack Compose, Material 3, Navigation Compose, DataStore Preferences, WorkManager, coroutines and Flow. Hardware acceleration is enabled.

Important areas: `data`, `effects`, `notification`, `ui`, `update`, `viewmodel`.

Settings flow through `LuminoteSettingsViewModel` and `LuminoteSettingsRepository`/DataStore. Preserve single-source-of-truth behavior and revision/debounce safeguards; do not introduce parallel persistence for existing settings.

Notification events enter through `LuminoteNotificationListener`. Consider repeated/grouped/removed notifications, rapid updates, reconnects and service lifecycle.

Halo responsibilities are separated between notification reception, request coordination, overlay rendering and accessibility-assisted behavior. Do not collapse them without an approved architecture change.

`HaloEffectCoordinator` serializes transient effects: one active request, FIFO pending requests, coalescing by application, bounded queue (`MAX_PENDING_REQUESTS = 8`), expiry (`MAX_REQUEST_AGE_MS = 15_000`), explicit renderer ownership and stale-detach protection. Operations are expected on the main thread. Preserve these invariants unless the task explicitly changes them.

`HaloOverlayService` is a special-use foreground service. `HaloAccessibilityService` separately handles accessibility/lock-screen behavior. Do not assume either service instance is permanent.

Halo geometry/rendering is device-sensitive. Avoid guessed constants and casual rewrites of known-working geometry. Validate straight edges, rounded corners, clipping, thickness, hardware acceleration and animation completion. Visual correctness requires device validation when relevant.

`InstalledAppsRepository` handles installed app discovery under Android package-visibility rules.

The update subsystem lives under `com.yp.luminote.app.update` and includes update discovery/channels/scheduling/notifications/installation/preferences/UI state. Package identity and signing compatibility are critical to updates.

Prefer preserving tuned working behavior over speculative cleanup. Understand invariants, make the smallest complete change, and validate regressions explicitly.
