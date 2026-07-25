# Codex Mobile Plugins

This is the canonical repository for owner-reviewed Codex Mobile providers. It distributes the Documents and Telegram plugins for Codex.
Each plugin has one standard Codex bundle, one Kotlin capability artifact, an Android
feature split for Codex Mobile, and a Dockerized MCP provider for desktop Codex.

## Install

Add this source in Codex Mobile:

```text
https://github.com/ciurlaro/codex-mobile-plugins
```

The official Codex Mobile app accepts Android provider code only from this Git origin. Ordinary Codex plugins without Android code remain installable from any source. Select a plugin and approve Android's package installation prompt. The app
restarts, verifies the split against the plugin schema and host version, disables
the plugin's MCP process on Android, and finishes the standard plugin install.
Telegram then asks for the application's API ID and API hash in its Settings
screen. Codex Mobile encrypts them in Telegram's own Android Keystore-backed
secret namespace; they are not part of the feature APK.

Desktop Codex uses the same marketplace:

```sh
codex plugin marketplace add https://github.com/ciurlaro/codex-mobile-plugins.git
```

The standard bundles start `ghcr.io/ciurlaro/codex-mobile-plugins:1.0.0` over
stdio. Docker must be installed. Documents mounts the current workspace and has
networking disabled. Telegram persists its TDLib session in a named Docker
volume and reads its separately supplied application credentials from the
desktop environment. Connect once before using its tools:

```sh
export TELEGRAM_API_ID=...
export TELEGRAM_API_HASH=...
docker run --rm -it -v codex-mobile-telegram:/state \
  -e TELEGRAM_API_ID -e TELEGRAM_API_HASH \
  ghcr.io/ciurlaro/codex-mobile-plugins:1.0.0 telegram-auth
```

Obtain one application credential pair from
[`my.telegram.org`](https://my.telegram.org). It identifies the Telegram client,
not the user's authenticated session. Keep these variables available when Codex
starts the Telegram MCP provider.

## Repository layout

- `.agents/plugins/` contains the Codex marketplace, manifests, skills, MCP
  configuration, and Android add-on metadata.
- `documents/` and `telegram/` contain the shared capability semantics used by Android and MCP.
- `android/` contains publishable Android provider implementation libraries. Documents uses
  PdfiumAndroid and bundled ML Kit OCR; Telegram uses TDLib through JNI. The base-app feature
  wrappers live in `codex-mobile` and contain no provider behavior.
- `mcp-server/` contains the official Kotlin MCP SDK stdio server. Its Documents
  backend uses PDFBox and offline Tess4J; its Telegram backend uses TDLib JNI.
- `Dockerfile` builds the Linux MCP provider and its audited runtime assets.

The Android base app contains none of these provider implementations, models,
or JNI libraries. Feature APKs must match the base package name and version code
and be signed by the same certificate. Official artifacts are therefore built
with the corresponding Codex Mobile release. Forks sign their own base and
matching feature splits.

Android provider release builds are shrunk and optimized without name
obfuscation. The host preserves the project-owned provider API names, so a
split built and published separately never depends on a particular base APK's
R8 mapping.

## Build and verify

Set Java 17 and the Android SDK, then run:

```sh
./gradlew :test
bash scripts/build-android-providers.sh ../codex-mobile debug
bash scripts/verify-structure.sh
docker build -t codex-mobile-plugins:local .
bash scripts/verify-mcp.sh
```

GitHub Actions is authoritative for Android instrumentation builds, multi-architecture Docker, SBOM, and release verification. Local Docker and release builds are optional diagnostics; production signing and publishing happen only in the manually approved CI release environment.

`build-android-providers.sh` uses a composite build to substitute the same
published capability and Android implementation coordinates. The host-owned
dynamic-feature wrappers compile with their base application; no provider source
or build directory enters the host graph. Gradle dependency verification stays
enabled for the complete Android dependency closure.

The `1.0.0` Android provider release targets Codex Mobile host version code 4 at
commit `84171210bedc07fc126402cf875b3fb9a880774d`. CI checks out that exact generic
host revision and builds this repository's feature projects against it.

For release builds, pass `release` and the matching host signing properties.
Application credentials are configured per installed plugin and never enter the
build. Publish the feature APKs only after
`scripts/write-release-metadata.py` records their release URLs, exact SHA-256
values, and immutable MCP image digest. Before upload, run `scripts/verify-release-artifacts.sh` with
the signed host, Documents, and Telegram APKs; it verifies the common signer,
package/version/split identity, manifest hashes, native allowlists, and absence
of retired helper payloads. The release also includes one deterministic
`release-manifest.json` binding the exact host, App Server protocol/runtime,
provider API, plugin content, signed APKs, MCP image, compatibility ranges, and
SBOMs by version and SHA-256.

Build the MCP image without credentials:

```sh
docker build -t codex-mobile-plugins:local .
```

The Telegram MCP process reads `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` only at
runtime. The image, repository, Android split, and add-on metadata contain no
configured application credentials.

## Runtime behavior

App Server plugin configuration is the sole enablement authority. Disabling a
plugin revokes tool execution immediately but retains its split, secret
namespace, and data.
Uninstall first disables the plugin. Documents removes its snapshots. Telegram
must confirm remote logout before its split and local session can be removed;
ambiguous revocation leaves removal pending for an explicit retry.
The host deletes the plugin's secret namespace only after that cleanup succeeds.
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

The Android Documents provider uses bundled Google ML Kit OCR. Recognition is available offline, but Google's terms state that ML Kit may contact Google for metrics, fixes, model updates, or compatibility information. The exact runtime closure is recorded in the release SBOM.

## Licence

Project code is distributed under `GPL-3.0-or-later`. The narrow section-7 permission in [`LICENSES/MLKIT-EXCEPTION.txt`](LICENSES/MLKIT-EXCEPTION.txt) applies only to the Documents provider's declared ML Kit Android OCR closure. It grants no permission for another proprietary dependency. Every bundled library and model retains its own licence and notice.
