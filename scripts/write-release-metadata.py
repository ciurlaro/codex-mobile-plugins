#!/usr/bin/env python3
import hashlib
import json
import pathlib
import re
import sys

if len(sys.argv) != 5:
    raise SystemExit(
        "usage: write-release-metadata.py <release-tag> <image-digest> <documents.apk> <telegram.apk>"
    )

root = pathlib.Path(__file__).resolve().parent.parent
release_tag, image_digest = sys.argv[1:3]
if not re.fullmatch(r"providers-[0-9A-Za-z._-]+", release_tag):
    raise SystemExit("invalid provider release tag")
if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_digest):
    raise SystemExit("invalid image digest")

for plugin, apk_name in zip(("documents", "telegram"), sys.argv[3:]):
    apk = pathlib.Path(apk_name)
    manifest = root / ".agents/plugins/plugins" / plugin / "codex-mobile-addon.json"
    data = json.loads(manifest.read_text())
    data["android"]["package"]["url"] = (
        f"https://github.com/ciurlaro/codex-mobile-plugins/releases/download/{release_tag}/provider_{plugin}.apk"
    )
    data["android"]["package"]["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest.write_text(json.dumps(data, indent=2) + "\n")

    mcp = root / ".agents/plugins/plugins" / plugin / ".mcp.json"
    value = mcp.read_text()
    value, count = re.subn(
        r"ghcr\.io/ciurlaro/codex-mobile-plugins(?::[^ @\"']+|@sha256:[0-9a-f]{64})",
        f"ghcr.io/ciurlaro/codex-mobile-plugins@{image_digest}",
        value,
    )
    if count != 1:
        raise SystemExit(f"expected one MCP image reference for {plugin}, found {count}")
    mcp.write_text(value)
