# Localization

Luminote uses the default `values/` resources for English and `values-uk/` for Ukrainian. User-visible application UI, service labels, accessibility descriptions, and update notifications use these resources.

Language preference is handled by Android's per-app locale API. Luminote declares English and Ukrainian in `@xml/locales_config`; an empty application locale list follows the system default. Android persists the selected per-app locale and applies configuration updates, so Luminote does not store a duplicate language preference or manually restart its activity.

If Android provides an application locale outside Luminote's three available choices, Luminote leaves it unchanged until the user selects a language.

Intentionally excluded from localization are internal identifiers and persisted values (route names, enum names, update tag markers, notification channel IDs, preferences/work names, package names, MIME types), the Android-required special-use foreground-service subtype, URLs, log/diagnostic text, and application names supplied by Android or other installed apps. These are not user-facing Luminote copy and changing them could affect behavior.

Android caches notification-channel names and descriptions after first creation. Existing channels may therefore retain their earlier language until Android recreates or the user resets that channel; the channel ID is deliberately unchanged.
