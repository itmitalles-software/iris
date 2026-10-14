# Changelog

All notable Apollo changes. Apollo follows Semantic Versioning; upstream
Hermes Mobile changes merged into Apollo are summarized per sync.

## 0.1.3 — 2026-10-14

### Added

- Purple (#8D4BA3) engraving illustrations with the Apollo theme: on the
  landing screen, the About banner and the empty, empty-chat and offline
  states (when there is enough room). Other themes keep their icons.
- A static website for apollo.wutz.io in `website/` (English/German, light and
  dark). It is not published yet.

### Changed

- The app icon, emblem and store graphics get a finer sunburst and two wavy
  halo rings behind the crown.
- Apollo is now developed independently of Hermes Mobile: it no longer merges
  upstream changes. `UPSTREAM.md` became `ORIGIN.md`; the package-rename sync
  script and the upstream Weblate config are gone. Credit, `LICENSE` and
  `NOTICE` stay.
- CI and Dependabot target `main` only (upstream used a `dev` branch). The PR
  template recovery workflow works on `main` again.

### Removed

- The "Support Hermes Mobile (upstream)" Ko-fi card in Settings. The About
  screen still credits Hermes Mobile.

## 0.1.2 — 2026-10-13

### Fixed

- The remaining English messages (toasts, errors, snackbars, tool titles,
  cron descriptions, relative times, update and MCP import summaries, system
  rows such as "Stopped N background processes.") are now shown in German too
  (#10). Counts use proper singular/plural forms. The cleartext HTTP warning on
  the login and connect screens is localized.
- Disconnecting now cancels a WebSocket handshake that is still in flight, so
  a superseded connection attempt no longer touches auth state (#11).

## 0.1.1 — 2026-10-13

### Added

- `scripts/server/setup-hermes-for-apollo.sh`: one-command Hermes host setup
  for Apollo (login with scrypt hash in a private env file, `hermes serve`
  systemd user service on loopback, self-check, Tailscale Serve hint;
  rerunnable, `--no-service`, `--uninstall`). README explains quick and
  manual setup.

### Fixed

- Every turn of a chat started in Apollo appeared twice after the app was
  restarted (#5, also in upstream Hermes Mobile: Hy4ri/hermes-mobile#1568).
  The first sync of a new session now probes `order=latest` paging, so cached
  rows carry stable row ids instead of absolute positions; a failed probe falls
  back to the previous behavior. Sessions created before this fix keep their
  old cache entries until the app data is cleared.
- Share target hardening: a `0@`/user-id authority prefix can no longer bypass
  the own-FileProvider guard; styled shared text is kept; URIs the sender did
  not grant no longer crash the share trampoline; provider queries run off the
  main thread.
- Rotating or unfolding across the 840 dp sidebar threshold keeps drafts and
  scroll state.
- System bars use `enableEdgeToEdge` with the in-app theme, fixing invisible
  navigation-bar icons on Android 8–9 when app and system theme differ.
- Browser sign-in page names Apollo and is localized.
- Emblem on light surfaces no longer shows a dark outline.
- `setup-hermes-for-apollo.sh`: password from stdin without a trailing newline,
  stricter `--public-url`/`--user` validation, lingering hint.
  `apollo-package-rename.sh` no longer fails when nothing is left to rename.

## 0.1.0 — 2026-10-12

First Apollo version, forked from Hermes Mobile `dev` at `689a2d30`
(2026-10-12). It contains the complete upstream feature set (see
[ORIGIN.md](ORIGIN.md)) plus:

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
