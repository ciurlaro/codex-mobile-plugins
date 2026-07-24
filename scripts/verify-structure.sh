#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

test -f .agents/plugins/marketplace.json
for plugin in documents telegram; do
  test -f ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json"
  test -f ".agents/plugins/plugins/$plugin/.mcp.json"
  test -f ".agents/plugins/plugins/$plugin/codex-mobile-addon.json"
  test -f ".agents/plugins/plugins/$plugin/skills/$plugin/SKILL.md"
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.mcp.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/codex-mobile-addon.json" >/dev/null
done

test -f shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/DocumentsTools.kt
test -f shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramTools.kt
test -f android/documents/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/DocumentsProvider.kt
test -f android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/TelegramProvider.kt
test -f mcp-server/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/Main.kt
test -f Dockerfile
test -x scripts/verify-mcp.sh
test -x scripts/verify-release-artifacts.sh
test -f .github/workflows/verify.yml

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
grep -q 'network=none' .agents/plugins/plugins/documents/.mcp.json
grep -q 'CODEX_MOBILE_TELEGRAM_API_ID' Dockerfile
grep -q 'CODEX_MOBILE_TELEGRAM_API_HASH' Dockerfile
if rg -n -- '-e TELEGRAM_API_(ID|HASH)' .agents/plugins; then
  echo "Published Telegram application credentials belong in the provider image, not user configuration" >&2
  exit 1
fi
grep -q 'TDLIB_COMMIT' android/telegram/build.gradle.kts
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

echo "Provider structure verified."
