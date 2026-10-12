# Apollo — verified state

_Last updated: 2026-10-12_

## Repository

- GitHub: `wutz-io/apollo-hermes-android` (public), branch `main`.
- Fork of `Hy4ri/hermes-mobile` `dev` at `689a2d30` (see `UPSTREAM.md`).
  Remote `upstream` points there; upstream tags are not pushed.
- Package / application ID `io.wutz.apollo` (debug `io.wutz.apollo.dev`).

## Verified (2026-10-12)

- Local toolchain: Temurin 21 (`~/.cache/codex-toolchains/temurin21`),
  SDK `~/Android/Sdk` (platform 37, build-tools 37), ktlint 1.8.0 in
  `~/.cache/codex-toolchains/ktlint-1.8.0`.
- `ktlintCheck checkColorLiterals assembleDebug compileReleaseKotlin
  compileDebugAndroidTestKotlin testDebugUnitTest lintDebug` green
  (≈3,430 unit tests; Lint 0 errors). One `ChatViewModelTest` case
  (`sendMessage while session create is in flight…`) failed once under heavy
  load and passed on rerun, so it is likely flaky.
- Emulator `apollo_api37` (API 37, port 5580) against a real Hermes v0.21.6
  `hermes serve` on loopback with `HERMES_DASHBOARD_PUBLIC_URL` + basic auth
  (gated mode), reached via `adb reverse`:
  - basic-auth login, streaming chat, tool call rendering, session list and
    search
  - share sheet from the Files app → staged in a new chat → uploaded →
    agent read the file
  - German UI, light/dark mode, persistent sidebar at 2560×1600/320 dpi
- Not verified: HTTPS through `tailscale serve` (needs root on the host),
  tool-approval UI (the host's Hermes config ran the test command without
  asking), notifications/inline reply, voice, instrumented tests
  (`connectedDebugAndroidTest`), signed release build.

## Known issues / follow-ups

Tracked as GitHub issues in the repository.

## How to test against a live Hermes

```bash
# isolated instance; does not touch ~/.hermes/config.yaml
HERMES_DASHBOARD_PUBLIC_URL=https://<machine>.<tailnet>.ts.net \
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=apollo-test \
HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=<random> \
HERMES_DASHBOARD_BASIC_AUTH_SECRET=<random> \
hermes serve --host 127.0.0.1 --port 19120 --skip-build
adb -s emulator-5580 reverse tcp:19120 tcp:19120   # app URL: http://127.0.0.1:19120
```
