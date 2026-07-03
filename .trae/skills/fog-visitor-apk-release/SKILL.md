---
name: "fog-visitor-apk-release"
description: "Maintains Fog Visitor Android APK release discipline. Invoke when editing, releasing, packaging, or reporting updates for this repo."
---

# Fog Visitor APK Release

This skill defines the non-negotiable working rules for the `world_visitor` repository.

Use this skill whenever you are:
- changing code for the Android app
- updating the shipped APK
- fixing a bug for user testing
- preparing a release or reporting a delivered update

## Product Boundary

Treat this repository as **Android APK first**.

Rules:
- The root `index.html` is the old Web line and is considered frozen unless the user explicitly reopens it.
- The APK may still ship `apk/app/src/main/assets/web/index.html` as an embedded WebView asset. That file belongs to the Android app delivery path and may be changed when the user is asking for APK fixes.
- Do not confuse the frozen root HTML with the APK embedded asset.

## Mandatory Version Discipline

After every user-visible APK change, always bump the APK version markers together.

Minimum required updates:
- `apk/app/build.gradle.kts`
  - increase `versionCode`
  - increase `versionName`
- `apk/app/src/main/assets/web/index.html`
  - update visible `versionTag`
  - update exported `appVersion`
- `README.md`
  - add a new changelog entry at the top

Do not ship a new APK while leaving the old version string in place.

## Mandatory Release Flow

For every delivered APK change, follow this order:
1. Make the code change.
2. Run diagnostics on edited files.
3. Bump version markers.
4. Build debug APK with Gradle.
5. Overwrite the fixed release artifact:
   - `apk/releases/latest/fog-visitor-debug-latest.apk`
6. Commit the code and the APK artifact together.
7. Push to `main`.

Do not stop after editing code if the user asked for a packaged APK or link update.

## Java / Packaging Rule

If the machine does not have a directly usable Java runtime:
- first check whether a usable system JDK or Android Studio JBR already exists
- if not, provision a temporary JDK only to finish packaging
- after packaging, remove the temporary JDK from the repo workspace unless the user asks to keep it

Do not use the lack of a preconfigured JDK as the final stopping point when the user asked for APK packaging.

## User Handoff Format

After every shipped update, always report these four things:
- current version
- what changed
- what the user should test
- the GitHub file link and raw download link

Use concise Chinese if the user is speaking Chinese.

## Testing Guidance

When asking the user to test, give focused checks tied to the exact change.

Example:
- what to tap
- what should stay visible
- what should no longer regress

## Repository-Specific Notes

- Main Android entry: `apk/app/src/main/java/io/shayne/fogvisitor/`
- Embedded APK WebView asset: `apk/app/src/main/assets/web/index.html`
- Frozen old Web root: `index.html`
- Fixed shipped APK path: `apk/releases/latest/fog-visitor-debug-latest.apk`

## Do Not Forget

- If the user reminds you of an old rule, treat it as binding project policy.
- If you changed behavior but did not bump the version, the task is not complete.
- If the user asked for packaging and upload, the task is not complete until the fixed APK path is updated and pushed.
