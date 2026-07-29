#!/usr/bin/env bash
set -euo pipefail

ndk=$1
library=$2
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "Unsupported TDLib build host" >&2; exit 1 ;;
esac
tools="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"

test -s "$library"
strings "$library" | grep -F 'OpenSSL 3.5.7' >/dev/null
if "$tools/llvm-readelf" -l "$library" | grep 'INTERP' >/dev/null; then
  echo "TDLib JNI library unexpectedly has a program interpreter" >&2
  exit 1
fi
if "$tools/llvm-nm" -D "$library" | grep -E ' (main|fork|execve|posix_spawn)$' >/dev/null; then
  echo "TDLib JNI library unexpectedly exposes a process entry point" >&2
  exit 1
fi
