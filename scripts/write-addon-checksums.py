#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: write-addon-checksums.py <documents.apk> <telegram.apk>")

root = pathlib.Path(__file__).resolve().parent.parent
for plugin, apk_name in zip(("documents", "telegram"), sys.argv[1:]):
    apk = pathlib.Path(apk_name)
    manifest = root / ".agents/plugins/plugins" / plugin / "codex-mobile-addon.json"
    data = json.loads(manifest.read_text())
    data["android"]["package"]["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest.write_text(json.dumps(data, indent=2) + "\n")
