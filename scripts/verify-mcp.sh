#!/usr/bin/env bash
set -euo pipefail

image=${1:-codex-mobile-plugins:local}
docker_bin=${DOCKER:-docker}
work=$(mktemp -d)
active_cid=
cleanup() {
  if [[ -n $active_cid ]]; then "$docker_bin" rm -f "$active_cid" >/dev/null 2>&1 || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

verify() {
  local provider=$1 expected=$2 output="$work/$1.jsonl" cidfile="$work/$1.cid" client cid
  (
    printf '%s\n' \
      '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"provider-verifier","version":"1"}}}' \
      '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
      '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' |
      "$docker_bin" run --rm -i --cidfile "$cidfile" \
        --cap-drop=ALL --security-opt=no-new-privileges --read-only \
        --tmpfs /tmp:rw,noexec,nosuid,size=256m --tmpfs /state:rw,noexec,nosuid,size=64m \
        --pids-limit=256 -e "CODEX_PROVIDER=$provider" "$image" >"$output"
  ) &
  client=$!
  for ((attempt = 0; attempt < 100; attempt++)); do
    if valid "$output" "$expected"; then break; fi
    sleep 0.1
  done
  test -s "$cidfile"
  cid=$(<"$cidfile")
  active_cid=$cid
  valid "$output" "$expected"
  "$docker_bin" stop -t 1 "$cid" >/dev/null 2>&1 || true
  wait "$client" || true
  active_cid=
}

valid() {
  local output=$1 expected=$2
  test -s "$output" && jq -s -e --argjson expected "$expected" '
    length == 2 and
    .[0].id == 1 and .[0].result.protocolVersion == "2025-06-18" and
    .[1].id == 2 and ([.[1].result.tools[].name] == $expected)
  ' "$output" >/dev/null
}

verify documents '["documents_read","documents_view_pages","documents_edit"]'
verify telegram '["telegram_list_chats","telegram_list_messages","telegram_search_messages","telegram_search_contacts","telegram_download_media","telegram_send_text","telegram_send_file"]'
echo "MCP stdio verified."
