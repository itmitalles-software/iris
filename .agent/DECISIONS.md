# Iris — owner decisions

Durable decisions by the owner, with date and source. Verified state lives in
`STATE.md`; open work lives in GitHub Issues. Add new decisions here instead of
burying them in dated log entries.

## Product and project

- **Renamed from Apollo to Iris** (2026-10-17, owner: "es heisst nicht mehr
  apollo"; scope confirmed 2026-11-17): everything user-visible, the code
  identifiers and the brand art become Iris; the application ID and Kotlin
  package `io.wutz.apollo` stay so installed apps keep updating, and persisted
  keys keep their names. Repository `itmitalles-software/iris` (was
  `wutz-io/apollo-hermes-android`). The emblem is a capital I over a gold
  rainbow; the Apollo illustrations are replaced by an Iris batch
  (`docs/brand/art/iris-art-spec.md`).
- **Independent of Hermes Mobile** (2026-10-13). Apollo no longer merges
  upstream changes and has no `upstream` remote. Fixes from Hermes Mobile may be
  ported by hand with credit (`Ported from Hy4ri/hermes-mobile@<sha>`).
  Credit, `LICENSE` and `NOTICE` stay. See `ORIGIN.md`.
- **Posting to the upstream tracker needs the owner's OK** (2026-10-12). The #5
  report was posted with that OK (Hy4ri/hermes-mobile#1568).
- **No tailnet host names or credentials in the repository** (2026-10-13). History
  was rewritten with the owner's OK to remove the tailnet host name; docs use
  `<machine>.<tailnet>.ts.net`.
- **Release key stays offline with the owner** (2026-10-12); CI gets it only through
  the repository secrets. Signer SHA-256
  `f6ff6e51356e8aa37eb23a518dee62691fad0a1eebb9c68298a0d0bc9880fb3e` (public).
- **Google Play as a `play` build type, not a product flavor** (2026-10-14, #7,
  PR #19): existing task names and outputs stay unchanged; the Play build has no
  self-updater and no `REQUEST_INSTALL_PACKAGES`. Play publishing is manual.

## Look and feel

- **Dark theme by default** (2026-10-13, explicit owner requirement; kept
  deliberately in the code review of that day).
- **Keep the gold Apollo theme** (2026-10-14, art review
  `~/reviews/apollo-art-2026-10-13/review-decision.json`): the black-and-white-only
  UI was dropped; `ApolloScheme` (now `IrisScheme`, the Iris theme) stays unchanged.
- **Art direction** (same review): purple (#8D4BA3) engraving illustrations, drawn
  as lines on black in dark mode; the new vector emblem is the app icon; the
  painted gold logo is used on the website. Style references for the art are
  third-party screenshots and are never committed.

## Website

- **Live at iris.wutz.io** (2026-11-17, owner: "schalt sie live unter
  iris.wutz.io", after the rebrand and before the Iris art batch; the Apollo
  illustrations are swapped once the batch is ingested). Imprint and privacy policy: the footer links
  https://itmitalles.de/impressum and https://itmitalles.de/datenschutz; the
  page's own privacy section covers the app (#33). The earlier hold
  (2026-10-14, "merge only, website not live") ends with that go-live.
  Setup: Pages build type workflow, custom domain iris.wutz.io, Cloudflare
  CNAME iris → itmitalles-software.github.io (DNS only, account
  tim@itmitalles.de); `website.yml` deploys on pushes to `website/**` on main.

## Code review 2026-10-14

- **Fix every finding, hotfix S1-S3 first** (owner confirmed in
  `~/reviews/apollo-code-review-2026-10-14/`): v0.1.5 shipped the hotfix, PRs
  #22-#27 the rest, v0.1.6 the release.
- **Third-party Markdown images load only after a tap**; only http, https and
  mailto links are clickable (security fix, shipped in v0.1.6).

## Earlier code review (2026-10-13)

Deliberately left as they were: the default dark theme (see above), a shared
URI-metadata helper and an auth-eligibility helper, the variant system rows and
the notice-kind design. The reason then was a small upstream diff, which no
longer applies since Apollo is independent; revisit only with a concrete need.

## Localization (2026-10-13, #10)

- User-visible ViewModel messages are `UiText` (EN/DE). Still English by design:
  data-layer fallbacks passed through as raw arguments (`NetworkError` HTTP texts,
  `ConnectorError` messages, Projects "Request failed"), TUI-style slash-command
  output, wire values, and parameterized system rows stored in Room (they are
  parsed back by `SystemNotice.displayUiText`).
- Only English and German are maintained; the other locales from Hermes Mobile
  fall back to English for new strings.
