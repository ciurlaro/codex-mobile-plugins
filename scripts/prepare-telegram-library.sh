#!/usr/bin/env bash
set -euo pipefail

ndk=$1
output=$2
td_version=1.8.66
td_commit=022d60202e446ad1287b9fb68e687c8a0760788b
td_sha256=b0837cd880a6de8d45abdfd5024fe0f042c100eb5f241a5f185ba65579acfc32
openssl_version=3.5.7
openssl_sha256=a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8

case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "Unsupported TDLib build host" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag"
test -x "$toolchain/bin/aarch64-linux-android26-clang"
command -v cmake >/dev/null
command -v ninja >/dev/null
command -v gperf >/dev/null
command -v perl >/dev/null

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
download() {
  local url=$1 sha=$2 target=$3
  curl --fail --location --retry 10 --retry-delay 5 --retry-max-time 180 --retry-all-errors \
    --header 'Accept: application/octet-stream' --header 'X-GitHub-Api-Version: 2022-11-28' \
    --proto '=https' --tlsv1.2 "$url" --output "$target"
  printf '%s  %s\n' "$sha" "$target" | shasum -a 256 --check --status
}

download "https://codeload.github.com/tdlib/td/tar.gz/$td_commit" "$td_sha256" "$work/tdlib.tar.gz"
mkdir "$work/source"
tar -xzf "$work/tdlib.tar.gz" -C "$work/source" --strip-components=1
grep -Fq "project(TDLib VERSION $td_version" "$work/source/CMakeLists.txt"
grep -Fq 'text.text, random_id' "$work/source/td/telegram/MessagesManager.cpp"
grep -Fq 'message will be re-sent after restart' "$work/source/td/telegram/MessagesManager.cpp"
mkdir "$work/source/.git"
printf '%s\n' "$td_commit" > "$work/source/.git/HEAD"

download \
  "https://api.github.com/repos/openssl/openssl/releases/assets/442677812" \
  "$openssl_sha256" "$work/openssl.tar.gz"
mkdir "$work/openssl-source" "$work/openssl"
tar -xzf "$work/openssl.tar.gz" -C "$work/openssl-source" --strip-components=1
(
  cd "$work/openssl-source"
  ANDROID_NDK_ROOT="$ndk" PATH="$toolchain/bin:$PATH" perl Configure android-arm64 \
    -D__ANDROID_API__=26 no-shared no-module no-legacy no-tests no-apps no-docs \
    --prefix="$work/openssl" --openssldir="$work/openssl/ssl"
  ANDROID_NDK_ROOT="$ndk" PATH="$toolchain/bin:$PATH" \
    make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)" install_sw
)

cmake -S "$work/source/example/android" -B "$work/host" \
  -DTD_ANDROID_JSON_JAVA=ON -DTD_ENABLE_LTO=OFF -DPHP_EXECUTABLE=PHP_EXECUTABLE-NOTFOUND
cmake --build "$work/host" --target prepare_cross_compiling --parallel

cmake -S "$work/source/example/android" -B "$work/android" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DOPENSSL_ROOT_DIR="$work/openssl" -DOPENSSL_INCLUDE_DIR="$work/openssl/include" \
  -DOPENSSL_SSL_LIBRARY="$work/openssl/lib/libssl.a" \
  -DOPENSSL_CRYPTO_LIBRARY="$work/openssl/lib/libcrypto.a" -DOPENSSL_USE_STATIC_LIBS=TRUE \
  -DTD_ANDROID_JSON_JAVA=ON -DTD_ENABLE_LTO=ON -DPHP_EXECUTABLE=PHP_EXECUTABLE-NOTFOUND
cmake --build "$work/android" --target tdjni --parallel

library="$work/android/libtdjsonjava.so"
test -s "$library"
strings "$library" | grep -F "OpenSSL $openssl_version" >/dev/null
if "$toolchain/bin/llvm-readelf" -l "$library" | grep 'INTERP' >/dev/null; then
  echo "TDLib JNI library unexpectedly has a program interpreter" >&2
  exit 1
fi
if "$toolchain/bin/llvm-nm" -D "$library" | grep -E ' (main|fork|execve|posix_spawn)$' >/dev/null; then
  echo "TDLib JNI library unexpectedly exposes a process entry point" >&2
  exit 1
fi
mkdir -p "$(dirname "$output")"
install -m 644 "$library" "$output"
