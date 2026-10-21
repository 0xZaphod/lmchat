# LMChat

## Overview

LMChat is an Android app for chatting with language models hosted on a server you choose. It supports LM Studio, Ollama, llama.cpp, and servers with a compatible OpenAI-style API, including vLLM and LocalAI. Connect over your Wi-Fi network or a VPN, then stream replies, manage chats, and attach documents. Image and camera attachments are available when the selected model reports that it supports vision.

The server runs the model and receives prompts and attachments for inference. LMChat stores conversations and their attachments on the phone until you delete the chat. It does not delete original files selected from the phone.

In Settings, choose a server type, enter its address, or scan a subnet for compatible servers. Choose **Auto-detect** to let LMChat try its supported server metadata APIs. The generic OpenAI-compatible option works with `/v1/models` and `/v1/chat/completions`; metadata such as vision support or configured context length may vary by server.

![stack](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-8AB4F8) ![material](https://img.shields.io/badge/Material-3-8AB4F8)

## Features

- **Streaming chat** — token-by-token SSE streaming through the OpenAI-compatible chat API
- **Images** — gallery and camera options appear when the selected model reports vision support; images are sent as data-URIs
- **Documents** — attach PDF, TXT, MD, CSV, JSON, XML, HTML, or source files; text is extracted and inlined into the prompt
- **Network scanner** — finds OpenAI-compatible servers on your Wi-Fi subnet, with common port shortcuts
- **Multiple chats** — conversation history persisted locally (Room), rename & delete
- **Saved servers** — keep a list of servers, switch per chat or set a default
- **Model picker** — live model list from `/v1/models`, selectable per conversation

## Install

Requires **Android 8.0 (API 26) or newer**. Download `LMChat-<version>.apk` from [GitHub Releases](https://github.com/0xZaphod/lmchat/releases), open it on your phone, and allow installation from your browser or file manager when Android asks. Use APKs signed with the same key to update an existing installation.

The app connects to a server you run separately; it does not run models on the phone.

## Build

Requirements: **Java 17**, **Android SDK platform 35**, and an Android Studio version compatible with the AGP version in `gradle/libs.versions.toml` (currently 9.4.1). The Gradle wrapper downloads the configured Gradle version on first use; an internet connection is required for dependencies.

Open the project in Android Studio and let Gradle sync, or set `ANDROID_HOME` to your SDK directory. Alternatively, create an ignored `local.properties` containing `sdk.dir=/path/to/android-sdk`.

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Debug and release builds use different signing keys, so switching between them may require uninstalling the app and losing its local data.

### Release builds

```bash
./gradlew assembleRelease
```

To sign your own release, create an ignored `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/your-release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyAliasPassword=YOUR_KEY_PASSWORD
```

With signing configured, the output is `app/build/outputs/apk/release/LMChat-<versionName>.apk`. Without it, the release is unsigned and must be signed before installation. Keep your key and passwords private and backed up; updates require the same signing key. Increment `versionCode` for each release and set the user-facing `versionName` in `app/build.gradle.kts`.

GitHub Actions runs unit tests and builds a debug APK on pushes and pull requests, without release credentials. Its APK artifact is a development build. Publish signed APKs separately through GitHub Releases.

## LM Studio setup

1. In LM Studio, open the **Developer** tab and start the **Local Server** (default port **1234**)
2. Load a model in the playground first — the server serves loaded models
3. Enable network access in the server and make sure the phone can reach its listening address and port.
4. In LMChat:
   - **Scan:** open Settings, select port 1234, scan, and add your server. Select **Use** in Saved servers.
   - **Manual:** enter the host (your PC's Wi-Fi IP, e.g. `192.168.1.20`), port, and server type in Settings, then save. The chat model selector also opens the saved-server picker when no models are available.

For manual connections, enter a bare host/IP and port, or a full URL such as `https://models.example.com`. Full URLs supply their own port (443 for HTTPS or 80 for HTTP unless specified), so the separate Port field can be left empty. Enter the server base URL without `/v1/models` or `/v1/chat/completions`; LMChat appends the API routes.

## Other servers and model capabilities

Choose a server type in Settings or the chat's manual server form. Existing servers use **Auto-detect**.

| Server type | Common port | Vision and configured context metadata |
| --- | --- | --- |
| LM Studio | 1234 | `/api/v1/models`: capabilities and loaded-instance configuration |
| Ollama | 11434 | `/api/show`: capabilities; `/api/ps`: running model's context length |
| llama.cpp | 8080 (configurable) | `/props?model=…`: modalities and per-slot context length |
| OpenAI-compatible (vLLM, LocalAI, others) | Depends on server | Explicit capability fields in `/v1/models`, when provided; `max_model_len` for vLLM |

All types use `/v1/models` and streaming `/v1/chat/completions`, with optional Bearer authentication. Configure the server to listen on a network address reachable by the phone. The scanner checks the selected port; the shortcuts can be overridden.

Vision support is checked again when the model/server changes or the model list is refreshed. Unknown capability data is not treated as support: image/camera options stay hidden with an explanation, while documents remain available. Sending images already attached to a conversation also requires confirmed vision support. No model-name guessing is used.

Context limits come from the running configuration, never the model's training maximum. A model that loads on demand may report its context limit only after the first message. For servers that do not expose it, Settings has an optional per-server **context limit override**, in tokens. This only changes the display; it does not configure the server. Generic compatibility does not guarantee metadata availability or image support on every model.

Protocol and metadata regression checks: `./gradlew testDebugUnitTest`. Document import and send regression tests run on a connected Android device/emulator with `./gradlew connectedDebugAndroidTest`.

### Notes

- **Images** require a model that reports vision support. If the server omits capabilities, refresh the model list or choose its specific server type.
- **Documents** are converted to plain text (PDF via PDFBox-Android, others decoded as UTF-8) and appended to the prompt as fenced code blocks — large documents are truncated to ~120 KB of text.
- **API key:** if you've set one in LM Studio, enter it in Settings or the per-chat server dialog.

## Project structure

```
app/src/main/java/dev/lmchat/
├── LMChatApplication.kt      # AppContainer (manual DI) + Application
├── MainActivity.kt           # Entry point + navigation graph
├── domain/Models.kt          # Shared domain types
├── data/
│   ├── local/                # Room: entities, DAOs, database
│   ├── remote/               # API client (SSE), DTOs, metadata, request builder
│   ├── repository/           # Chat/Server repositories, settings store
│   ├── scanner/NetworkScanner.kt
│   └── util/AttachmentFactory.kt   # image downscale/base64, PDF extraction
└── ui/
    ├── theme/                # Always-dark Material 3 palette
    └── screens/              # ChatList, Chat, Scanner, Settings (+ ViewModels)
```

## Troubleshooting

- **Scan finds nothing** — confirm both devices share a Wi-Fi SSID; some routers isolate phones from LAN clients (AP isolation). Enter the host manually in that case.
- **Connection refused** — LM Studio server not started, or firewall is blocking it (allow `lms` on your LAN).
- **Model won't load** — the model must be loaded in LM Studio before the server will serve it.

## License

LMChat is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). Use, modification, and redistribution are permitted for the purposes specified in the license, including noncommercial use. Commercial use is not granted by this license.

Third-party dependencies retain their respective licenses.
