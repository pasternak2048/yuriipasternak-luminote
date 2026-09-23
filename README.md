# Luminote

Luminote is an Android app that adds a configurable light around the edge of your screen. It makes incoming notifications easier to notice without relying on a phone maker's built-in edge-lighting feature, and it can also keep a decorative edge effect visible while the screen is on.

## Features

- **Luminote Halo** for notification-driven edge glow.
- Choose whether every app or only selected apps can trigger the Halo.
- Choose a solid color, match the notifying app's icon color, or use a gradient. Gradients can use Luminote's palette or colors from active notifications.
- Adjust brightness, edge width, effect speed, and gradient flow.
- Choose Pulse, Snake, Corner pulse, Rain, or Ripple edge animations.
- Set notification reminders to play once, repeat, or remain visible until relevant notifications are dismissed.
- **Ambient Halo**, a separate always-visible edge effect while the screen is on, with its own color, brightness, width, animation, and speed.
- Optional lock-screen Halo support through Luminote's accessibility service.
- In-app updates from GitHub Releases, with Stable, QA, and Dev channels. Update notifications are optional.
- English and Ukrainian interface translations. In **Language**, choose System default, English, or Ukrainian; System default follows the phone's language.

## Requirements and permissions

Luminote requires **Android 16 (API 36) or later**.

To use notification effects, open **Access** in Luminote and allow:

- **Display over other apps** — lets the Halo appear above the app currently on screen.
- **Notification access** — lets Luminote detect notifications and, when selected, use the notifying app's icon color.

The following permissions are used for specific optional features:

- **Accessibility service** — enables the lock-screen Halo. The service does not retrieve window content, accept touches, or wake the display.
- **Notifications** — needed only when you enable update notifications.
- **Install unknown apps** — requested only when you choose to install a downloaded in-app update.
- **Internet** — checks GitHub Releases and downloads an update when you request one.

Luminote also runs its active Halo renderer as an Android foreground service so the effect can remain available when the app is not open.

## Install and use

1. Download an APK from the project's [GitHub Releases](https://github.com/pasternak2048/yuriipasternak-luminote/releases) page and install it. Android may ask you to allow installs from the app you used to open the APK.
2. Open Luminote and select **Alerts** or **Ambient** on the home screen.
3. For notification effects, open **Access** and enable Display over other apps and Notification access.
4. Open **Luminote Halo** to customize notification lighting, and **Apps** to limit which apps can trigger it. Use **Ambient Halo** to customize the persistent effect.
5. Optionally enable the lock-screen Halo in **Access**, or choose a language in **Language**.

In **About**, you can check for updates, choose an update channel, download an available APK, and install it through Android's installer.

## Development

Luminote is a Kotlin Android application built with Jetpack Compose and Material 3. The repository includes the Gradle wrapper and uses Java 17 in its release workflow.

```bash
git clone https://github.com/pasternak2048/yuriipasternak-luminote.git
cd yuriipasternak-luminote
./gradlew assembleDebug
./gradlew test
```

On Windows, use `./gradlew.bat assembleDebug` and `./gradlew.bat test`.

The app module targets API 37, has a minimum SDK of 36, and includes unit and instrumentation tests. Tagged `v*` releases are built by GitHub Actions; the workflow publishes an APK and its update manifest to GitHub Releases.

## Status

Luminote is in early development. Device-specific behavior, especially overlays and lock-screen effects, should be tested on the Android device you plan to use.

## License

Copyright (c) 2026 Yurii Pasternak. Licensed under the [MIT License](LICENSE).
