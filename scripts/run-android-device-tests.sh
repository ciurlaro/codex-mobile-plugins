#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-device-tests}
documents_test=$(find "$artifacts" -type f -name documents-android-debug-androidTest.apk -print -quit)
telegram_test=$(find "$artifacts" -type f -name telegram-android-debug-androidTest.apk -print -quit)
test -f "$documents_test"
test -f "$telegram_test"

run_instrumentation() {
  local output
  output=$(adb shell am instrument -w -r "$@")
  printf '%s\n' "$output"
  grep -q '^OK (' <<< "$output"
}

adb install -r "$documents_test"
run_instrumentation \
  -e class 'io.github.ciurlaro.codexmobile.platform.android.DocumentsDeviceTest' \
  io.github.ciurlaro.codexmobile.providers.documents.test/androidx.test.runner.AndroidJUnitRunner
adb install -r "$telegram_test"
run_instrumentation \
  -e class 'io.github.ciurlaro.codexmobile.platform.android.TelegramCredentialsDeviceTest,io.github.ciurlaro.codexmobile.platform.android.TelegramSessionStorageDeviceTest' \
  io.github.ciurlaro.codexmobile.providers.telegram.test/androidx.test.runner.AndroidJUnitRunner
