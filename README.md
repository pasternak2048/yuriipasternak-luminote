# Luminote

**Luminote** is an Android application for customizable notification lighting and ambient edge effects.

## What is Luminote?

Luminote renders its own notification lighting effect around the physical contour of the display.

The core effect, **Luminote Halo**, provides a customizable visual indication for incoming notifications without relying on device-specific edge lighting implementations.

## Features

- Customizable **Luminote Halo**
- Multiple animation modes:
  - Pulse
  - Snake
  - Corner Pulse
  - Rain
  - Ripple Edge
- Adjustable effect speed
- Adjustable brightness and edge width
- Solid colors and animated gradients
- Notification app color matching
- Luminote and notification-app gradient palettes
- Per-app notification source selection
- Optional silent notification updates
- Repeatable notification effects
- Ambient Halo for persistent decorative edge lighting

## Project Structure

Luminote is built natively for Android using:

- Kotlin
- Jetpack Compose
- Material 3
- Android Notification Listener
- Android Overlay APIs
- Android Accessibility APIs
- DataStore Preferences

## Build

Clone the repository:

```bash
git clone https://github.com/pasternak2048/yuriipasternak-luminote.git
cd yuriipasternak-luminote
```

Build a debug APK:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

## Releases

Signed APK builds are produced automatically through GitHub Actions for tagged releases.

Release tags follow semantic versioning:

```text
v0.1.0
v0.1.1
v0.2.0
```

The resulting APK is published in the GitHub Releases section.

## Permissions

Luminote requires notification access and system permissions necessary to render its notification effects.

Some Halo functionality may also require the Luminote accessibility service.

These permissions are used exclusively for notification processing and edge-lighting functionality.

## Status

Luminote is currently in early development.

Features, behavior and UI may change between releases.

## Author

**Yurii Pasternak**

## License

MIT License - see [LICENSE](LICENSE).
