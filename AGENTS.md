# Contributing to LMChat

LMChat is a Kotlin Android app built with Jetpack Compose and Material 3. Keep the always-dark theme and support for OpenAI-compatible servers.

## Build and validation

- Use Java 17 and Android SDK platform 35. Use the checked-in Gradle wrapper; dependency versions live in `gradle/libs.versions.toml`.
- Run `./gradlew :app:testDebugUnitTest assembleDebug` for protocol, metadata, context tracking, and compilation checks.
- Run `./gradlew connectedDebugAndroidTest` on a device/emulator for document import and send regression tests.
- Run `./gradlew assembleRelease` when changing release packaging. Release signing is optional for contributors; see README.md.
- Report device testing separately from builds and unit tests. Never claim an APK was exercised on a device unless it was.

## Architecture

- `domain/Models.kt`: shared types and endpoint handling.
- `data/local`: Room entities and DAOs. IDs are String UUIDs; preserve relationships and add migrations for schema changes.
- `data/remote`: OpenAI-compatible requests, SSE, and server-specific capability metadata.
- `data/repository`: persistence, streaming lifecycle, settings, and context token tracking.
- `data/scanner`: local IPv4 discovery.
- `ui/screens`: Compose screens and ViewModels; `ui/components`: shared UI, including assistant Markdown; `ui/theme`: the dark Material 3 theme.
- Manual dependency injection uses the top-level `dev.lmchat.AppContainer`. ViewModels expose factories; do not introduce Hilt without a reason.

## Behavior to preserve

- Full HTTP(S) URLs own their scheme and port; bare hosts require the separate port. Use the shared endpoint helpers.
- Render assistant Markdown only in the UI; retain original message text in storage and API history. Keep native text selectable, parsing off the main thread, and streaming updates conflated.
- Keep request serialization separate from decoding: requests need `encodeDefaults = true` so `stream: true` reaches the server.
- SSE uses an unlimited read timeout. Keep persistence and finalization serialized, preserve partial replies on stop, and surface errors on empty replies.
- Rebuild the full message history for each request. Documents become extracted text; images use `image_url` data-URI parts.
- Confirm image capability through server metadata, never model-name guesses. Unknown metadata must not prevent text chat.
- Context limits use a positive local override first, then the running server configuration. Never substitute a model's training maximum. Local overrides must appear without waiting for a network lookup.
- Camera capture uses FileProvider. Image/document work belongs off the main thread.
- Use the Android PDFBox port (`com.tom-roush:pdfbox-android`), initialized with `PDFBoxResourceLoader` in the Application. Extract text with `PDDocument.load` and `PDFTextStripper`. Desktop Apache PDFBox depends on missing `java.awt` classes and crashes on Android; JVM tests alone will not catch that. Preserve packaging exclusions and matching ProGuard rules.
- The app permits HTTP for local servers. Preserve normal certificate verification for HTTPS.

## Repository hygiene

- Never commit API keys, signing credentials, keystores, local SDK paths, private hostnames, or user conversations/screenshots.
- Keep `keystore.properties` and `local.properties` local. Back up signing material privately.
- Bump `versionCode` for released updates. The public version is `versionName`; release APKs are `LMChat-<versionName>.apk`.
- Use `docs/releases/TEMPLATE.md` for public release notes and keep local notes identical to GitHub. Keep them short and user-facing; omit debug-installation caveats and build/test details.
- Keep documentation focused on current behavior, not machine-specific setup or debugging transcripts.
