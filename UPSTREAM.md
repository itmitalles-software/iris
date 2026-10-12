# Upstream relationship

Apollo is a fork of [Hermes Mobile](https://github.com/Hy4ri/hermes-mobile)
(Apache-2.0). The full upstream history is preserved, so upstream fixes can be
merged instead of re-implemented.

| | |
| --- | --- |
| Upstream repository | `https://github.com/Hy4ri/hermes-mobile` |
| Upstream branch | `dev` (integration branch; releases are tagged from `main`) |
| Fork point | `689a2d30` — `fix(#1540): treat kanban 1008 close as board unavailable` (2026-10-12) |
| Upstream package | `com.m57.hermescontrol` |
| Apollo package / application ID | `io.wutz.apollo` (debug: `io.wutz.apollo.dev`) |

## What Apollo changes

Keep this list current. It is the checklist for resolving merge conflicts.

- **Package rename** — `com.m57.hermescontrol` → `io.wutz.apollo` via
  `scripts/apollo-package-rename.sh` (source sets, Room schema directories,
  ProGuard rules, XML references, CI path filters).
- **Branding** — app name, launcher/notification icons, About section (Apollo
  repository, upstream credit, "not affiliated with Nous Research"), support
  section points to the upstream project, own debug keystore.
- **Theme** — new `ThemePreset.APOLLO` (true-black dark, sun-gold accent, light
  counterpart) is the default; the default theme mode is `DARK`. System bar icon
  colors follow the in-app theme instead of the system night mode.
- **German** — `values-de/` and a `de` entry in the language picker.
- **Update checker / releases** — release assets are `apollo-<tag>.apk` from
  `wutz-io/apollo-hermes-android`; the release workflow fails early when
  signing secrets are missing.
- **CI** — PR-template gate exempts repository members; the stale bot and
  upstream funding config are removed.
- **Share target** — `share/ShareInbox.kt` and `share/ShareTargetActivity.kt`
  (manifest entry), consumed in `MainActivity.consumeShareIntent`,
  `ChatLifecycleEffects` and `ChatViewModel.stageSharedContent`.
- **Large screens** — `Navigation.kt` (persistent sidebar ≥ 840 dp; drawer
  content extracted into `drawerItems`), `ChatScreen.kt` (`CHAT_MAX_CONTENT_WIDTH`).
- **Keyboard** — `ComposerKeyboardShortcuts.kt`, hooked into `ChatComposer.kt`.
- **Design system / brand** — `theme/presets/ApolloScheme.kt` (generated
  palette), `theme/ApolloType.kt` + `res/font/cinzel.ttf`, typography switch in
  `Theme.kt`, `ui/common/ApolloBrand.kt`, Apollo-only accents in
  `UserBubble.kt` and `ToolBubble.kt`, drawer header in `Navigation.kt`,
  splash themes (`values/themes.xml`, `values-v31/themes.xml`), generated
  drawables (see `docs/BRANDING.md`).
- **Small UI fixes** — Apollo mark on landing/login, `auth_login_action_probing`
  label, localized `context_usage_label`.
- Additional Apollo features are listed in `CHANGELOG.md`.

## Syncing upstream changes

```bash
git remote add upstream https://github.com/Hy4ri/hermes-mobile.git  # once
git fetch upstream
git switch -c sync/upstream-$(date +%Y%m%d)
git merge upstream/dev            # or a release tag, e.g. v2.5.0
# Resolve conflicts with the list above in mind, then move any files that
# upstream added under the old package and rewrite their package lines:
./scripts/apollo-package-rename.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
git commit -am "Merge upstream Hermes Mobile <ref>"
```

Notes:

- Git's rename detection maps upstream edits of
  `com/m57/hermescontrol/...` onto `io/wutz/apollo/...`. If a large merge
  reports rename conflicts, raise the limit: `git config merge.renameLimit 20000`.
- New upstream strings arrive in `values/strings.xml` only; add the German
  translations to `values-de/strings.xml` in the same sync branch.
- Upstream tags are not pushed to the Apollo repository: Apollo has its own
  version line (see `BUILD.md`).
- Cherry-picking a single upstream fix works the same way:
  `git cherry-pick -x <sha>` followed by `./scripts/apollo-package-rename.sh`.
