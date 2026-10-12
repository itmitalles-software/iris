# Changelog

All notable Apollo changes. Apollo follows Semantic Versioning; upstream
Hermes Mobile changes merged into Apollo are summarized per sync.

## Unreleased — 0.1.0

First Apollo version, forked from Hermes Mobile `dev` at `689a2d30`
(2026-10-12). It contains the complete upstream feature set (see
[UPSTREAM.md](UPSTREAM.md)) plus:

### Added

- Apollo identity: app name, application ID `io.wutz.apollo`, sun launcher
  icon (adaptive, with themed-icon layer) and notification icon.
- `Apollo` theme preset: true-black AMOLED dark mode with a sun-gold accent and
  a matching light mode. Apollo starts in dark mode; light, system and
  Material You remain selectable.
- German translation of the whole app, chosen automatically from the system
  language or manually in Settings → Language.
- About section credits the upstream project and states that Apollo is not
  affiliated with Nous Research.
- Android share-sheet target: text, links and files shared from any app open
  a new chat with the content staged in the composer (never sent
  automatically). Only `content://` URIs from other apps are accepted; Apollo's
  own FileProvider and `file://` URIs are rejected.
- Large screens: from 840 dp window width the navigation drawer becomes a
  persistent sidebar (the menu button shows/hides it), and the chat column is
  limited to a readable 840 dp.
- Hardware keyboards: Ctrl+Enter (or Meta+Enter) sends the message.

- Apollo design system and brand (docs/BRANDING.md): antique-gold/bronze
  palette on true black with marble light mode, Cinzel display type for brand
  surfaces, the "radiate crown over a Roman A" emblem as adaptive, themed and
  notification icon, APOLLO wordmark, splash screen with the
  "APOLLO / For Hermes Agent / by wutz.io" line, brand lockup on landing and
  About, README banner and store graphics. All assets come from
  `scripts/brand/generate_brand_assets.py`.
- Chat accents in the Apollo theme: bronze user bubbles with a gold hairline,
  gold edge on tool calls (brighter while running).
- About shows the developer (wutz.io) and the independence notice.

### Changed

- System bar icons follow the in-app light/dark choice instead of the system
  night mode, so they stay readable when both differ.
- In-app update checks and release assets point to
  `wutz-io/apollo-hermes-android` (`apollo-<tag>.apk`).
- The release workflow fails early with a clear message when signing secrets
  are missing.
- Own debug signing key; upstream's funding config and stale bot removed.
- Login button reads "Check server" before the first probe instead of the
  misleading "Probing dashboard…".
- The chat context meter ("… / … context") is localized.
- Landing and login screens show the Apollo mark instead of a bolt icon.
