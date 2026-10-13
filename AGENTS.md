# AGENTS.md

Rules for coding agents (and humans) working on Apollo. Keep changes small
and tested.

## Read first

- `.agent/STATE.md`: current verified state and next steps
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): navigation, ViewModel scope,
  drawer gestures, WebSocket/RPC conventions (inherited from Hermes Mobile, binding)
- [DESIGN.md](DESIGN.md) for UI work
- [ORIGIN.md](ORIGIN.md): Apollo started as a fork of Hermes Mobile and is
  independent since 2026-10-13 (no upstream merges)
- Open work lives in GitHub Issues, not in TODO files.

## Principles

- Apollo is a client only. Use documented Hermes dashboard/`hermes serve`
  endpoints that exist in `hermes-agent`, and verify new calls against its
  source or the vendored contract (`app/src/test/resources/gateway-contract`).
  Never invent APIs or ship UI placeholders for missing server features.
- Prefer the existing components and patterns. No new frameworks, DI
  containers, modules or abstractions without a concrete need.
- Porting a fix from Hermes Mobile is fine: port it by hand and credit it in
  the commit message (`Ported from Hy4ri/hermes-mobile@<sha>`).
- Security: no telemetry, trackers or third-party SDKs. Credentials only in
  encrypted storage, never in logs. Do not weaken TLS verification. Ask for
  confirmation before destructive actions.

## Conventions

- Package `io.wutz.apollo`; never reintroduce `com.m57.hermescontrol`.
- Every user-visible string goes into `values/strings.xml` **and**
  `values-de/strings.xml`. The other locales are unmaintained and fall back to English.
- Colors come from the theme (`MaterialTheme.colorScheme`,
  `LocalHermesStatusColors`); `checkColorLiterals` enforces this.
- ktlint 1.8.0 style: sorted imports, trailing commas, 120 columns.
- English for code, comments, commits and docs.

## Before committing

```bash
./gradlew ktlintCheck checkColorLiterals testDebugUnitTest lintDebug assembleDebug
```

If you touch shell scripts, run `shellcheck scripts/**/*.sh`; the server
setup script (`scripts/server/setup-hermes-for-apollo.sh`) must also be tried
with an isolated `--service-name/--port/--config-dir` and `--uninstall`.

For UI changes, also run the app (emulator or device) and check light and
dark mode in English and German. State clearly what was not tested,
especially anything that needs a live Hermes server.
