#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

test -f LICENSE
test -f LICENSES/MLKIT-EXCEPTION.txt
test -f THIRD_PARTY_NOTICES.md
test -f gradle/verification-metadata.xml
test -f gradle/libs.versions.toml
test -f build-logic/src/main/kotlin/PrepareTelegramLibraryTask.kt
test -f build-logic/src/main/kotlin/codexmobile.provider-android-library.gradle.kts
test -f build-logic/src/main/kotlin/codexmobile.provider-kmp-library.gradle.kts
test -f build-logic/src/main/kotlin/codexmobile.provider-jvm-application.gradle.kts
test -f build-logic/src/main/kotlin/codexmobile.telegram-library.gradle.kts
test -f build-logic/src/test/kotlin/PrepareTelegramLibraryTaskTest.kt
grep -q 'name = "codex-mobile-plugins-build-logic"' settings.gradle.kts
grep -q 'GNU GENERAL PUBLIC LICENSE' LICENSE
grep -q 'GNU GPL version 3 section 7' LICENSES/MLKIT-EXCEPTION.txt

expected_builds=$(printf '%s\n' \
  android/documents/build.gradle.kts \
  android/telegram/build.gradle.kts \
  build-logic/build.gradle.kts \
  build.gradle.kts \
  documents/build.gradle.kts \
  mcp-server/build.gradle.kts \
  telegram/build.gradle.kts)
actual_builds=$(git ls-files --cached --others --exclude-standard '*build.gradle.kts' | sort)
test "$actual_builds" = "$expected_builds" || {
  echo "Provider module/build-logic descriptor set is not intentional" >&2
  exit 1
}

while IFS= read -r source; do
  case "$source" in
    build.gradle.kts|settings.gradle.kts|*/build.gradle.kts|build-logic/settings.gradle.kts|\
    build-logic/src/main/kotlin/*.kt|build-logic/src/main/kotlin/*.kts|\
    build-logic/src/test/kotlin/*.kt|build-logic/src/test/kotlin/*.kts|\
    documents/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/*.kt|\
    documents/src/commonTest/kotlin/io/github/ciurlaro/codexmobile/providers/documents/*.kt|\
    documents/src/jvmMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/*.kt|\
    telegram/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/*.kt|\
    telegram/src/commonTest/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/*.kt|\
    telegram/src/jvmMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/*.kt|\
    mcp-server/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/*.kt|\
    mcp-server/src/test/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/*.kt|\
    android/documents/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/*.kt|\
    android/documents/src/androidTest/kotlin/io/github/ciurlaro/codexmobile/platform/android/*.kt|\
    android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/*.kt|\
    android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/*.kt|\
    android/telegram/src/androidTest/kotlin/io/github/ciurlaro/codexmobile/platform/android/*.kt) ;;
    *) echo "Kotlin source uses an unsupported root or package: $source" >&2; exit 1 ;;
  esac
done < <(git ls-files --cached --others --exclude-standard '*.kt' '*.kts')

test -f .agents/plugins/marketplace.json
for plugin in documents telegram; do
  grep -q '"path": "\./\.agents/plugins/plugins/'"$plugin"'"' .agents/plugins/marketplace.json
  test -f ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json"
  test -f ".agents/plugins/plugins/$plugin/.mcp.json"
  test -f ".agents/plugins/plugins/$plugin/codex-mobile-addon.json"
  test -f ".agents/plugins/plugins/$plugin/skills/$plugin/SKILL.md"
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/.mcp.json" >/dev/null
  python3 -m json.tool ".agents/plugins/plugins/$plugin/codex-mobile-addon.json" >/dev/null
  grep -q '"license": "GPL-3.0-or-later"' ".agents/plugins/plugins/$plugin/.codex-plugin/plugin.json"
  grep -q '"versionCode": 5' ".agents/plugins/plugins/$plugin/codex-mobile-addon.json"
done

test -f documents/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/DocumentsTools.kt
test -f documents/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents/DocumentsSemantics.kt
test -f telegram/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramTools.kt
test -f telegram/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramSemantics.kt
test -f telegram/src/jvmMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramIntegration.kt
test "$(find . -path '*/src/*' -name TelegramIntegration.kt | wc -l | tr -d ' ')" = 1
test "$(find . -path '*/src/*' -name TdLibTransport.kt | wc -l | tr -d ' ')" = 1
test "$(find . -path '*/src/*' -name JsonClient.java | wc -l | tr -d ' ')" = 1
test -f android/documents/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/DocumentsProvider.kt
test -f android/telegram/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/TelegramProvider.kt
grep -q 'id("codexmobile.provider-android-library")' android/documents/build.gradle.kts
grep -q 'id("codexmobile.provider-android-library")' android/telegram/build.gradle.kts
grep -q 'id("codexmobile.telegram-library")' android/telegram/build.gradle.kts
if rg -n 'tasks\.register<Exec>|(^|[[:space:]])(allprojects|subprojects)[[:space:]]*\{' \
    --glob '*.kts' --glob '!**/build/**'; then
  echo "Builds must use typed tasks and self-applied conventions" >&2
  exit 1
fi
for provider in android/{documents,telegram}/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/*Provider.kt; do
  grep -q 'minHostVersionCode = 5' "$provider"
  grep -q 'maxHostVersionCode = 5' "$provider"
done
test -f mcp-server/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/mcp/Main.kt
test -f Dockerfile
grep -q '^COPY build-logic ./build-logic$' Dockerfile
grep -q '^COPY documents ./documents$' Dockerfile
grep -q '^COPY telegram ./telegram$' Dockerfile
test -x scripts/verify-mcp.sh
test -x scripts/run-android-device-tests.sh
test -x scripts/verify-telegram-library.sh
test -x scripts/write-release-metadata.py
test -x scripts/write-release-manifest.py
test -f .github/workflows/verify.yml
test -f .github/workflows/release.yml

if rg -n 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec|java\.lang\.Process|/bin/(sh|bash)' android --glob '!**/build/**'; then
  echo "Android providers must not launch helper processes" >&2
  exit 1
fi
if rg -n 'mutool|officecli|tg_?cli|node_modules|preparePrivateBackends|PrivateBackendBundle' . \
    --glob '!**/build/**' --glob '!scripts/verify-structure.sh'; then
  echo "A removed command backend remains" >&2
  exit 1
fi

grep -q 'io.modelcontextprotocol:kotlin-sdk-server' gradle/libs.versions.toml
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
grep -q 'github.com/openssl/openssl/releases/download/openssl-3.5.7/openssl-3.5.7.tar.gz' Dockerfile
grep -q 'github.com/openssl/openssl/releases/download/openssl-3.5.7/openssl-3.5.7.tar.gz' scripts/prepare-telegram-library.sh
grep -q -- '--target tdjson_static' Dockerfile
grep -q -- '-DTD_ENABLE_JNI=ON' Dockerfile
grep -q -- '-DTD_INSTALL_SHARED_LIBRARIES=OFF' Dockerfile
if rg -n '^FROM [^@ ]+( AS .+)?$|--target install' Dockerfile; then
  echo "Container bases and native build targets must be immutable and library-only" >&2
  exit 1
fi
grep -q 'schemaDigest' .agents/plugins/plugins/documents/codex-mobile-addon.json
grep -q 'schemaDigest' .agents/plugins/plugins/telegram/codex-mobile-addon.json
if rg -n -- '--dependency-verification=off|src/commonMain.*directories|sourceSets\[[^]]+\]\.kotlin\.directories' \
    scripts/build-android-providers.sh android settings.gradle.kts; then
  echo "Provider builds must use verified artifacts, not verification bypasses or injected sources" >&2
  exit 1
fi
if rg -n 'project\(":(app:android|agent:codex|platform:android)"\)|codexMobile\.providerProjects' \
    android scripts settings.gradle.kts; then
  echo "Provider implementations must not depend on the mobile host graph" >&2
  exit 1
fi
grep -q 'codexMobile.providerBuild' scripts/build-android-providers.sh
grep -q ':documents-android:assembleDebug' scripts/build-android-providers.sh
grep -q ':telegram-android:assembleDebug' scripts/build-android-providers.sh
grep -q ':app:assembleDebug' scripts/build-android-providers.sh
host_revision=$(sed -n 's/^codexMobile.hostRevision=//p' gradle.properties)
[[ "$host_revision" =~ ^[0-9a-f]{40}$ ]]
grep -Fq 'ref: ${{ steps.host.outputs.revision }}' .github/workflows/verify.yml
if rg -n 'io\.github\.ciurlaro\.codexmobile:provider-api|codexMobile\.providerApiBuild|host/provider-api|:provider_(documents|telegram)|:app:android|host/app/android|host/providers/' \
    settings.gradle.kts android scripts .github README.md docs CONTRIBUTING.md SECURITY.md THIRD_PARTY_NOTICES.md \
    --glob '!scripts/verify-structure.sh'; then
  echo "Legacy provider API coordinates, modules, or artifact paths remain" >&2
  exit 1
fi
grep -q 'write-release-manifest.py' .github/workflows/release.yml
grep -Fq 'name: codex-mobile-provider-candidate' .github/workflows/release.yml
if rg -n 'uses: [^ ]+@v[0-9]' .github/workflows; then
  echo "GitHub Actions must be pinned to immutable revisions" >&2
  exit 1
fi
scripts/generate-sbom.py --check

echo "Provider structure verified."
