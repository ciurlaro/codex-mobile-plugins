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


def properties(path: pathlib.Path) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in path.read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    )


def project_version(path: pathlib.Path) -> str:
    match = re.search(r'^version\s*=\s*"([^"]+)"', path.read_text(), re.MULTILINE)
    if not match:
        raise SystemExit(f"version is missing from {path}")
    return match.group(1)


parser = argparse.ArgumentParser()
parser.add_argument("--host-root", required=True, type=pathlib.Path)
parser.add_argument("--host-revision", required=True)
parser.add_argument("--provider-revision", required=True)
parser.add_argument("--host-apk", required=True, type=pathlib.Path)
parser.add_argument("--documents-apk", required=True, type=pathlib.Path)
parser.add_argument("--telegram-apk", required=True, type=pathlib.Path)
parser.add_argument("--mcp-image-digest", required=True)
parser.add_argument("--output", required=True, type=pathlib.Path)
args = parser.parse_args()

for name, value in (("host", args.host_revision), ("provider", args.provider_revision)):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise SystemExit(f"{name} revision must be an exact Git commit")
if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.mcp_image_digest):
    raise SystemExit("MCP image digest must be immutable")

root = pathlib.Path(__file__).resolve().parent.parent
host = args.host_root.resolve()
host_properties = properties(host / "gradle.properties")
protocol = json.loads((host / "app-server-client/protocol/provenance.json").read_text())
plugins = {}
for name, apk in (("documents", args.documents_apk), ("telegram", args.telegram_apk)):
    bundle = root / ".agents/plugins/plugins" / name
    addon = json.loads((bundle / "codex-mobile-addon.json").read_text())
    definition = json.loads((bundle / ".codex-plugin/plugin.json").read_text())
    plugins[name] = {
        "pluginId": addon["pluginId"],
        "version": definition["version"],
        "implementation": f"io.github.ciurlaro.codexmobile.providers:{name}-android:{addon['implementationVersion']}",
        "schemaSha256": addon["schemaDigest"],
        "contentSha256": tree_sha256(bundle),
        "android": {
            "artifactSha256": sha256(apk),
            "splitNames": addon["android"]["splitNames"],
            "abis": addon["android"]["abis"],
            "hostVersionCode": addon["host"]["versionCode"],
            "providerApi": addon["providerApi"],
        },
    }

manifest = {
    "formatVersion": 1,
    "source": {
        "hostRevision": args.host_revision,
        "providerRevision": args.provider_revision,
    },
    "mobile": {
        "versionName": host_properties["codexMobile.versionName"],
        "versionCode": int(host_properties["codexMobile.versionCode"]),
        "artifactSha256": sha256(args.host_apk),
        "sbom": {
            "path": "codex-mobile/docs/sbom.cdx.json",
            "sha256": sha256(host / "docs/sbom.cdx.json"),
        },
    },
    "appServer": {
        "version": host_properties["codexMobile.codexVersion"],
        "revision": protocol["upstreamRevision"],
        "upstreamTag": protocol["upstreamTag"],
        "target": "aarch64-unknown-linux-musl",
        "archiveSha256": host_properties["codexMobile.codexArchiveSha256"],
        "binarySha256": host_properties["codexMobile.codexBinarySha256"],
        "protocol": {
            "client": f"io.github.ciurlaro.codexmobile:app-server-client:{project_version(host / 'app-server-client/build.gradle.kts')}",
            "sourceSha256": protocol["inputs"][0]["sha256"],
            "outputs": protocol["generator"]["outputs"],
        },
        "host": f"io.github.ciurlaro.codexmobile:runtime-host:{project_version(host / 'runtime-host/build.gradle.kts')}",
    },
    "providerApi": f"io.github.ciurlaro.codexmobile:provider-api:{project_version(host / 'provider-api/build.gradle.kts')}",
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
