# Contributing to LMChat

See the [README](README.md#build) for build requirements, debug builds, and validation commands.

## Release builds

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

## Publishing updates

The in-app updater requires a higher numeric release version, a higher APK `versionCode`, and the same package name and signing key as the installed app. Publish the signed `LMChat-<versionName>.apk` asset on a stable GitHub release tagged `<versionName>` or `v<versionName>`.

## Release validation

Before uploading an APK, run the unit tests, debug build, connected Android tests, and release build. Verify the actual APK with the Android SDK's `apksigner verify --verbose --print-certs <apk>` and compare its certificate SHA-256 digest with the previous public APK. Checking for `META-INF/*.RSA` or `*.SF` ZIP entries is insufficient: APK v2 signatures live in a separate signing block.

The Android tests cover bounded imports, document sending, concurrent chats, duplicate-send rejection, cancellation/retry, recovery of persisted streaming rows after reopening the database, and migrations from database versions 1–3. Reopening a database simulates persisted process-death state; it does not exercise Android killing the app process.

Also run these manual checks on the release candidate, using synthetic conversations and a test server:

- Install the previous public APK and create a chat with messages and attachments. Install the candidate as an update without clearing storage; confirm history, attachments, server settings, and selected models remain available.
- Send a reply, rapidly tap send, navigate between two chats, and stop one reply. Confirm no duplicate user messages and that stopping one chat does not stop another.
- Background the app and return during a reply. Then force-stop it during another reply and reopen it. Confirm saved partial text is preserved and the interrupted reply can be retried.
- Exercise the in-app update download and Android's installer UI using successive signed versions. Unit and instrumentation tests do not establish that the installer/update flow was exercised.
- Verify cloud-backup/device-transfer exclusions on representative Android versions. Automated manifest/resource checks alone do not prove a device manufacturer's transfer behavior.

Record builds, automated device tests, and manual install/update checks separately. Never report the manual checks as passed based only on instrumentation results. Keep exported Room schemas under `app/schemas` when changing database entities, add explicit migrations, and test preservation of existing data; do not restore destructive migration fallback.
