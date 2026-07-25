#!/usr/bin/env bash
set -euo pipefail

provider_root="$(cd "$(dirname "$0")/.." && pwd)"
host_root="$(cd "${1:-$provider_root/../codex-mobile}" && pwd)"
variant="${2:-debug}"

case "$variant" in
  debug)
    tasks=(
      :app:android:assembleDebug
      :provider_documents:assembleDebug
      :provider_telegram:assembleDebug
      :provider_documents:assembleDebugAndroidTest
      :provider_telegram:assembleDebugAndroidTest
    )
    ;;
  release)
    tasks=(
      :provider_documents:assembleRelease
      :provider_telegram:assembleRelease
      :app:android:assembleRelease
    )
    ;;
  *) echo "Usage: $0 [codex-mobile-checkout] [debug|release]" >&2; exit 2 ;;
esac

exec "$host_root/gradlew" -p "$host_root" \
  -PcodexMobile.providerBuild="$provider_root" \
  "${tasks[@]}"
