#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import re


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_sha256(root: pathlib.Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix().encode()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


parser = argparse.ArgumentParser()
parser.add_argument("--provider-revision", required=True)
parser.add_argument("--documents-aar", required=True, type=pathlib.Path)
parser.add_argument("--telegram-aar", required=True, type=pathlib.Path)
parser.add_argument("--mcp-image-digest", required=True)
parser.add_argument("--output", required=True, type=pathlib.Path)
args = parser.parse_args()

if not re.fullmatch(r"[0-9a-f]{40}", args.provider_revision):
    raise SystemExit("provider revision must be an exact Git commit")
if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.mcp_image_digest):
    raise SystemExit("MCP image digest must be immutable")

root = pathlib.Path(__file__).resolve().parent.parent
plugins = {}
for name, aar in (("documents", args.documents_aar), ("telegram", args.telegram_aar)):
    bundle = root / ".agents/plugins/plugins" / name
    addon = json.loads((bundle / "codex-mobile-addon.json").read_text())
    definition = json.loads((bundle / ".codex-plugin/plugin.json").read_text())
    plugins[name] = {
        "pluginId": addon["pluginId"],
        "version": definition["version"],
        "implementation": (
            f"io.github.ciurlaro.codexmobile.providers:{name}-android:"
            f"{addon['implementationVersion']}"
        ),
        "schemaSha256": addon["schemaDigest"],
        "contentSha256": tree_sha256(bundle),
        "android": {
            "delivery": "bundled",
            "providerApi": addon["providerApi"],
            "hostVersionCode": addon["host"]["versionCode"],
            "aarSha256": sha256(aar),
        },
    }

manifest = {
    "formatVersion": 2,
    "source": {"providerRevision": args.provider_revision},
    "providerApi": "io.github.ciurlaro.codexmobile:extension-provider-api:2.0.0",
    "plugins": plugins,
    "mcp": {
        "image": f"ghcr.io/ciurlaro/codex-mobile-plugins@{args.mcp_image_digest}",
        "sbom": {
            "path": "codex-mobile-plugins/docs/sbom.cdx.json",
            "sha256": sha256(root / "docs/sbom.cdx.json"),
        },
    },
}

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
