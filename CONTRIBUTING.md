# Contributing to Iris

Thank you for your interest in contributing to Iris! To maintain high code quality and consistency, we ask all contributors to follow the workflow and guidelines outlined below.

> Iris started as a fork of [Hermes Mobile](https://github.com/Hy4ri/hermes-mobile)
> and is developed independently; it does not merge upstream changes
> (see [ORIGIN.md](ORIGIN.md)).
> Every new user-visible string needs English and German text.

---

## Before You Start: Search First

A quick duplicate check saves your time and keeps the PR queue clean:

```bash
gh search prs --repo itmitalles-software/iris --state open "<your feature>"
gh search prs --repo itmitalles-software/iris --state closed "<your feature>"
gh search issues --repo itmitalles-software/iris --state open "<your feature>"
```

If an open PR already covers it, review or improve that one instead of opening a competing duplicate. For larger work, comment on the issue to signal you're on it.

---

## PR Workflow

Every change must go through a pull request (PR) targeting `main`.

**Keep PRs focused: one feature or one fix per PR.** Unrelated changes belong in separate PRs so each one stays easy to review, test, and revert.

1. **Pick or open an issue** to discuss the changes you want to make.
2. **Create a branch** off `main` using the following naming convention:
   - Features: `feat/short-description` (optionally `feat/issue-N-description`)
   - Bug fixes: `fix/short-description` (optionally `fix/issue-N-description`)
   - CI/chore: `ci/...`, `chore/...`
3. **Implement your changes** and format them locally (see Code Style).
4. **Submit a PR** targeting `main`, filling the PR template.
5. **Run the local gate** (`./gradlew gate`, see [BUILD.md](BUILD.md#local-gate)) and **ensure all CI checks pass**.
6. **Rebase onto `main` before merge** and re-run verification on the rebased result.

### Fixing a blocked PR description

If the template bot reports missing information, edit the PR description or
title, then re-run the failed **PR Template** job of the Android CI run (it reads
the live description). A new push re-runs it as well.

---

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/) to keep the history scannable:

- `feat:` — New feature for the user
- `fix:` — Bug fix
- `refactor:` — Code change with no functional change
- `docs:` — Documentation only
- `test:` — Adding or fixing tests
- `ci:` — CI configuration or scripts
- `chore:` — Maintenance, deps, tooling

Keep commits **atomic**: one subject line (≤72 chars) + max 2 lines of body. If it needs more, split into multiple commits.

```
fix(#5): resume last session from Room cache on cold start

Shows cached messages before WS connects and resumes the last
session on GatewayReady instead of creating a blank one.
```

---

## AI Tool Usage

If you use AI coding tools (including agents) to contribute:

- Keep your own git identity as the commit author. A `Co-Authored-By` trailer naming the AI tool is allowed and shows which changes were made with AI help.
- Direct AI agents to [`AGENTS.md`](AGENTS.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`DESIGN.md`](DESIGN.md). `AGENTS.md` holds the rules for contributors and agents, `docs/ARCHITECTURE.md` the architecture and testing conventions, and `DESIGN.md` the visual, interaction, and accessibility requirements with links to the token sources. Theme implementation is documented in [`THEMES.md`](app/src/main/java/io/wutz/apollo/theme/THEMES.md).

---

## Translations

English (`values/`) and German (`values-de/`) are maintained in this repository and must both be updated
with every new string. The Arabic, Chinese, Italian, Korean and Spanish files date from the Hermes Mobile
fork point and are not maintained; strings added since fall back to English there.

---

## Code Style

We enforce Kotlin coding conventions and Jetpack Compose best practices.

### Kotlin Formatting

- Code formatting is checked and enforced by **ktlint 1.8.0** in CI. Use the Gradle tasks; they resolve ktlint 1.8.0 through Gradle, so no separate install is needed:
  ```bash
  ./gradlew ktlintCheck        # check (CI-equivalent)
  ./gradlew ktlintFormat       # auto-fix, then re-check with ktlintCheck
  ```
  > ⚠️ Hand-patching indentation/import order routinely fails the real CI gate (multiline-expression-wrapping and ASCII import order are the usual casualties). Always run `ktlintFormat`, never fix formatting by hand.
- Import ordering is strictly ASCII-lexicographic (uppercase before lowercase: e.g., `LaunchedEffect` before `collectAsState`).

### Compose Guidelines

- Standard screen structures must use `HermesScaffold` rather than raw Material3 `Scaffold`.
- Follow existing Compose APIs: required parameters first, `modifier` as the first optional parameter, then other optional parameters; keep trailing composable content last.
- Do **not** apply `paddingValues` on inner content inside `HermesScaffold` — the scaffold already handles top bar padding. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#-hermesscaffold-padding-foot-gun) for the full breakdown of this recurring bug.
- Every data screen must implement `LoadingState`, `ErrorState`, and `EmptyState` branches in its `when { }` block.

### Color Usage

- **No hardcoded `Color(0x...)` literals** outside `theme/`, `*Preview.kt`, and auth screens. A Gradle task (`checkColorLiterals`) enforces this in CI.
- Use `MaterialTheme.colorScheme.<token>` or `LocalHermesStatusColors.current.<semantic>` instead.
- `Color.Transparent` and `Color.Unspecified` are allowed.

### Tests

- Unit tests: `./gradlew testDebugUnitTest` (MockK). Instrumented Compose UI tests run in CI on an emulator; `./gradlew gate` compiles them (`compileDebugAndroidTestKotlin`) so signature changes do not break the emulator job.
- **Tests must not touch real on-device storage.** Use `@get:Rule TemporaryFolder` (or Room `inMemoryDatabaseBuilder`) — never write to real app data or external storage. CI/emulator environments are ephemeral; leaking state breaks the next run.
- Mock time/dispatchers explicitly (`StandardTestDispatcher` + `Dispatchers.setMain`), restore `Dispatchers.Main` in teardown, and cancel test-owned scopes. Follow neighboring tests for the affected component and the [testing conventions](docs/ARCHITECTURE.md#testing-conventions).
- For UI changes, exercise the changed flow on a device or emulator and record the behavior observed. A built or downloaded CI APK alone is not behavioral verification. If device verification is unavailable, state that gate explicitly rather than marking it passed.


### Gateway contract snapshot

`GatewayContractTest` checks `WsMethods`, handled server requests and literal param keys against a vendored copy of
hermes-agent's `gateway-contract.openrpc.json` (`app/src/test/resources/gateway-contract/`, provenance in `SOURCE`).
It is test-only. Refresh it with `scripts/sync-gateway-contract.sh [ref]`, then run
`./gradlew testDebugUnitTest --tests '*GatewayContract*'` and review the diff. Allowlist exceptions need a reason in
`GatewayContractAllowlist`.

---

## Dependency & Build Hygiene

- **Pin build tooling.** AGP/Kotlin/SDK versions are pinned in `gradle/libs.versions.toml` and `build.gradle.kts` on purpose. Unpinned versions silently break the build on the next dependabot/CI refresh (the "SDK not writable" AGP trap). Bump deliberately, not via wildcard ranges.
- Never add a dependency without updating the version catalog; CI validates the Gradle wrapper.

---

## PR Checklist

Before submitting your PR, please verify:

- [ ] I searched open/closed PRs + issues for duplicates (see *Before You Start*).
- [ ] PR is focused on one feature or one fix (no unrelated changes).
- [ ] Branch is rebased onto current `main` (`git rebase origin/main`).
- [ ] `./gradlew gate` passes (ran `ktlintFormat` first — no hand edits).
- [ ] No unused imports, unused parameters, or dead code.
- [ ] Meaningful images and icon-only actions have localized accessible labels; decorative images/icons use `contentDescription = null` to avoid redundant announcements.
- [ ] New screens use `HermesScaffold` and implement Loading/Error/Empty states.
- [ ] UI changes follow [`DESIGN.md`](DESIGN.md), including applicable contrast, touch-target, font-scaling, and RTL requirements.
- [ ] Changed UI behavior was exercised on a device/emulator; the PR records the result or explicitly names the unverified gate.
- [ ] New components match the UI/UX style of similar existing screens.
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) and are atomic (subject + ≤2 lines body).
