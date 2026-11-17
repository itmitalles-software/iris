# Iris (formerly Apollo) — verified state

_Last updated: 2026-11-17_

Owner decisions are in `DECISIONS.md`; open work is in GitHub Issues
(#4 real-phone check, #28 credential recovery after a device transfer, and
the deferred improvement items #29-#41).

## Repository

- GitHub: `itmitalles-software/iris` (public again since 2026-11-17; it was
  private after the move from `wutz-io/apollo-hermes-android`, which broke the
  unauthenticated update check). The old URL redirects, also for the API.
  Branch `main`. Rulesets: no force-push or deletion on main (direct pushes
  allowed), `v*` tags admin-only. Merge when CI Summary is green.
- Renamed Apollo → Iris (decision in `DECISIONS.md`): application ID and
  package stay `io.wutz.apollo`; persisted keys, intent extras, keystore alias
  and `CN=Apollo` signer keep their names. Release assets are `iris-<tag>.apk`
  from the first Iris release on; the update checker accepts any `.apk`.
- Started as a fork of `Hy4ri/hermes-mobile` `dev` at `689a2d30`; independent
  since 2026-10-13 (see `ORIGIN.md`). No `upstream` remote; `gh repo
  set-default` points at `itmitalles-software/iris`.
- Package / application ID `io.wutz.apollo` (debug `io.wutz.apollo.dev`).
- History was rewritten on 2026-10-13 (commits from 89b129b2 on no longer
  contain the tailnet host name); the old refs are in the owner's local bundle
  `~/local-archive/apollo-hermes-android-before-history-rewrite-20261013.bundle`.
  GitHub may still serve old commits by SHA until support purges them.
- Branch `pr-assets-13` only holds a PR screenshot, not app code.

## Releases

- Latest: v0.1.6 (versionCode 106), released 2026-10-14 from the full code
  review (v0.1.5 = security hotfix S1-S3, PR #21; PRs #22-#27 the rest).
- Release APKs are signed with the owner's offline release key; signer SHA-256
  `f6ff6e51356e8aa37eb23a518dee62691fad0a1eebb9c68298a0d0bc9880fb3e`
  (`CN=Apollo, O=wutz.io, C=DE`), verified on the downloaded v0.1.4 and v0.1.6
  assets.
- Release pitfall: a v0.1.5 release run failed because Maven Central briefly
  404'd `compose-group-mapping:2.4.21`; rerunning the failed job worked.

## Toolchain and gate

- Local toolchain: Temurin 21 (`~/.cache/codex-toolchains/temurin21`),
  SDK `~/Android/Sdk` (platform 37, build-tools 37), ktlint 1.8.0 in
  `~/.cache/codex-toolchains/ktlint-1.8.0`. `nix develop` provides the same.
- Local gate: `./gradlew gate` (includes `compileDebugAndroidTestKotlin`; CI
  once caught an androidTest compile break that the old hand-written gate
  missed, #27).
- Last combined gate (2026-10-14, before merging #22-#27): 3,701 unit tests
  green, Lint 0 errors. GitHub CI on main green, including instrumented tests
  (API 34 ATD). CodeQL default setup is disabled so `codeql.yml` runs.
- Known load-sensitive tests (pass alone, failed once under heavy load):
  `HermesWsClientTest.testOpenRequestsReplayUsesTheLiveServerRequestDispatcher`
  and the `ChatViewModelSendTest` case `sendMessage while session create is in
  flight…`.

## Verified against a live Hermes

- Hermes Agent v0.21.6, `hermes serve` with `HERMES_DASHBOARD_PUBLIC_URL` and
  basic auth. Emulator `apollo_api37` (API 37, port 5580) via `adb reverse`,
  and the signed release build against the owner's server through Tailscale
  Serve (the owner's host runs an equivalent `hermes-serve` user service on
  127.0.0.1:9119, published tailnet-only).
- Verified: basic-auth login, streaming chat, tool calls, session list and
  search, share sheet → upload → agent reads the file, German UI, light/dark,
  tablet layout (2560×1600), splash/landing/About, sudo prompt, completion
  notification and inline reply, background prompt notifications (#3),
  ViewModel messages EN/DE (#10), and the 2026-10-14 review fixes (S1 cookie
  probe, S7 forged intent, notification taps, rotation/locale, dialogs EN/DE
  dark/light, voice panel).
- Voice protocol verified against a real server from the JVM.
- Not verified on a device: the spoken voice loop (#4; the emulator's
  PulseAudio input fails on this PipeWire host), P1 transfer, P2 offline reply,
  N6 on API 37, N14 Keystore migration, background connection under Doze (#4).
- 401 pitfall: the password gate is active only for a non-loopback
  `public_url`; otherwise `/api/*` wants the session token. The recipe below
  therefore uses `http://10.0.2.2:19120`.

## Conventions added by the 2026-10-14 fixes

Kanban VMs `attachScreen/detachScreen` from a DisposableEffect (VMs stay
Activity-scoped); back stack via `rememberNavBackStack`; non-GET requests are
never replayed (`NonReplayableFailureInterceptor` + `safeApiCall`);
notification taps go through the non-exported alias `.NotificationEntryAlias`;
session cookies only for the active server (`CookieManager.isActiveServerUrl`),
GitHub calls through the cookie-less `OkHttpProvider.external`. Testing
conventions are in `docs/ARCHITECTURE.md`.

## 2026-10-14: improvement round (v0.2.0)

- Six read-only audits (UX/a11y, performance, code health, build/CI, docs,
  features vs. the Hermes API) produced 116 proposals; reports and the owner's
  decision page are in `~/reviews/apollo-improvements-2026-10-14/` (local).
  The owner confirmed all recommendations; deferred items are issues #29-#41.
- Wave 1 = PRs #42-#48, wave 2 = PR #54 (one merge commit per area).
- Release workflow: verify (SemVer tag on main, green CI Summary, CHANGELOG
  section) -> build (environment `release`, the only job with the signing
  secrets) -> publish (SHA256SUMS, provenance). The signer is pinned in
  `.github/release-cert.sha256`; release notes come from CHANGELOG.md via
  `scripts/release-notes.sh`. A tag must point at a main commit whose CI
  Summary passed (older main commits are often cancelled by concurrency).
- Signing secrets live in the `release` environment (tag policy `v*`); the
  repository-level copies are deleted once a release built from the
  environment succeeded.
- Gate on the merged state: 3,892 unit tests green; the unsigned Play APK is
  12.77 MB (no x86, R8 without blanket keeps, app locales only).
- Emulator-verified (API 37, R8 build, isolated gated hermes serve): login via
  a LAN URL with the Android 17 local-network prompt, chat streaming,
  reactions, drafts per chat, History share/Direct Share/archive undo,
  approval from the notification (with "Stay connected in background"), app
  lock with a PIN, launcher shortcuts (the non-exported alias rejects adb).
- Not device-verified: voice shortcut, Quick Settings tile, JSON
  export/import round trip, memory edit/forget, cron runs, Hermes v0.22.0
  paths, TalkBack walkthrough, large fonts.
- Local builds of several agents in parallel: use an isolated
  GRADLE_USER_HOME and `-Pkotlin.compiler.execution.strategy=in-process`
  (the shared Kotlin daemon runs out of memory); never `./gradlew --stop`.
- Instrumented tests: `-Pandroid.testInstrumentationRunnerArguments.class=`
  takes one class per run here (a comma list ran only the first).

## Refactoring boundary (#31 / #65)

- The first phase-2 slice extracts `ChatSessionContext`: generation, runtime
  identity, event/request applicability and exact send ownership. The stored
  ID and server/profile scope keep their existing owners; lifecycle cleanup
  order, public ViewModel API and gateway protocol are unchanged.
- History paging, send-pipeline orchestration and per-method RPC-result routing
  still live in `ChatViewModel`; this slice does not complete #31 or #65.

## Website

- Live at https://iris.wutz.io since 2026-11-17 (GitHub Pages, workflow build,
  HTTPS enforced, Let's Encrypt certificate approved). `website.yml` deploys on
  pushes to `website/**` on main.
- Pitfall: the first certificate request hung for over an hour; clearing the
  custom domain (`cname=null`) and setting it again issued it within minutes.

## Brand assets

- All vector artwork is generated by `scripts/brand/generate_brand_assets.py`
  (needs fontTools); store PNGs are rendered from `docs/brand/*.svg` with
  headless Chrome. `scripts/brand/process_art.py <codex-out>` derives the
  transparent WebPs for app and website from native Codex outputs.
- Iris art batch (2026-11-17): spec and prompt in `docs/brand/art/`,
  provenance in `docs/brand/art/iris-art-provenance.md`, native outputs in
  `~/reviews-local/iris-art-2026-11-17/codex-out/` (local only; the `.jpg`
  files there are Antigravity alternatives for 02/06/08). The 2026-10-13
  Apollo batch lived in `~/reviews/`, which is on the encrypted Data disk.
- Local toolchain without the Data disk: SDK `~/.cache/codex-toolchains/android-sdk`
  (platform 37.0, build-tools 37, emulator, AVD `iris_api37` in
  `~/.cache/codex-toolchains/avd`), JDK 25 for Gradle (foojay provisions 21).

## How to test against a live Hermes

- Pitfall (2026-11-17): `scripts/server/setup-hermes-for-iris.sh` defaults to
  the service name `hermes-serve`, which is the owner's live unit on this host.
  Test it only with `--service-name <scratch-name>` (an `--uninstall` without it
  removed the live unit once; it was restored from agent-config).

The owner's server uses smart approvals (a guardian model approves), so the
approval card never shows there. For approvals, voice and anything that must
not touch the owner's setup, run an isolated instance (verified 2026-10-14):

```bash
H=<scratch>/hermes-test; mkdir -p $H      # own HERMES_HOME; installs its own venv on first start
cat > $H/config.yaml <<'YAML'
_config_version: 50
model: {provider: ollama, base_url: http://127.0.0.1:11434/v1, default: hermes-qwen3-vl:8b-instruct, context_length: 64000}
approvals: {mode: manual}
YAML
# Non-loopback public URL activates the password gate; 10.0.2.2 is the host as seen by the emulator.
HERMES_HOME=$H HERMES_DASHBOARD_PUBLIC_URL=http://10.0.2.2:19120 \
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=apollo-test HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=<random> \
HERMES_DASHBOARD_BASIC_AUTH_SECRET=<random> hermes serve --host 127.0.0.1 --port 19120 --skip-build
adb -s emulator-5580 reverse tcp:19120 tcp:19120   # app URL: http://127.0.0.1:19120/
```

- Ollama model `hermes-qwen3-vl:8b-instruct` has the 64k context Hermes needs
  (`MINIMUM_CONTEXT_LENGTH`). Approval probe: ask it to `rm -r` a missing
  `/tmp` path; prefix `sleep 8` to get the request while the app is in the background.
- Voice: Edge TTS answered 403 on 2026-10-14. Local providers without touching
  the Hermes install: a scratch venv with `piper-tts` and `faster-whisper`
  (decode via ffmpeg; its bundled PyAV is incompatible), wired as
  `tts.providers.<name>` / `stt.providers.<name>` with `type: command`.
- The emulator's PulseAudio input fails on this PipeWire host ("Failed to
  initialize PA context"), so the spoken loop needs a real phone (#4).
