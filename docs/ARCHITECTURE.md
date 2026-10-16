# Architecture conventions

Inherited from the Hermes Mobile `AGENTS.md` (Apollo's origin) and still
authoritative for Apollo's code. Apollo-specific rules live in the
root [AGENTS.md](../AGENTS.md).
This complements [README.md](../README.md) (for humans) and [DESIGN.md](../DESIGN.md)
(visual and interaction requirements) with agent-focused operational and
architecture context. Before changing navigation, state ownership, or module
boundaries, read the relevant implementation and the conventions below. For UI
changes, also read `DESIGN.md`; for theme implementation, read
[`THEMES.md`](../app/src/main/java/io/wutz/apollo/theme/THEMES.md).
Flag conflicts between requirements and implementation instead of silently diverging.

## Project Overview

**Apollo** (started as a fork of Hermes Mobile, independent since 2026-10-13) is a Jetpack Compose
Android app — a mobile control panel for [Hermes Agent](https://hermes-agent.nousresearch.com). It
talks to the Hermes dashboard's REST API and WebSocket TUI Gateway (JSON-RPC 2.0), normally over
HTTPS through Tailscale Serve; plain HTTP is for trusted networks only.

- **Package:** `io.wutz.apollo` (Hermes Mobile: `com.m57.hermescontrol`)
- **Min SDK 26 / Target SDK 37 / Compile SDK 37**
- **Kotlin and KSP:** versions are pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml). KSP uses standalone versioning, NOT `kotlinVersion-kspVersion`.
- **Jetpack Compose**, Room, Navigation3, OkHttp WebSocket, Retrofit, Kotlinx Serialization. See the version catalog and [`app/build.gradle.kts`](../app/build.gradle.kts) for versions and dependency scopes.
- **Auth:** `EncryptedSharedPreferences` (AES256-GCM); connection/authentication flows are documented in [connection-reference.md](connection-reference.md). Trace the active REST and WebSocket auth paths before changing them.

## Build & Test Commands

Requirements and setup (JDK 21 and Android SDK 37, or `nix develop`; ktlint
1.8.0 is resolved by Gradle) are in [BUILD.md](../BUILD.md). The local gate is one task:

```bash
./gradlew gate        # run before every commit and PR
```

It runs `ktlintCheck`, `checkColorLiterals`, `checkGermanStrings`,
`testDebugUnitTest`, `lintDebug`, `assembleDebug`,
`compileDebugAndroidTestKotlin`, `compileReleaseKotlin` and
`checkPlayPermissions`. Single tasks for quicker loops:

```bash
./gradlew testDebugUnitTest --tests '*SomeTest*'   # targeted unit tests (MockK)
./gradlew ktlintFormat ktlintCheck                 # fix, then check style
./gradlew connectedDebugAndroidTest                # Compose UI tests on a device/emulator
```

`ktlintCheck` runs the `ktlint` binary, so a missing binary fails the gate;
there is no Gradle plugin fallback.

### CI pipeline (`.github/workflows/android.yml`)

| Job                  | Purpose                                                                                                                            |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `pr-template`        | PR description check (pull requests only); a failure skips the expensive jobs                                                      |
| `changes`            | path filter; changes outside `app/`, the Gradle files and `android.yml` skip the Gradle jobs                                     |
| `ktlint`             | ktlint 1.8.0 style check + `checkColorLiterals` (hardcoded Color guard, issue #622)                                                |
| `android-lint`       | Android Lint                                                                                                                       |
| `unit-tests`         | JUnit, plus a Kover line-coverage report (debug variant, HTML artifact `coverage-report`, no threshold)                            |
| `build`              | assembleDebug, uploaded as an artifact (runs in parallel with the jobs above, not after them)                                      |
| `release-compile`    | compileReleaseKotlin — catches release-variant compilation issues (e.g. debugImplementation-scoped deps referenced in main source); checkPlayPermissions — the Play manifest has no REQUEST_INSTALL_PACKAGES |
| `instrumented-tests` | Compose UI tests on Android emulator (API 34 ATD)                                                                                  |
| `ci-summary`         | Pure aggregator (`if: always()`, read-only token): the one result to check; merge only when it is green. The release workflow requires it on the tagged commit |

Also: `pr-template.yml` labels PRs by path and comments on incomplete PR
descriptions (not for the owner), `codeql.yml` runs CodeQL, and `website.yml`
(manual only) publishes `website/`.

Every Gradle job validates `gradle-wrapper.jar` and uses the GitHub Actions
Gradle cache of `gradle/actions/setup-gradle` (no remote build cache). A new
push to a PR branch cancels the PR's running CI (`concurrency.cancel-in-progress`).

### Releasing (`.github/workflows/release.yml`)

Triggers on `git push tag v*` (see [BUILD.md](../BUILD.md#releases)). Three jobs:
`verify` (SemVer tag, tag commit on `main` with a green CI Summary, CHANGELOG section
as release notes), `build` (the only job with the signing secrets, environment
`release`, read-only token; checks the signer against `.github/release-cert.sha256`
and the version with `aapt2`) and `publish` (no secrets; `SHA256SUMS`, build
provenance attestation, `gh release create`). The release APK uses R8 minification +
resource shrinking.

## Code Style

- **ktlint 1.8.0** is enforced in CI. With an Android SDK, run `./gradlew ktlintFormat` followed by `./gradlew ktlintCheck`. Standalone formatting is only a fallback; it does not replace the Gradle CI check.
- Import ordering is the #1 CI failure: ktlint enforces ASCII-lexicographic order
  (uppercase before lowercase: `LaunchedEffect` before `collectAsState`).
- `const val` must use SCREAMING_SNAKE_CASE.
- Trailing commas required. No trailing whitespace.
- 120 char max line length.

## Architecture Conventions

### Navigation (Navigation3 — NOT Navigation Compose)

Uses `androidx.navigation3` (`NavKey`, `NavBackStack`, `NavDisplay`, `entry<T>`).
The back stack is created with `rememberNavBackStack`, so it survives configuration
changes (the manifest declares no `configChanges`); every `NavKey` must therefore stay
`@Serializable` (`NavigationBackStackSaveTest`).

**⚠ Always route navigation through `NavigationController.navigateTo()`.** Never call
`backStack.add(ScreenKey)` directly from UI callbacks — the controller has a
deduplication guard. Bypassing it stacks duplicate screen entries that compete for
touch events and become unresponsive.

**Drawer gesture state is screen-owned (issue #619).** Each screen declares whether
the modal drawer's swipe gestures should be active while it is visible, via a single
source of truth — no global gesture set, no defensive `LaunchedEffect(snapTo(Closed))`,
no `closeDrawer` callback on `NavigationController`.

- `HermesScaffold(drawerGesturesEnabled = true)` — default; primary screens and
  most list screens. The scaffold reconciles this preference into the
  `DrawerGestureController` via a `SideEffect`.
- `HermesScaffold(drawerGesturesEnabled = false)` — drill-down sub-pages (e.g.
  `SettingsConnectionPage`, `SettingsAppearancePage`, …). The controller closes
  the drawer itself if it was open when the screen composed, so the scrim can't
  stick around and intercept the next tap.
- `DisableDrawerGestures()` — for entry screens that don't use `HermesScaffold`
  (Landing, AuthLogin, PairingCodeEntry).

`ModalNavigationDrawer` in `Navigation.kt` reads `gestureController.enabled`
(provided via `LocalDrawerGestureController`) and passes it to Material's
`gesturesEnabled` parameter. To change a screen's gesture behavior, edit the
screen — not `Navigation.kt`.

### Activity-Scoped ViewModels

Some ViewModels (e.g. `AuthLoginViewModel`) are created at the Activity scope via
`viewModel()` at the navigation entry level. This means they **survive navigation**
and cached state like `connectionSuccess` persists across screen entries.

Always pair transient state flags with a `DisposableEffect` cleanup:

```kotlin
DisposableEffect(Unit) {
    onDispose { viewModel.clearTransientState() }
}
```

The cleanup function should reset self-transitioning flags (`connectionSuccess`,
`isLoading`, `errorMessage`) so the screen can re-enter cleanly.

NavDisplay has no per-entry ViewModel store, so polling loops and sockets owned by such
a ViewModel keep running after the screen is gone unless the screen stops them. Screens
with live updates (Kanban board and task detail) call `attachScreen()` /
`detachScreen()` from a `DisposableEffect`; the ViewModel counts attached screens (two
entries of the same screen can overlap during a transition) and stops polling and its
socket when the count drops to zero.

### WebSocket Lifecycle

`HermesWsClient` is a singleton that outlives individual screens. It must be
explicitly disconnected on logout and reconnected after login:

| Event         | Action                                                 |
| ------------- | ------------------------------------------------------ |
| Logout        | `HermesWsClient.disconnect()` before clearing tokens   |
| Login success | `HermesWsClient.connect()` after `ApiClient.rebuild()` |

The singleton's `connect()` has a guard (`if connected → skip`) so it's safe to
call unconditionally.

### Typed Gateway RPC

Every gateway method in the contract (`app/src/test/resources/gateway-contract/openrpc.json`) is called through a
typed descriptor: `HermesWsClient.call(RpcMethods.X, params)` (suspending), `requestTyped(...)`, or the typed
`send(RpcMethods.X, params, onSent)`. Params are `@Serializable` classes under `data/ws/contract/`; results are
`JsonElement` passthrough or typed. To add a method: add the params class, register it in `RpcMethods` (and
`RpcMethods.all`), and `GatewayContractTest` checks it against the contract.

- The untyped `request(String, Map)` / `send(String, Map)` are `internal`. Use them only for `gateway.ping` and the
  legacy `*.respond` fallbacks (`clarify`, `sudo`, `secret`, `vault.*`) that have no contract entry.
- Optional params are `T? = null`: the client Json has `encodeDefaults = false`, so unset fields are omitted and an
  explicit `false`/`""` is still sent. Do not add a `profile` field unless callers pass an explicit override;
  `WsProfileParams.decorate` injects it for profile-scoped methods.
- `RawRpcParamsRatchetTest.BASELINE` must stay empty.

### ChatViewModel delegates

`ChatViewModel` coordinates session state, history and the send pipeline; cohesive
features live in delegates in `ui/chat/` that get `uiState`, the scope, dispatchers
and narrow lambdas: request tracking (`ChatRequestTracker`), timeline and history
windows (`ChatTimelineDelegate`), the context meter (`ChatContextUsageDelegate`),
`/compress` (`ChatCompressionDelegate`) with the ordered local transcript writes
(`ChatLocalTranscriptWriter`), slash commands (`ChatSlashCommandsDelegate`), plus
approvals, clarify, credential prompts, model switch, streaming, search, media,
subagents and processes. Keep the VM's public API as thin forwarders and add new
features as delegates rather than growing the VM.

`ChatSessionContext` owns the generation and runtime session ID used by those
delegates. It reads the stored conversation ID and server/profile scope from
their existing owners. Its separate guards preserve three different rules:
events accept stored/runtime aliases and legacy unscoped events; history/RPC
results match the stored ID and generation (plus receipt scope when present);
sends require an exact stored ID, runtime ID, scope and generation. The request
tracker adds request-ID bookkeeping and resume-attempt sequencing to that
shared context. Session cleanup, job cancellation and their ordering stay in
the ViewModel; history paging and send-pipeline extraction remain follow-ups
under #31, within the larger #65 refactoring pass.

### Voice conversation

The hands-free voice mode (`ui/chat/voice/`) is owned by `ChatViewModel`.
`VoiceConversationMachine` is a pure state machine (listen → transcribe → send →
speak → listen, barge-in, shutdown); `VoiceConversationController` runs its
effects against a per-conversation `VoiceEngine` (`AndroidVoiceEngine`: mic +
`VoiceActivityDetector`, live STT with upload fallback, `ReplySpeech` for the
streamed reply). The audio sockets (`data/voice/`) authenticate like `/api/ws`
(a fresh `mintWsTicket()` credential per socket) and carry `?profile=`
explicitly because the OkHttp WebSocket client has no `ProfileScopeInterceptor`.
`ChatScreen` stops the mode on ON_STOP, disposal and session switches; losing
the audio focus (call, navigation) ends it too.

Reply events carry no turn id. Transcripts are therefore sent as plain text
(no attachments, no slash commands) with `INTERRUPT` (after a barge-in) or
`QUEUE`, never steered/redirected into a running turn. When a turn was still
running at send time, the machine (`ReplyTurn.BEHIND_RUNNING_TURN`) ignores
that turn's `message.complete` and adopts only the next `message.start`.

### Shared Components

- **`HermesScaffold`** — drawer-aware Scaffold + TopAppBar with refresh slot,
  pull-to-refresh, optional snackbar host. New screens MUST use this instead of raw
  `Scaffold`. API: `title: @Composable () -> Unit` (not `String` — wrap in
  `{ Text("...") }`).
- **`StateViews`** — `LoadingState`, `ErrorState`, `EmptyState`. Every data screen
  must implement all three branches in its `when { }` block.

### ⚠ HermesScaffold Padding Foot-Gun

`HermesScaffold` handles system insets and the dynamic top-bar offset in its
internal content wrapper. Although it passes `paddingValues` to the content
lambda, applying those values again double-stacks the padding:

```kotlin
HermesScaffold(...) { paddingValues ->      // ← scaffold already handles top bar offset via Box
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),         // ← ❌ double-stacked top padding
        contentPadding = listContentPadding, // ← extra 8dp, now triple-stacked
    ) { ... }
}
```

**Correct pattern — do NOT apply `paddingValues` on inner content:**

```kotlin
// ✅ List screens:
LazyColumn(
    modifier = Modifier.fillMaxSize(),    // no .padding(paddingValues)!!
    contentPadding = listContentPadding,  // this is the ONLY padding needed
    verticalArrangement = listItemSpacing,
)

// ✅ Non-list screens (Column/Box wrapping):
Column(
    modifier = Modifier.fillMaxSize(),    // no .padding(paddingValues)!!
) { ... }

// ✅ Loading/Error/Empty states inside HermesScaffold content follow the same rule:
LoadingState(modifier = Modifier.fillMaxSize())
```

This applies to every branch inside the content lambda. A state view outside
`HermesScaffold` must follow its own parent layout's inset contract instead.
See [`HermesScaffold.kt`](../app/src/main/java/io/wutz/apollo/ui/common/HermesScaffold.kt)
for the current inset implementation.

### Room Persistence

- `ChatMessageEntity` / `ChatMessageDao` / `HermesDatabase` — chat messages survive
  app kills. `getMessagesForSession()` returns `suspend fun ...: List<ChatMessageEntity>`
  (not `Flow` — the caller controls the coroutine scope).
- Room schema configuration is defined in `app/build.gradle.kts`; preserve schema export and migrations when updating Room.
- `ChatViewModel` extends `AndroidViewModel` (needs Application for DB access).

### Theme

`Theme.kt` uses a preset-based theme system with 8 built-in presets: Apollo (the
default, see [BRANDING.md](BRANDING.md)), Default, Monochrome, Gruvbox, Catppuccin,
AMOLED, Nord, and Garnet. Each preset provides light and dark color schemes plus
matching semantic status colors (AMOLED is dark-only — light mode falls back to the
Default palette). Only the Apollo preset switches the display styles to Cinzel
(`ApolloTypography`) and enables the Apollo chat accents.
Every preset is a `ThemePalette` template fill (`PaletteTemplate.kt`): raw
`PaletteColors` + `HermesStatusColors` mapped into the Material 3
`ColorScheme` slots, with the error slots derived from the status set.

Dynamic color (Material You on API 31+) is controlled by a `useDynamicColors`
parameter on `HermesControlTheme`, not a compile-time constant. When dynamic
colors are active they override the preset's `ColorScheme`, but semantic status
colors (success/warning/error/info) are ALWAYS resolved from the active preset
via `LocalHermesStatusColors` — never from `MaterialTheme.colorScheme`.

Access status colors via `LocalHermesStatusColors.current.success`, not
`MaterialTheme.colorScheme`.

## Git Workflow

### PR-Always (ENFORCED)

**Every change goes through a PR targeting `main`. Never push directly to `main`.**

```bash
git switch main && git pull origin main
git switch -c fix/issue-N-description      # or feat/...
# make changes, run ./gradlew ktlintFormat gate
git commit -m "fix(#N): description"
git push -u origin HEAD
gh pr create --base main --title "fix(#N): description" --body "Closes #N"
```

### Commit Conventions — STRICT

**⚠ Agent-authored commits:** Do NOT override git authorship (`--author`) with the agent's identity. Use the default git config. A `Co-Authored-By` trailer for the agent is allowed.

- **Short commits:** subject line + max 2 lines of body. Split into atomic commits rather than writing long bodies.
- **Conventional Commits** types: `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `chore`.
- Bug fixes annotated inline with the tracking issue/regression ID.

## Security Considerations

- **HTTPS is the default path** (Tailscale Serve, private CAs, mTLS client certificates;
  see [connection-reference.md](connection-reference.md)). Server certificate and hostname
  verification always stay on.
- **Cleartext HTTP/WS stays allowed on purpose** for trusted networks, emulators and
  `adb reverse` (`network_security_config.xml`, the `usesCleartextTraffic` manifest
  placeholder). The login screen warns about it; do not remove it without an owner decision.
- **HTTP logging** is `HttpLoggingInterceptor.Level.BASIC` in debug builds and `NONE` in
  release builds (`ApiClient`). Never raise it to `HEADERS` or `BODY`: both print the
  `Authorization` header and cookies.
- **Token mode only:** the dashboard session token is ephemeral — it becomes invalid
  when Hermes restarts. Symptom: 401 on every call. Fix: re-extract the token from the
  running server, don't assume the stored one is valid.
- **Release signing** uses a keystore with env-var credentials (`KEYSTORE_PATH`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Don't commit keystore files or
  hardcode passwords.

## Things to Avoid

- Don't skip the local gate (`./gradlew gate`). CI runs the same checks plus the emulator
  tests, but a red CI run costs a full cycle.
- Don't remove `@OptIn(ExperimentalMaterial3Api::class)` from screens that use
  `SegmentedButton`, `PullToRefreshBox`, or other experimental M3 APIs.
- Don't access `MaterialTheme.colorScheme.*` inside non-`@Composable` lambdas
  (`remember {}`, `buildAnnotatedString`, `LaunchedEffect`). Extract to a local
  `val` at the composable scope first.
- Don't add new screens without checking if an existing one already covers the
  functionality (30+ screens exist). Extend rather than duplicate.
- **⚠ Never scope a dependency to `debugImplementation` if its import is used in
  `main/` source code.** The CI `release-compile` job catches this, but save the
  cycle. `okhttp3.logging.HttpLoggingInterceptor` is the classic example — it's
  imported in `ApiClient.kt` (main source) so it must be `implementation`, not
  `debugImplementation`. If you want it debug-only, wrap the usage in
  `if (BuildConfig.DEBUG)` or extract it behind an interface.

### Hardcoded Color Guard (issue #622)

A custom Gradle task `checkColorLiterals` fails the build if `Color(0x...)` hex
literals or named `Color.White`/`Color.Black`/etc. appear outside the allowed
paths (`theme/`, `*Preview.kt`, `PairingScreen.kt`, `AuthLoginScreen.kt`).

Use `MaterialTheme.colorScheme.<token>`, `LocalHermesStatusColors.current.<semantic>`,
or `Color.Transparent` / `Color.Unspecified` instead. The guard runs in CI as part
of the `ktlint` job.

## Testing Conventions

The unit-test suite runs about 3,700 JVM tests in two forks that share
JVM-global singletons (`AuthManager`, `HermesWsClient`, `ApiClient`,
`ActiveSessionHolder`, `SwrCache`). Leaks between test classes turn into
load-dependent flakes, so:

- **Virtual time, no sleeps.** Use `runTest` with a `StandardTestDispatcher`
  installed as Main (`test:testutil/MainDispatcherRule.kt`, or `Dispatchers.setMain`/
  `resetMain` by hand), and advance with `advanceUntilIdle()`/`advanceTimeBy()`.
  Replace `Thread.sleep` with virtual time or a latch.
- **ChatViewModel tests use `ChatViewModelHarness`** (`test:ui/chat/`), a JUnit rule
  that stubs the singletons and creates view models on the test scheduler. The
  per-feature classes (`ChatViewModelSendTest`, `...HistoryTest`, `...SlashTest`, ...)
  extend `ChatViewModelTestBase`. Production timers come from an injected
  `ChatTimingConfig`: tests get `ChatTimingConfig.TEST` (no timers, unthrottled
  streaming) and enable single timers to test them (`ChatViewModelTimingTest`); there
  is no hidden test mode in production code.
- **Never mock `Dispatchers` statically** (`mockkStatic(Dispatchers::class)`). The mock
  bleeds JVM-wide into later test classes, and singleton scopes created while it is
  active stay bound to a dispatcher nobody advances. Inject an `ioDispatcher`
  constructor parameter (default `Dispatchers.IO`) instead; `safeLaunchLoad`
  (`ui/common/ViewModelExt.kt`) deliberately has no IO hop.
- **Subscribe before you trigger.** `HermesWsClient.events` has no replay. Start
  collectors with `async(start = CoroutineStart.UNDISPATCHED)` or wait on an
  `onSubscription` latch before `connect()` or before the server sends.
- **Close mock WebSockets.** Use `MockResponse().withClosingWebSocketUpgrade(listener)`
  (`test:data/ws/MockWebSocketCleanup.kt`), not plain `withWebSocketUpgrade`.
  Otherwise the server never answers the client's close frame and every
  `MockWebServer.shutdown()` waits about five seconds.
- **Identify requests by method, not by position or id counter**, for example
  `sentRequestMethods.last { it.first == WsMethods.SESSION_CREATE }`. A late extra send
  shifts ids and counters.
- **Stub the global flows a ViewModel reads** (`AuthManager.activeProfileId`,
  `dataScopeFlow`) in `setUp`, and undo every `mockkObject`/`mockkStatic` in teardown.
- **User-visible ViewModel messages are `UiText`** (`ui/common/UiText.kt`). Assert the
  resource id and arguments (`assertEquals(UiText.res(R.string.x, "a"), state.toastMessage)`),
  not English text.
- **Request failures go through `NetworkError.toUiText()`** (`ui/common/NetworkErrorText.kt`):
  localized status texts, server details verbatim. The `safeLaunch*` `onError` callbacks get
  the typed `NetworkError`. Screen messages that report a failure are `UiText.failure(...)`;
  `ToastEffect` shows them as an error snackbar with "Copy", and offers "Undo" when the
  screen passes `onUndo` (sessions archive/pin, projects archive).
- **No real device storage.** Use `TemporaryFolder` or Room's in-memory builder.
- After changing a signature used by `app/src/androidTest`, the gate's
  `compileDebugAndroidTestKotlin` must pass.

## Project Layout

```
io.wutz.apollo/
├── data/
│   ├── config/     ServerStore, ConnectionProfile, migrations
│   ├── local/      AuthManager, Room (ChatMessageEntity/Dao, HermesDatabase), AnalyticsCacheStore
│   ├── model/      40+ data classes for API responses + requests
│   ├── remote/     ApiClient, Retrofit service, OkHttp provider, cookie management
│   ├── repository/ KanbanRepository, KanbanDispatcherNudger, VoiceNoteRepository
│   ├── session/    ActiveSessionHolder, ProfileSwitchCoordinator
│   ├── update/     AppUpdateChecker, AppUpdateCache, AppUpdateState
│   ├── voice/      speak-stream / transcribe-stream socket protocol and clients
│   └── ws/         HermesWsClient, JSON-RPC models, WsEvent, BillingRepository
├── assistant/      digital-assistant role (voice interaction service and session)
├── notification/   ChatNotificationService, NotificationReplyReceiver
├── share/          ShareTargetActivity, ShareInbox (Android share sheet into a chat)
├── theme/          Color, Theme, Motion, Spacing, Shapes, Type, ApolloType, SystemBars, HermesStatusColors
│   └── presets/    Apollo, Default, Monochrome, Gruvbox, Catppuccin, AMOLED, Nord, Garnet
├── ui/
│   ├── common/     HermesScaffold, StateViews, SharedComponents, DetailDialog, DetailRows
│   └── 30 feature packages (achievements, analytics, authlogin, billing, bots,
│       channels, chat, config, connectors, cron, files, gateway, kanban,
│       keys, landing, logs, mcp, memory, model, pairing, plugins, profiles,
│       projects, providers, sessions, settings, skills, system, toolsets,
│       webhooks)
├── util/           CronExpressionFormatter, LocaleContextWrapper, FormatUtils, BidiUtils, …
├── HermesControlApp.kt     Application class
├── Navigation.kt           Drawer + NavDisplay + entry wiring
├── NavigationController.kt Central navigation guard (dedup)
├── NavigationKeys.kt       @Serializable NavKey data objects (32 screens + 4 detail keys + 7 settings sub-pages)
├── ScreenRegistry.kt       entry<T> registrations for all NavKeys
└── MainActivity.kt
```

## Further Reading

- [README.md](../README.md) — human-facing overview, features, screenshots, tech stack
- [DESIGN.md](../DESIGN.md) — visual and interaction requirements, accessibility, and token source map
- [THEMES.md](../app/src/main/java/io/wutz/apollo/theme/THEMES.md) — theme template, dispatcher, and mode fallback implementation
- [Gateway contract test](../CONTRIBUTING.md#gateway-contract-snapshot) — backend drift check; refresh with `scripts/sync-gateway-contract.sh`
- [CONTRIBUTING.md](../CONTRIBUTING.md) — contributor workflow, PR checklist, code style
- [.github/workflows/android.yml](../.github/workflows/android.yml) — CI pipeline source of truth
