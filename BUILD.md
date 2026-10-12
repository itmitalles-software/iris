# Building Apollo

## Requirements

- JDK 21 (Temurin recommended)
- Android SDK with platform `android-37` and build-tools 37 (Gradle installs
  missing SDK packages automatically when the licenses are accepted)
- Optional: [ktlint 1.8.0](https://github.com/pinterest/ktlint/releases/tag/1.8.0)
  on `PATH` for `./gradlew ktlintCheck`

Create `local.properties` (ignored by Git) or export `ANDROID_HOME`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Common tasks

```bash
./gradlew assembleDebug        # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug            # Android Lint
./gradlew ktlintCheck          # code style (needs ktlint on PATH)
./gradlew checkColorLiterals   # guard against hard-coded colors outside the theme
./gradlew connectedDebugAndroidTest   # Compose UI tests on a device/emulator
```

The debug build installs side by side with a release build as
`io.wutz.apollo.dev` ("Apollo Dev"). It is signed with the committed
`app/debug.keystore` (public password `android`), so CI debug APKs can be
installed over each other.

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Versioning

Apollo uses Semantic Versioning with Git tags `vMAJOR.MINOR.PATCH`
(pre-releases: `v0.2.0-rc.1`). The release workflow derives

- `versionName` = tag without the leading `v`
- `versionCode` = `MAJOR * 10000 + MINOR * 100 + PATCH`

Local builds without `-PversionName/-PversionCode` report `1.0-dev`.

## Releases

Pushing a tag `v*` runs `.github/workflows/release.yml`. It builds a minified,
signed release APK, attaches `apollo-<tag>.apk` to a GitHub release, and the
in-app update checker offers it to installed release builds.

### Release signing (one-time setup)

The release key never goes into the repository. Create it locally and keep a
backup somewhere safe; losing it means users must uninstall to update.

```bash
keytool -genkeypair -v -keystore apollo-release.jks -alias apollo \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Apollo,O=wutz.io,C=DE"

gh secret set KEYSTORE_BASE64   -R wutz-io/apollo-hermes-android < <(base64 -w0 apollo-release.jks)
gh secret set KEYSTORE_PASSWORD -R wutz-io/apollo-hermes-android
gh secret set KEY_ALIAS         -R wutz-io/apollo-hermes-android --body apollo
gh secret set KEY_PASSWORD      -R wutz-io/apollo-hermes-android
```

Without these secrets the release workflow stops with a clear error; use the
debug APK from the CI artifacts (or a local `assembleDebug`) in the meantime.

To sign a release locally:

```bash
KEYSTORE_PATH=$PWD/apollo-release.jks KEYSTORE_PASSWORD=… KEY_ALIAS=apollo KEY_PASSWORD=… \
  ./gradlew assembleRelease -PversionName=0.1.0 -PversionCode=100
```

### Google Play

The app is prepared for a later Play release: unique application ID, R8
minification, no proprietary SDKs or trackers, and Fastlane metadata in
`fastlane/metadata/android/` (English and German). For Play, build an App
Bundle with `./gradlew bundleRelease` and enroll in Play App Signing; the
release key above then serves as the upload key. Review the
`REQUEST_INSTALL_PACKAGES` permission (used only by the GitHub self-updater)
before a Play submission — Play builds should drop the self-updater.
