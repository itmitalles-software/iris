# Origin

Apollo started as a fork of [Hermes Mobile](https://github.com/Hy4ri/hermes-mobile)
by Hy4ri (Apache-2.0). Since 2026-10-13 it is an independent project: it no
longer tracks or merges upstream. The upstream history up to the fork point is
kept in Git, and the original copyright and license notices stay in
[LICENSE](LICENSE) and [NOTICE](NOTICE) as Apache-2.0 requires.

| | |
| --- | --- |
| Original project | `https://github.com/Hy4ri/hermes-mobile` |
| Fork point | `689a2d30` — `fix(#1540): treat kanban 1008 close as board unavailable` (2026-10-12) |
| Original package | `com.m57.hermescontrol` |
| Apollo package / application ID | `io.wutz.apollo` (debug: `io.wutz.apollo.dev`) |

Taking over a single upstream fix later is still possible: port it by hand
(the package and many files differ), credit it in the commit message
(`Ported from Hy4ri/hermes-mobile@<sha>`), and keep its license notice.

## What Apollo changed before going independent

Record of the divergence from Hermes Mobile at the fork point. Later changes
are in `CHANGELOG.md`.

- **Package rename** — `com.m57.hermescontrol` → `io.wutz.apollo` (source
  sets, Room schema directories, ProGuard rules, XML references, CI path
  filters).
- **Branding** — app name, launcher/notification icons, About section (Apollo
  repository, upstream credit, "not affiliated with Nous Research"), support
  section pointed to the upstream project (removed when going independent), own
  debug keystore.
- **Theme** — new `ThemePreset.APOLLO` (true-black dark, sun-gold accent, light
  counterpart) is the default; the default theme mode is `DARK`. System bar icon
  colors follow the in-app theme instead of the system night mode.
- **German** — `values-de/` and a `de` entry in the language picker. Hardcoded
  English UI literals across `ui/**` and `notification/` were moved to string
  resources (block "Apollo i18n" at the end of both `strings.xml`). Fixed local
  system rows stay English in storage and are translated at display time via
  `ui/chat/SystemNotice.kt` (#8); formatters take label resolvers
  (`buildReasoningLabel`, `KanbanModelOverride.displayLabel`,
  `KanbanEventDetailMessage`, `AuxTaskInfo` resource ids).
  ViewModel messages (`toastMessage`, `errorMessage`, snackbars) are
  `ui/common/UiText` (resource id + args, or raw server text) and are resolved
  in the composables; pure formatters return `UiText` or take a resolver
  (`ui/chat/tool/ToolText` for the tool renderers). Parameterized system rows
  ("Stopped N background processes.", "↶ Rewound to: …") stay English in
  storage and are parsed back into resources by `SystemNotice.displayUiText`
  (#10). New keys live in the block "Apollo i18n (ViewModel messages, issue
  #10)".
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
