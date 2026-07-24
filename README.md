# Codex Mobile Plugins

This repository distributes the Documents and Telegram plugins for Codex.
Each plugin has one standard Codex bundle, one shared Kotlin schema, an Android
feature split for Codex Mobile, and a Dockerized MCP provider for desktop Codex.

## Install

Add this source in Codex Mobile:

```text
https://github.com/ciurlaro/codex-mobile-plugins
```

Select a plugin and approve Android's package installation prompt. The app
restarts, verifies the split against the plugin schema and host version, disables
the plugin's MCP process on Android, and finishes the standard plugin install.

Desktop Codex uses the same marketplace:

```sh
codex plugin marketplace add https://github.com/ciurlaro/codex-mobile-plugins.git
```

The standard bundles start `ghcr.io/ciurlaro/codex-mobile-plugins:1.0.0` over
stdio. Docker must be installed. Documents mounts the current workspace and has
networking disabled. Telegram persists its TDLib session in a named Docker
volume. The official image contains the publisher's Telegram application ID and
hash; users authenticate only with their phone, code, and optional password.
Connect once before using its tools:

```sh
docker run --rm -it -v codex-mobile-telegram:/state \
  ghcr.io/ciurlaro/codex-mobile-plugins:1.0.0 telegram-auth
```

## Repository layout

- `.agents/plugins/` contains the Codex marketplace, manifests, skills, MCP
  configuration, and Android add-on metadata.
- `shared/` contains the KMP tool schemas used by Android and MCP.
- `android/` contains direct Kotlin Android feature providers. Documents uses
  PdfiumAndroid and bundled ML Kit OCR; Telegram uses TDLib through JNI.
- `mcp-server/` contains the official Kotlin MCP SDK stdio server. Its Documents
  backend uses PDFBox and offline Tess4J; its Telegram backend uses TDLib JNI.
- `Dockerfile` builds the Linux MCP provider and its audited runtime assets.

The Android base app contains none of these provider implementations, models,
or JNI libraries. Feature APKs must match the base package name and version code
and be signed by the same certificate. Official artifacts are therefore built
with the corresponding Codex Mobile release. Forks sign their own base and
matching feature splits.

## Build and verify

Set Java 17 and the Android SDK, then run:

```sh
./gradlew test
bash scripts/build-android-providers.sh ../codex-mobile debug
bash scripts/verify-structure.sh
docker build -t codex-mobile-plugins:local .
bash scripts/verify-mcp.sh
```

`build-android-providers.sh` uses the generic provider-project hook in the exact
host checkout because Android dynamic-feature modules must compile with their
base application. The host does not name or depend on either provider. Gradle's
root-only dependency-verification file remains host-owned, so the script uses
the provider repository's locked and audited dependency inventory rather than
copying provider checksums into the host.

The `1.0.0` Android provider release targets Codex Mobile host version code 3 at
commit `845d022d86465ccd09d12252dfd3a3b4ac0f792e`. CI checks out that exact generic
host revision and builds this repository's feature projects against it.

For release builds, pass `release`, the matching host signing properties, and
publisher-owned `CODEX_MOBILE_TELEGRAM_API_ID` and
`CODEX_MOBILE_TELEGRAM_API_HASH` secrets. The signed Telegram split embeds
those application credentials so end users authenticate with only their phone,
code, and optional password. Publish the feature APKs only after
`scripts/write-addon-checksums.py` records their exact SHA-256 values in the
add-on manifests. Before upload, run `scripts/verify-release-artifacts.sh` with
the signed host, Documents, and Telegram APKs; it verifies the common signer,
package/version/split identity, manifest hashes, native allowlists, and absence
of retired helper payloads.

Build the official MCP image with the same publisher-owned application
credentials:

```sh
docker build \
  --build-arg CODEX_MOBILE_TELEGRAM_API_ID \
  --build-arg CODEX_MOBILE_TELEGRAM_API_HASH \
  -t codex-mobile-plugins:local .
```

These identify the published Telegram application and are embedded in both
distributed clients; they are not user sessions or bot tokens. A custom image
must supply its own values.

## Runtime behavior

App Server plugin configuration is the sole enablement authority. Disabling a
plugin revokes tool execution immediately but retains its split and data.
Uninstall first disables the plugin. Documents removes its snapshots. Telegram
must confirm remote logout before its split and local session can be removed;
ambiguous revocation leaves removal pending for an explicit retry.
If an obsolete authorization cannot be identified by the current client, local
cleanup reports that limitation and directs the user to Telegram's Devices
screen rather than claiming remote revocation.

Installed providers expose project-owned calls and results only. The host keeps
approval, deadline, cancellation, workspace, and mutation-journal checks around
provider execution. Android provider code is loaded directly from the verified
split; it does not use MCP, HTTP, Binder, a shell, or a helper process.

The Linux and Android backends implement the same schemas but use platform-
appropriate libraries. No runtime code or OCR model download occurs after the
Android split or Docker image is installed.
