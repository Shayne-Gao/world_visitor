# 0.1.112-alpha: Unverified Device Test Build

- Version code: 43.
- Distribution: user explicitly requested uploading this test build after being informed of the missing Android-device validation.
- This is a one-time test distribution, not a verified P0 release or a change to `AGENTS.md`.
- Older APKs and `latest` are unchanged.

## Changes

- Commit manual coordinates to native storage before fallible geometry work.
- Build a self-contained Blob Worker from two fixed APK assets, avoiding the `importScripts(file:...)` failure observed on Android WebView.
- Include native archive and tracking events in diagnostic exports.
- Add full local P0 replay, including stale 1646-track cache recovery from 1920 native tracks.

## Validation Boundary

The local replay uses real Kotlin Service/JsBridge/Room, controlled GMS inputs, and the APK page in desktop Chrome through an HTTP bridge adapter. Worker file imports are explicitly denied.

Actual Android WebView, GMS delivery, background/lock-screen behavior, and process recreation have NOT been validated for this APK. The isolated emulator could not boot because of `HV_DENIED`. No Android/WebView version or device acceptance result is available for this build.

## Local Run: 2026-10-28

- Ran `npm ci` and `npm run test:p0` for this build, with Gradle `--rerun-tasks`.
- JVM: 36 tests, zero failures/errors/skips. Includes both API 28 and API 35 bridge replays.
- Node: 21 tests passed; map and zoom browser checks completed.
- Gradle tests, lint and APK assembly completed successfully.
- The command wrapper nevertheless exited with code 1 and `TRAE Sandbox Error: hit restricted`. This is NOT a clean overall gate pass.
- APK signature verification passed and matches 111. Packaged HTML, Turf and Worker assets match the current source byte-for-byte.
- APK SHA-256: `d6e7ebd3822b7e664da1f2bbc30c11ba353320ab69436f0c0c704defa1ffcc74`.
- Signing certificate SHA-256: `01cfd984a16c414f441dc1729d6a010c2e12a1f5f4c953f2e0c8c5cbdf691aa8`.

## Installation

Export a backup first, then install over the existing app. Do not uninstall or clear app data. Old APK availability does not imply database downgrade compatibility.

The first check can be done without travelling: make a manual mark, close and reopen the app, and check that the mark remains visible. Real-device automatic tracking acceptance is still outstanding; this build does not claim it is complete.
