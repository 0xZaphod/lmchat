# LMChat

## Overview

LMChat is an Android app for chatting with language models hosted on a server you choose. Use LLM servers such as LM Studio, Ollama, llama.cpp, vLLM, and LocalAI over Wi-Fi or a VPN to stream replies, manage chats, and attach documents. Image and camera attachments are available when the selected model reports vision support or you enable its saved image override.

The server runs the model and receives prompts and attachments for inference. LMChat stores conversations and their attachments on the phone until you delete the chat. It does not delete original files selected from the phone.

In Settings, scan for a server or enter its address, then choose a model and start chatting. **Auto-detect** handles server-specific model information where available.

![stack](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-8AB4F8) ![material](https://img.shields.io/badge/Material-3-8AB4F8)

## Features

- **Streaming chat** — watch replies arrive as the server generates them
- **Markdown replies** — headings, emphasis, lists, quotes, links, tables, and monospace code; select and copy response text
- **Images** — gallery and camera options appear when the selected model reports vision support or you enable **Allow images for this model** in the attachment menu; images are sent as data-URIs
- **Documents** — attach PDF, TXT, MD, CSV, JSON, XML, HTML, or source files; text is extracted and inlined into the prompt
- **Network scanner** — finds compatible LLM servers on your local network, with common port shortcuts
- **Multiple chats** — conversation history persisted locally (Room), rename & delete
- **Saved servers** — keep a list of servers, switch per chat or set a default; each chat remembers its server address and port, while API keys and saved server settings are shared
- **Model picker** — choose from the server’s available models for each conversation

## Install

Requires **Android 8.0 (API 26) or newer**. Download `LMChat-<version>.apk` from [GitHub Releases](https://github.com/0xZaphod/lmchat/releases), open it on your phone, and allow installation from your browser or file manager when Android asks. Use APKs signed with the same key to update an existing installation.

The app connects to a server you run separately; it does not run models on the phone.

### In-app updates

LMChat checks the latest stable GitHub release when opened. There are no scheduled or background update checks. Settings also offers **Check for updates**. Tap **Update** to download the release APK, then **Install** to open Android’s confirmation screen. Android may first ask you to allow installations from LMChat. Downloads are stored in the app cache; interrupted downloads can be retried.

Updating preserves local chats and settings. Updates must use the same signing key; debug builds cannot install official releases signed with a different key.

## Build

Requirements: **Java 17**, **Android SDK platform 35**, and an Android Studio version compatible with the AGP version in `gradle/libs.versions.toml` (currently 9.4.1). The Gradle wrapper downloads the configured Gradle version on first use; an internet connection is required for dependencies.

Open the project in Android Studio and let Gradle sync, or set `ANDROID_HOME` to your SDK directory. Alternatively, create an ignored `local.properties` containing `sdk.dir=/path/to/android-sdk`.

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Debug and release builds use different signing keys, so switching between them may require uninstalling the app and losing its local data.

Run document import and send regression tests on a connected Android device or emulator with `./gradlew connectedDebugAndroidTest`.

For release builds and signing, see [Contributing](CONTRIBUTING.md#release-builds).

## Servers and model capabilities

LMChat supports LM Studio, Ollama, llama.cpp, vLLM, LocalAI, and other LLM servers exposing `/v1/models` and streaming `/v1/chat/completions`. Choose **Auto-detect** or the matching server type in Settings.

Enter a host/IP and port, or a full base URL such as `https://models.example.com`. Full URLs supply their own scheme and port; omit API paths such as `/v1/chat/completions`. The server must be reachable from your phone. If it requires an API key, enter it in Settings or the chat’s server dialog.

Image attachments require reported vision support or the saved **Allow images for this model** override in the attachment menu. Documents are extracted as text and work without vision support. Context usage uses the server’s running configuration or a local **context limit override** in Settings; the override does not change the server itself.

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
- **Connection refused** — check that the server is running, accepts network connections, and is allowed through its firewall.
- **Model unavailable** — check that the model is installed and ready on the server, then refresh the model list.

## License

LMChat is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). Use, modification, and redistribution are permitted for the purposes specified in the license, including noncommercial use. Commercial use is not granted by this license.

Third-party dependencies retain their respective licenses.
