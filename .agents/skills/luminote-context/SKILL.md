---
name: luminote-context
description: Compact repository facts and protected invariants for Luminote.
---
Use only when Luminote repository facts/invariants are needed; do not load for unrelated or already-sufficiently-scoped work.
# Luminote Context
Application: `com.yp.luminote.app`. Gradle is authoritative for SDK/version configuration. Stack: Kotlin, Compose/Material3, Navigation Compose, DataStore, WorkManager, coroutines/Flow; hardware acceleration enabled.

Settings: `LuminoteSettingsViewModel` -> `LuminoteSettingsRepository`/DataStore. Preserve single source of truth and revision/debounce safeguards.

Notifications enter through `LuminoteNotificationListener`; account for repeated/grouped/removed events, rapid updates, reconnects and service lifecycle.

Halo separates notification reception, coordination, overlay rendering and accessibility behavior. `HaloEffectCoordinator`: one active request, FIFO pending, app coalescing, bounded queue `MAX_PENDING_REQUESTS=8`, expiry `MAX_REQUEST_AGE_MS=15_000`, explicit renderer ownership, stale-detach protection, main-thread expectation. Preserve unless explicitly changed.

`HaloOverlayService` is a special-use foreground service; `HaloAccessibilityService` separately handles accessibility/lock-screen behavior. Neither instance is permanent. Geometry/rendering is device-sensitive; validate clipping, straight edges, rounded corners, thickness, acceleration and completion when relevant.

`InstalledAppsRepository` owns installed-app discovery/package visibility. Update subsystem is under `com.yp.luminote.app.update`; package identity/signing compatibility are critical.
