#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

test -f LICENSE
test -f LICENSES/MLKIT-EXCEPTION.txt
test -f THIRD_PARTY_NOTICES.md
grep -q 'GNU GENERAL PUBLIC LICENSE' LICENSE
grep -q 'GNU GPL version 3 section 7' LICENSES/MLKIT-EXCEPTION.txt

test -f .agents/plugins/marketplace.json
for plugin in documents telegram; do
  test -f ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json"
  test -f ".agents/plugins/plugins/$plugin/.mcp.json"
  test -f ".agents/plugins/plugins/$plugin/codex-mobile-addon.json"
  test -f ".agents/plugins/plugins/$plugin/skills/$plugin/SKILL.md"
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.mcp.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/codex-mobile-addon.json" >/dev/null
  grep -q '"license": "GPL-3.0-or-later"' ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json"
  grep -q '"versionCode": 4' ".agents/plugins/plugins/$plugin/codex-mobile-addon.json"
done

test -f shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/DocumentsTools.kt
test -f shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramTools.kt
test -f android/documents/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/DocumentsProvider.kt
test -f android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/TelegramProvider.kt
for provider in android/{documents,telegram}/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/*Provider.kt; do
  grep -q 'minHostVersionCode = 4' "$provider"
  grep -q 'maxHostVersionCode = 4' "$provider"
done
test -f mcp-server/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/Main.kt
test -f Dockerfile
test -x scripts/verify-mcp.sh
test -x scripts/verify-release-artifacts.sh
test -x scripts/write-release-metadata.py
test -f .github/workflows/verify.yml
test -f .github/workflows/release.yml

if rg -n 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec|java\.lang\.Process|/bin/(sh|bash)' android --glob '!**/build/**'; then
  echo "Android providers must not launch helper processes" >&2
  exit 1
fi
if rg -n 'mutool|officecli|tg_?cli|node_modules|preparePrivateBackends|PrivateBackendBundle' . \
    --glob '!**/build/**' --glob '!scripts/verify-structure.sh' --glob '!scripts/verify-release-artifacts.sh'; then
  echo "A removed command backend remains" >&2
  exit 1
fi

grep -q 'io.modelcontextprotocol:kotlin-sdk-server:0.14.0' mcp-server/build.gradle.kts
grep -q 'kotlin-logging.logStartupMessage' mcp-server/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/Main.kt
grep -q 'COPY THIRD_PARTY_NOTICES.md /opt/provider/THIRD_PARTY_NOTICES.md' Dockerfile
grep -q 'network=none' .agents/plugins/plugins/documents/.mcp.json
grep -q -- '-e TELEGRAM_API_ID -e TELEGRAM_API_HASH' .agents/plugins/plugins/telegram/.mcp.json
grep -q 'ProviderSecretDefinition' android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/TelegramProvider.kt
if rg -n 'CODEX_MOBILE_TELEGRAM_API_|buildConfigField\([^\n]*TELEGRAM_API_|TELEGRAM_API_(ID|HASH)=' \
    Dockerfile android .agents --glob '!**/build/**'; then
  echo "Telegram application credentials must be supplied per installation, not embedded in artifacts" >&2
  exit 1
fi
grep -q 'TDLIB_COMMIT' android/telegram/build.gradle.kts
grep -q 'org.opencontainers.image.licenses="GPL-3.0-or-later"' Dockerfile
grep -q -- '--target tdjson_static' Dockerfile
grep -q -- '-DTD_ENABLE_JNI=ON' Dockerfile
grep -q -- '-DTD_INSTALL_SHARED_LIBRARIES=OFF' Dockerfile
if rg -n '^FROM [^@ ]+( AS .+)?$|--target install' Dockerfile; then
  echo "Container bases and native build targets must be immutable and library-only" >&2
  exit 1
fi
grep -q 'schemaDigest' .agents/plugins/plugins/documents/codex-mobile-addon.json
grep -q 'schemaDigest' .agents/plugins/plugins/telegram/codex-mobile-addon.json
grep -q -- '--dependency-verification=off' scripts/build-android-providers.sh
grep -q 'b1ea90a3f064dd1c54560081675b41abaa7bc37c' .github/workflows/verify.yml
grep -q 'b1ea90a3f064dd1c54560081675b41abaa7bc37c' .github/workflows/release.yml
if rg -n 'uses: [^ ]+@v[0-9]' .github/workflows; then
  echo "GitHub Actions must be pinned to immutable revisions" >&2
  exit 1
fi
scripts/generate-sbom.py --check

echo "Provider structure verified."
