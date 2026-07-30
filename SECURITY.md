# Security

Report vulnerabilities through this repository's private GitHub Security Advisory flow. Do not publicly disclose provider-signing, credential, path-authority, authentication, mutation-replay, native-library, model-supply-chain, or container-escape issues before a fix is available.

Provider releases must match the canonical schemas and pinned host revision, contain no configured user secret, and pass the AAR, ABI, Docker, licence, and retired-runtime inspections. The host release applies the official Android signer after compiling the pinned provider libraries into the base APK.
