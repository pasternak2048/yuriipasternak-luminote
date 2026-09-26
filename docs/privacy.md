# Privacy and Data Safety source-audit draft

**Status: human and Play Console verification required.** This is a technical
source audit of the current app tree, not a public privacy policy, legal advice,
or a completed Data Safety form. The release owner must verify shipped binaries,
third-party dependencies, release configuration, and current Play requirements
before publishing a public statement.

## Local processing and storage identified in source

- **Notification access:** `LuminoteNotificationListener` receives Android
  notification callbacks to decide whether to render a Halo. It uses package
  identity, notification state/category, and icon-derived colors. For media
  notifications it also reads title and text locally to distinguish meaningful
  media changes from repeated callbacks. This source audit found no code path
  that uploads notification content.
- **Accessibility:** `HaloAccessibilityService` receives the configured
  accessibility event type but has a no-op `onAccessibilityEvent`. Its metadata
  disables window-content retrieval. The service is used to render the
  non-interactive lock-screen Halo.
- **App choices:** DataStore persists Luminote display/effect preferences and,
  when selected-app mode is used, selected application package names. Update
  preferences persist the selected update channel, update-notification choice,
  and notified version markers.
- **Downloaded update files:** the updater stores downloaded APK files in the
  app cache directory before handing installation to Android's package
  installer.

## Network behavior identified in source

The source's update repository contacts GitHub Releases to check release
metadata and download a user-requested update. This audit found no analytics,
advertising SDK, crash-reporting SDK, account system, or separate telemetry
client in the application source/dependency declarations inspected for this
change. Network requests and dependencies must still be rechecked from the
release artifact before publication.

## Data Safety completion checklist

- Inspect the signed release APK/AAB, merged manifest, dependency report, and
  network traffic on a device; update this audit if they differ from source.
- Decide and accurately answer Play Console's current questions for
  notification-related data, package/app information, update download traffic,
  storage, sharing, retention, deletion, encryption, and optional features.
- Publish a real, reviewable privacy-policy URL before entering it in Play
  Console. This repository draft intentionally does not invent one.
- Keep the Play listing, in-app disclosure, public policy, and Console answers
  consistent; obtain legal/privacy review where appropriate.
