#!/usr/bin/env python3
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
components = {}
for lock in root.rglob("gradle.lockfile"):
    if "build" in lock.parts:
        continue
    for line in lock.read_text().splitlines():
        match = re.match(r"([^:#=]+):([^:=]+):([^=]+)=", line)
        if not match:
            continue
        group, name, version = match.groups()
        purl = f"pkg:maven/{group}/{name}@{version}"
        components[purl] = {
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
        }

for component in (
    {
        "type": "container",
        "group": "library",
        "name": "eclipse-temurin",
        "version": "17.0.19_10-jre-jammy",
        "purl": "pkg:docker/eclipse-temurin@17.0.19_10-jre-jammy?repository_url=docker.io/library",
        "hashes": [{"alg": "SHA-256", "content": "475d8e96b4b2bfe08999e5e854755c773af1581acdf959a4545d88f0696a2339"}],
        "licenses": [{"license": {"name": "Eclipse Temurin container notices"}}],
    },
    {
        "type": "library",
        "group": "tdlib",
        "name": "td",
        "version": "1.8.66+022d60202e446ad1287b9fb68e687c8a0760788b",
        "purl": "pkg:github/tdlib/td@022d60202e446ad1287b9fb68e687c8a0760788b",
        "hashes": [{"alg": "SHA-256", "content": "b0837cd880a6de8d45abdfd5024fe0f042c100eb5f241a5f185ba65579acfc32"}],
        "licenses": [{"license": {"id": "BSL-1.0"}}],
    },
    {
        "type": "library",
        "group": "openssl",
        "name": "openssl",
        "version": "3.5.7",
        "purl": "pkg:generic/openssl@3.5.7",
        "hashes": [{"alg": "SHA-256", "content": "a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8"}],
        "licenses": [{"license": {"id": "Apache-2.0"}}],
    },
    {
        "type": "data",
        "group": "tesseract-ocr",
        "name": "tessdata_fast-eng",
        "version": "4.1.0",
        "purl": "pkg:github/tesseract-ocr/tessdata_fast@4.1.0",
        "hashes": [{"alg": "SHA-256", "content": "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"}],
        "licenses": [{"license": {"id": "Apache-2.0"}}],
    },
    {
        "type": "library",
        "group": "ubuntu",
        "name": "libtesseract4",
        "version": "4.1.1-2.1build1",
        "purl": "pkg:deb/ubuntu/libtesseract4@4.1.1-2.1build1?arch=arm64",
        "licenses": [{"license": {"id": "Apache-2.0"}}],
    },
    {
        "type": "library",
        "group": "ubuntu",
        "name": "liblept5",
        "version": "1.82.0-3build1",
        "purl": "pkg:deb/ubuntu/liblept5@1.82.0-3build1?arch=arm64",
        "licenses": [{"license": {"id": "BSD-2-Clause"}}],
    },
):
    component["bom-ref"] = component["purl"]
    components[component["purl"]] = component

bom = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "serialNumber": "urn:uuid:9bf34c77-c739-4621-91cb-1b1488b41aa9",
    "version": 1,
    "metadata": {"component": {"type": "application", "name": "codex-mobile-plugins", "version": "1.0.0"}},
    "components": [components[key] for key in sorted(components)],
}
destination = root / "docs/sbom.cdx.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(bom, indent=2) + "\n")
