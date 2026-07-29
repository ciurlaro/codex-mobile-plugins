# Third-party notices

The Android Documents provider includes PdfiumAndroid (Apache-2.0), PDFium and its
transitive native libraries (BSD-style notices packaged with the provider), and
bundled ML Kit text recognition under the ML Kit Terms of Service. Exact notices
are under `android/documents/src/main/assets/`.

The Android Telegram provider includes TDLib 1.8.66 at commit
`022d60202e446ad1287b9fb68e687c8a0760788b` (Boost Software License 1.0) and
statically linked OpenSSL 3.5.7 (Apache-2.0). Its notice is packaged under
`android/telegram/src/main/assets/`.

The Docker MCP provider includes:

- Model Context Protocol Kotlin SDK 0.14.0 (MIT);
- Apache PDFBox 3.0.7 (Apache-2.0);
- Tess4J 5.19.0 (Apache-2.0), Ubuntu `libtesseract4` 4.1.1-2.1build1
  (Apache-2.0), Leptonica `liblept5` 1.82.0-3build1 (BSD-2-Clause), and English
  `tessdata_fast` 4.1.0 data (Apache-2.0);
- TDLib 1.8.66 (Boost Software License 1.0) and OpenSSL 3.5.7 (Apache-2.0);
- Kotlin, kotlinx.coroutines, and kotlinx.serialization (Apache-2.0);
- their dependency-locked transitive JVM and native libraries.

Android ML Kit OCR is bundled with the host app and needs neither Google
Play Services nor a runtime model download. The Docker OCR model is copied into
the image at build time and verified by SHA-256. TDLib requires Telegram network
access; Documents does not.
