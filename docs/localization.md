# Localization

Luminote uses the default `values/` resources for English and `values-uk/` for Ukrainian. User-visible application UI, service labels, accessibility descriptions, and update notifications use these resources.

Intentionally excluded from localization are internal identifiers and persisted values (route names, enum names, update tag markers, notification channel IDs, preferences/work names, package names, MIME types), the Android-required special-use foreground-service subtype, URLs, log/diagnostic text, and application names supplied by Android or other installed apps. These are not user-facing Luminote copy and changing them could affect behavior.

Android caches notification-channel names and descriptions after first creation. Existing channels may therefore retain their earlier language until Android recreates or the user resets that channel; the channel ID is deliberately unchanged.
