# Google Play review evidence draft

**Status: human and Play Console verification required.** This is a source-backed
draft for the release owner. It is not a Play Console declaration, approval, or
substitute for current Play policy.

## Accessibility service

### User-facing flow

1. The user opens **Access** and selects **Lock screen halo**.
2. If Luminote's accessibility service is not enabled, the app displays a
   standalone disclosure before it opens Android Accessibility settings.
3. The disclosure says that the service is used only to show a
   non-interactive lock-screen halo and does not retrieve window content, read
   text, perform actions, or transfer accessibility events off the device.
4. **Cancel**, dismiss, or system back closes the disclosure without opening
   Settings or changing Luminote settings. **Continue to Accessibility** opens
   Android's accessibility settings; enabling the service remains the user's
   separate Android-system choice.

### Source evidence to capture for the review submission

- `AndroidManifest.xml` declares `HaloAccessibilityService` with
  `BIND_ACCESSIBILITY_SERVICE` and its accessibility-service metadata.
- `luminote_halo_accessibility.xml` sets
  `canRetrieveWindowContent="false"` and requests only
  `typeWindowStateChanged` events.
- `HaloAccessibilityService.onAccessibilityEvent` is intentionally a no-op.
- The service renders a halo overlay; it does not implement touch handling,
  window-content retrieval, or accessibility actions.
- The in-app disclosure is in `AccessScreen` and is shown before the Android
  settings intent when the service is disabled.

### Release-owner checklist

- Capture the disclosure, Android settings page, and enabled-service state on
  a production-like device.
- Confirm current AccessibilityService policy eligibility and complete every
  required Play Console declaration with these exact, tested behaviors.
- Ensure store listing, privacy policy, and support contact describe the same
  limited purpose. Do not submit this draft as proof of approval.

## Special-use foreground service

`HaloOverlayService` is declared as `specialUse`; its manifest subtype is
“Renders the user-configured notification halo overlay.” The Access screen now
explains that, while a halo is active, its renderer runs as a foreground service
with an ongoing notification.

### Release-owner checklist

- Capture the ongoing foreground-service notification while a Halo is active.
- Confirm the actual tested trigger, stop behavior, and user-visible purpose
  match the current special-use declaration.
- Complete the current Play Console foreground-service declaration and retain
  screenshots/video requested by the Console. Policy requirements can change;
  verify them at submission time.
