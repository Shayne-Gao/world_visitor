# Fog Visitor APK Build & Release Runbook

This document records the project-specific APK build and release steps for `world_visitor`.

## Scope

- APK delivery path:
  - `apk/app/src/main/assets/web/index.html`
  - `apk/app/src/main/java/io/shayne/fogvisitor/`
  - `apk/releases/latest/fog-visitor-debug-latest.apk`
- Root Web path:
  - `index.html`
  - Do not edit it for APK-only requests.
- Release branch:
  - `main`

## Required Version Updates

For every user-visible APK change, update all of these together:

- `apk/app/build.gradle.kts`
  - Increase `versionCode`.
  - Increase `versionName`.
- `apk/app/src/main/assets/web/index.html`
  - Update visible `versionTag`.
  - Update exported diagnostic `appVersion`.
- `README.md`
  - Add a new changelog entry at the top.

Do not package a new APK while leaving old version strings in place.

## Build Environment

The current local build setup uses:

- JDK:
  - `JAVA_HOME=/tmp/fogvisitor-jdk`
- Android SDK:
  - `apk/local.properties`
  - `sdk.dir=/tmp/fogvisitor-sdk-root`
- Gradle wrapper:
  - `apk/gradlew`

The SDK directory is temporary and may be deleted by the environment. If that happens, Gradle may need to reinstall SDK components.

## Android SDK Licenses

If Gradle fails with missing or unaccepted Android SDK licenses, recreate the license files in the temporary SDK:

```bash
mkdir -p /tmp/fogvisitor-sdk-root/licenses
printf '8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > /tmp/fogvisitor-sdk-root/licenses/android-sdk-license
printf '84831b9409646a918e30573bab4c9c91346d8abd\n' > /tmp/fogvisitor-sdk-root/licenses/android-sdk-preview-license
```

Then rerun the Gradle build. Gradle can install missing components such as:

- `build-tools;34.0.0`
- `platforms;android-35`
- `platform-tools`

If the first install appears stuck at `Preparing "Install Android SDK Build-Tools 34..."`, wait long enough before stopping. On 2026-08-11 it completed successfully after a retry and about one minute.

## Build Command

Run from the APK directory:

```bash
cd "/Users/bytedance/Documents/Personal Document/world_visit/world_visitor/apk"
export JAVA_HOME=/tmp/fogvisitor-jdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

Expected output includes:

```text
BUILD SUCCESSFUL
```

The built debug APK is:

```text
apk/app/build/outputs/apk/debug/app-debug.apk
```

## Latest APK Artifact

After a successful build, overwrite the fixed latest APK artifact:

```bash
cd "/Users/bytedance/Documents/Personal Document/world_visit/world_visitor/apk"
cp -f app/build/outputs/apk/debug/app-debug.apk ../apk/releases/latest/fog-visitor-debug-latest.apk
/usr/bin/stat -f '%Sm %N' app/build/outputs/apk/debug/app-debug.apk ../apk/releases/latest/fog-visitor-debug-latest.apk
```

Published links:

- GitHub file:
  - `https://github.com/Shayne-Gao/world_visitor/blob/main/apk/releases/latest/fog-visitor-debug-latest.apk`
- Raw download:
  - `https://raw.githubusercontent.com/Shayne-Gao/world_visitor/main/apk/releases/latest/fog-visitor-debug-latest.apk`

## Diagnostics Before Build

After editing APK files, run diagnostics on touched files:

- `apk/app/src/main/assets/web/index.html`
- Kotlin files under `apk/app/src/main/java/io/shayne/fogvisitor/`
- `apk/app/build.gradle.kts`
- `apk/app/src/main/AndroidManifest.xml`, if touched

Use the editor diagnostics tool rather than relying only on Gradle.

## Commit & Push

Only stage files relevant to the release. Ignore local debug artifacts such as:

- `.dbg/`
- `debug.log`
- `debug-*.md`

Typical release commit:

```bash
cd "/Users/bytedance/Documents/Personal Document/world_visit/world_visitor"
git status --short
git add README.md \
  apk/app/build.gradle.kts \
  apk/app/src/main/assets/web/index.html \
  apk/releases/latest/fog-visitor-debug-latest.apk
git commit -m "release(apk): bump to vX.Y.Z and describe change"
git push origin main
git status --short
git rev-parse --short HEAD
```

If Kotlin or Manifest files were changed, include them explicitly:

```bash
git add apk/app/src/main/java/io/shayne/fogvisitor/MainActivity.kt
git add apk/app/src/main/java/io/shayne/fogvisitor/JsBridge.kt
git add apk/app/src/main/AndroidManifest.xml
```

## Common Failures

### License Not Accepted

Symptom:

```text
Failed to install the following Android SDK packages as some licences have not been accepted.
```

Fix:

- Recreate `/tmp/fogvisitor-sdk-root/licenses/android-sdk-license`.
- Recreate `/tmp/fogvisitor-sdk-root/licenses/android-sdk-preview-license`.
- Rerun `./gradlew assembleDebug`.

### Missing `android-35`

Symptom:

```text
Failed to find target with hash string 'android-35' in: /tmp/fogvisitor-sdk-root
```

Fix:

- Do not use `--offline`.
- Ensure license files exist.
- Rerun normal `./gradlew assembleDebug` and let Gradle install `platforms;android-35`.

### Temporary SDK Partial Install

Symptom:

- Directories such as `android-35-2` or `34.0.0-2` exist.
- Expected files such as `android.jar` or `aapt2` are missing.

Fix:

- Treat the install as incomplete.
- Rerun Gradle normally after ensuring license files exist.
- Do not manually move incomplete `*-2` directories unless required files exist.

### Build Warning About `compileSdk = 35`

Warning:

```text
This Android Gradle plugin (8.5.2) was tested up to compileSdk = 34.
```

Current handling:

- This is a warning, not a build blocker.
- Do not change AGP or compile SDK just for this warning unless explicitly planned.

## Release Handoff Checklist

After shipping, report:

- Current APK version and `versionCode`.
- Summary of behavior changes.
- Focused test steps for the user.
- GitHub file link and raw APK download link.

## Do Not Do

- Do not use `git reset --hard` or destructive cleanup.
- Do not revert unrelated user changes.
- Do not commit local debug artifacts.
- Do not change root `index.html` for APK-only requests.
- Do not stop after code edits when the user asked for a packaged APK.
