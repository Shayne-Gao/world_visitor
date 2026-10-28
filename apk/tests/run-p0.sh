#!/usr/bin/env bash
set -euo pipefail
ROOT="$(node -e 'process.stdout.write(require("path").resolve(process.argv[1], "../.."))' "$(dirname "$0")")"
export PLAYWRIGHT_PATH="${PLAYWRIGHT_PATH:-$ROOT/node_modules/playwright-core}"
node -e 'require(process.argv[1])' "$PLAYWRIGHT_PATH"

run_bounded() {
  perl -e 'alarm shift; exec @ARGV' "$@"
}

run_bounded 600 "$ROOT/apk/gradlew" -p "$ROOT/apk" testDebugUnitTest lintDebug assembleDebug --rerun-tasks --console=plain --no-daemon
run_bounded 300 node --test "$ROOT/apk/tests/geometry-worker.test.cjs" "$ROOT/apk/tests/native-sync.test.cjs" "$ROOT/apk/tests/canvas-cache.test.cjs" "$ROOT/apk/tests/storage-sql.test.cjs"
run_bounded 120 node "$ROOT/apk/tests/map-browser.test.cjs"
run_bounded 120 node "$ROOT/apk/tests/zoom-browser.test.cjs"
printf '%s\n' 'Local P0 regressions passed. Publication remains blocked until actual Android/WebView/device scenarios are verified for this APK.'
