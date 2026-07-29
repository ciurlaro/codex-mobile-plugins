#!/usr/bin/env bash
set -euo pipefail

provider_root="$(cd "$(dirname "$0")/.." && pwd)"
host_root="$(cd "${1:-$provider_root/../codex-mobile}" && pwd)"
variant="${2:-debug}"
api_root="$host_root/modules/multiplatform/extension-provider-api"
[[ -d "$api_root" ]] || { echo "extension-provider-api checkout not found in $host_root" >&2; exit 1; }

case "$variant" in
  debug)
    provider_tasks=(
      :documents-android:assembleDebug
      :telegram-android:assembleDebug
      :documents-android:assembleDebugAndroidTest
      :telegram-android:assembleDebugAndroidTest
      :documents-android:lintAnalyzeDebug
      :telegram-android:lintAnalyzeDebug
    )
    host_tasks=(:app:assembleDebug :app:assembleDebugAndroidTest :app:lintAnalyzeDebug)
    ;;
  release)
    provider_tasks=(:documents-android:assembleRelease :telegram-android:assembleRelease)
    host_tasks=(:app:assembleRelease)
    ;;
  *) echo "Usage: $0 [codex-mobile-checkout] [debug|release]" >&2; exit 2 ;;
esac

"$provider_root/gradlew" -p "$provider_root" \
  -PcodexMobile.extensionProviderApiBuild="$api_root" \
  "${provider_tasks[@]}"
exec "$host_root/gradlew" -p "$host_root" \
  -PcodexMobile.providerBuild="$provider_root" \
  "${host_tasks[@]}"
