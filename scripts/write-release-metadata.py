#!/usr/bin/env python3
import pathlib
import re
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: write-release-metadata.py <release-tag> <image-digest>")

root = pathlib.Path(__file__).resolve().parent.parent
release_tag, image_digest = sys.argv[1:3]
if not re.fullmatch(r"providers-[0-9A-Za-z._-]+", release_tag):
    raise SystemExit("invalid provider release tag")
if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_digest):
    raise SystemExit("invalid image digest")

for plugin in ("documents", "telegram"):
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
