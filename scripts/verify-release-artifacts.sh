#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <host.apk> <documents.apk> <telegram.apk>" >&2
  exit 2
fi

host_apk=$1
documents_apk=$2
telegram_apk=$3
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools=${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}/build-tools/36.0.0

for apk in "$host_apk" "$documents_apk" "$telegram_apk"; do
  test -f "$apk"
  "$tools/apksigner" verify "$apk"
done

certificate() {
  "$tools/apksigner" verify --print-certs "$1" |
    awk -F': ' '/certificate SHA-256 digest/ { print $2; exit }'
}

host_certificate=$(certificate "$host_apk")
test -n "$host_certificate"
test "$(certificate "$documents_apk")" = "$host_certificate"
test "$(certificate "$telegram_apk")" = "$host_certificate"

verify_feature() {
  local plugin=$1 split=$2 apk=$3 manifest entries expected_sha actual_sha
  manifest=$("$tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)
  grep -q 'package="io.github.ciurlaro.codexmobile"' <<<"$manifest"
  grep -q 'versionCode.*=3' <<<"$manifest"
  grep -q "split=\"$split\"" <<<"$manifest"
  entries=$(unzip -Z1 "$apk")
  ! grep -Eqi 'mutool|tesseract|officecli|tg_?cli|node_modules|(^|/)node($|/)|(^|/)npm($|/)|private-backend' <<<"$entries"
  expected_sha=$(jq -r '.android.package.sha256' "$root/.agents/plugins/plugins/$plugin/codex-mobile-addon.json")
  actual_sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
  test "$actual_sha" = "$expected_sha"
}

verify_feature documents provider_documents "$documents_apk"
verify_feature telegram provider_telegram "$telegram_apk"

documents_native=$(unzip -Z1 "$documents_apk" | grep '^lib/.*\.so$' | sort)
test "$documents_native" = $'lib/arm64-v8a/libmlkit_google_ocr_pipeline.so\nlib/arm64-v8a/libpdfium.so\nlib/arm64-v8a/libpdfiumandroid.so'
telegram_native=$(unzip -Z1 "$telegram_apk" | grep '^lib/.*\.so$' | sort)
test "$telegram_native" = 'lib/arm64-v8a/libtdjsonjava.so'

echo "provider release artifacts verified"
