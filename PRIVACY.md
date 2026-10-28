# Privacy

LMChat is a client for an LLM server you choose. It has no developer-operated chat service, analytics, advertising, or crash-reporting service.

## Chats and servers

When you send a message, LMChat sends the conversation history, system prompt, and attachments in that history to the selected server. Documents are sent as extracted text; images are sent as image data. The server operator controls how that information is processed, logged, and retained. Switching servers can send the existing chat history to the new server.

Saved chats, attachment copies, server addresses, API keys, and settings are stored in the app's private Android storage. API keys are stored in preferences and the local database without additional app-level encryption. They are sent as authorization headers to the configured server. HTTPS uses normal certificate verification. HTTP is supported for local servers and does not encrypt traffic; use a trusted network or VPN for HTTP connections.

Deleting a chat removes its messages and saved attachments from the app database. It does not delete original files, copies retained by a server, or guarantee forensic erasure from storage. Uninstalling the app or clearing its storage removes its local data. LMChat currently has no chat export or restore feature.

Active replies use an Android foreground service to keep receiving text while the app is in the background or the screen is off. The service holds a wake lock only while replies are active and stops when the last reply ends. Its notification contains no conversation text, server addresses, or API keys.

## Android backups

LMChat disables Android cloud backup and excludes app data from Android backup and device-transfer rules. Chats, attachments, credentials, and settings are not intended to be transferred by those mechanisms. Earlier versions enabled Android backup; this change does not establish that historical backups have been erased. Independently created device backups and copies made outside the app remain outside its control.

## Other network requests

LMChat checks GitHub for the latest stable release when the app opens and when you request an update check. Downloading an update contacts GitHub's download infrastructure. GitHub receives normal request information, including your IP address; chat content and server API keys are not included in these update requests.

The network scanner probes addresses on the selected local IPv4 subnet when you start a scan. It does not upload scan results to the developer. Opening a link in a response uses your device's link handler, whose privacy behavior is separate.

## Questions

Report privacy concerns through the project's [GitHub issues](https://github.com/0xZaphod/lmchat/issues). Do not include API keys, private server addresses, or conversation content in public reports.
