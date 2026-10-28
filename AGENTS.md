# APK P0 Release Rules

This repository is Android APK first. Root `index.html` is frozen.

## Mandatory Regression Gate

Every change, including visual-only changes, must run `npm run test:p0` before an APK is published.
Missing dependencies, skipped tests, timeouts, failures, or an unverified Android-device flow block publication.
Do not replace the gate with compile success, isolated mocks, or a previous build's test report.
The P0 bridge replay must deny `importScripts(file:...)` inside Workers: Android WebView
has failed this path on a real device. Desktop Chrome file-access flags are not evidence
that APK Worker resources load. Preserve the 1646-cache/1920-native recovery regression.

P0 acceptance includes:

- Manual marking: submit through the APK UI and native bridge; reopen the page and database and retain the exact coordinates.
- Foreground tracking: location input reaches the actual Service, Room, bridge, render geometry, and visible exploration.
- Background tracking: continue recording without a page, then reopen and display all accepted samples.
- Stop and close: old callbacks must not write; explicit reopening restores intended recording.
- Cold start and upgrade: existing archive and draft survive; cache must never overwrite native truth.
- Export/import: round-trip coordinates and IDs without loss; failed operations must not report success.
- Map alignment, stationary filtering, gap splitting, and bounded storage remain regression requirements.

The JVM/browser bridge test is a useful integration test, NOT Android WebView or real GMS/Doze evidence.
Before publication, also validate the APK on an isolated Android device/emulator using simulated movement,
actual WebView, foreground/background switching and process recreation. Record the APK hash, Android/WebView
versions, steps, and result. Never inject test tracks into the user's archive.

If device validation is unavailable or a reported P0 remains unexplained, state that plainly and do not publish
an APK as a verified fix. Request existing diagnostics only when needed; do not make the user walk outside
to compensate for missing automated regression coverage.

Keep old published APKs unchanged unless the user explicitly authorizes replacement.
