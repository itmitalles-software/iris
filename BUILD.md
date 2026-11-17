# Building Iris

## Requirements

- JDK 21 (Temurin recommended)
- Android SDK with platform `android-37` and build-tools 37 (Gradle installs
  missing SDK packages automatically when the licenses are accepted)
- Nothing else: `./gradlew ktlintCheck` resolves ktlint 1.8.0
  (`com.pinterest.ktlint:ktlint-cli`) through Gradle, so no `ktlint` binary
  is needed on `PATH`

Create `local.properties` (ignored by Git) or export `ANDROID_HOME`:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Nix (optional)

`flake.nix` provides all of the above (JDK 21, the Android SDK with platform
and build-tools 37 plus an x86_64 emulator image) and sets `JAVA_HOME` and `ANDROID_HOME`:

```bash
nix develop        # or, with direnv: direnv allow (the repository's .envrc runs `use flake`)
```

## Local gate

Run this before every commit or pull request:

```bash
./gradlew gate
```

It runs `ktlintCheck`, `checkColorLiterals`, `checkGermanStrings`,
`testDebugUnitTest`, `lintDebug`, `assembleDebug`,
`compileDebugAndroidTestKotlin`, `compileReleaseKotlin` and
`checkPlayPermissions`: the checks of the CI jobs except the emulator tests.
For UI changes, also run the changed flow on a device or emulator.

## Common tasks

```bash
./gradlew assembleDebug        # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug            # Android Lint
./gradlew ktlintCheck          # code style (ktlint 1.8.0, resolved by Gradle)
./gradlew ktlintFormat         # fix code style, then re-run ktlintCheck
./gradlew checkColorLiterals   # guard against hard-coded colors outside the theme
./gradlew connectedDebugAndroidTest   # Compose UI tests on a device/emulator
```

The debug build installs side by side with a release build as
`io.wutz.apollo.dev` ("Iris Dev"). It is signed with the committed
`app/debug.keystore` (public password `android`), so CI debug APKs can be
installed over each other.

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Versioning

Iris uses Semantic Versioning with Git tags `vMAJOR.MINOR.PATCH`
(pre-releases: `v0.2.0-rc.1`). The release workflow derives

- `versionName` = tag without the leading `v`
- `versionCode` = `MAJOR * 10000 + MINOR * 100 + PATCH` (so `MINOR` and `PATCH`
  stay below 100; the release workflow enforces it)

Local builds without `-PversionName/-PversionCode` report `1.0-dev` (debug builds
add their `-dev` suffix, so About shows `1.0-dev-dev`).

## Releases

Iris has two distributions, both with the application ID `io.wutz.apollo`:

| Build | Task | Output | Self-updater |
| --- | --- | --- | --- |
| GitHub (default) | `assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | yes |
| Google Play | `bundlePlay` | `app/build/outputs/bundle/play/app-play.aab` | no |

The Play build is a separate build type (`play`, a copy of `release`), not a
product flavor, so every existing task name and output path stays the same.
It sets `BuildConfig.SELF_UPDATE_ENABLED = false` and its manifest
(`app/src/play/AndroidManifest.xml`) removes `REQUEST_INSTALL_PACKAGES`.

### Cutting a release

1. Add a `## <version>` section (for example `## 0.2.0 — 2026-10-20`) to
   `CHANGELOG.md` and merge it. That section becomes the GitHub release text,
   which the in-app update dialog shows. Check it locally with
   `scripts/release-notes.sh 0.2.0`. A pre-release (`0.2.0-rc.1`) uses its own
   section if there is one, otherwise the `0.2.0` section.
2. Wait until **CI Summary** is green on that `main` commit, then tag it:
   `git tag -a v0.2.0 -m "Iris 0.2.0" && git push origin v0.2.0`.

The tag runs `.github/workflows/release.yml`:

- `verify` rejects tags that are not `vMAJOR.MINOR.PATCH[-pre]`, are not on
  `main`, or whose commit has no green CI Summary (it waits up to 45 minutes
  for a running CI), and fails when the CHANGELOG section is missing.
- `build` is the only job that sees the signing secrets (GitHub environment
  `release`). It builds the minified APK (one retry after 60 s), deletes the
  keystore, and fails unless the signing certificate matches
  `.github/release-cert.sha256` and `aapt2` shows the expected
  `versionName`/`versionCode`.
- `publish` has no secrets. It attaches `iris-<tag>.apk` and `SHA256SUMS`
  to the GitHub release and records a build provenance attestation.

The in-app update checker then offers the APK to installed release builds.
To re-run a release by hand, dispatch the workflow on the tag itself:
`gh workflow run release.yml --ref v0.2.0 -f tag=v0.2.0`.

### Release signing (one-time setup)

The release key never goes into the repository. Create it locally and keep a
backup somewhere safe; losing it means users must uninstall to update.

```bash
keytool -genkeypair -v -keystore iris-release.jks -alias apollo \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Apollo,O=wutz.io,C=DE"

gh secret set KEYSTORE_BASE64   -R itmitalles-software/iris --env release < <(base64 -w0 iris-release.jks)
gh secret set KEYSTORE_PASSWORD -R itmitalles-software/iris --env release
gh secret set KEY_ALIAS         -R itmitalles-software/iris --env release --body apollo
gh secret set KEY_PASSWORD      -R itmitalles-software/iris --env release
```

The secrets belong to the `release` environment, which only `v*` tags may
deploy to, so no branch workflow can read them. A new key also needs its
certificate digest in `.github/release-cert.sha256`
(`apksigner verify --print-certs` prints it).

Without these secrets the release workflow stops with a clear error; use the
debug APK from the CI artifacts (or a local `assembleDebug`) in the meantime.

To sign a release locally:

```bash
KEYSTORE_PATH=$PWD/iris-release.jks KEYSTORE_PASSWORD=… KEY_ALIAS=apollo KEY_PASSWORD=… \
  ./gradlew assembleRelease -PversionName=0.1.0 -PversionCode=100
```

### Google Play

The app is prepared for a Play release: unique application ID, R8
minification, no proprietary SDKs or trackers, and Fastlane metadata in
`fastlane/metadata/android/` (English and German). There is no Play publishing
automation; uploads are manual in the Play Console.

Build the App Bundle with the Play build type (issue #7):

```bash
KEYSTORE_PATH=$PWD/iris-release.jks KEYSTORE_PASSWORD=… KEY_ALIAS=apollo KEY_PASSWORD=… \
  ./gradlew bundlePlay -PversionName=0.2.0 -PversionCode=200
```

Without the signing variables `bundlePlay` produces an unsigned bundle, which
is enough for local checks but cannot be uploaded. The Play build has no
GitHub self-updater: no update checks or downloads, no update rows in About,
no update banner or dialog, and no `REQUEST_INSTALL_PACKAGES` permission.
`./gradlew checkPlayPermissions` (also run by CI and before every
`bundlePlay`/`assemblePlay`) fails if that permission comes back into the
merged manifest. Use the same `versionCode` scheme as GitHub releases; Play
rejects a `versionCode` it has already seen.

**Play App Signing.** Play signs the APKs it delivers with the *app signing
key*; you sign uploads with an *upload key*. Never commit either key or put
the app signing key into CI.

- Option A (default): let Google generate the app signing key and use the
  existing Iris release key as the upload key. Play installs and GitHub
  APKs then have different signatures, so users must uninstall to switch
  between them (losing local data).
- Option B: upload the existing Iris release key as the app signing key
  (the Play Console's PEPK tool encrypts it) and create a new upload key. Play
  installs and GitHub APKs then share a signature and update each other.
  Keep the offline backup of the release key either way.

If the upload key is lost or leaked, the account owner can request an upload
key reset in the Play Console.
