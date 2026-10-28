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

## Version numbering

The release sequence starts at `0.1`. Increase the last component for fixes and polish (`0.1` → `0.1.1`); increase the minor version for new user-facing features and reset the patch component (`0.1.1` → `0.2`). Stay below `1.0` until the app is ready for its first stable release. Keep Android's internal `versionCode` increasing independently, including when renumbering the public version. Historical rebuilds retain their original version codes; new releases must use a higher code.

The original `1.0`–`1.0.10` releases were renumbered to `0.1`–`0.4.5`. Legacy Git tags remain available for old source links. Existing installations displaying `1.0.x` require one manual APK update before the numeric in-app updater can follow the 0.x sequence.

## Release notes

Use [the release-note template](docs/releases/TEMPLATE.md) for both `docs/releases/<version>.md` and the GitHub release body. Keep them identical: one short summary, up to four brief bullets when useful, and the APK download line. A single-fix release can omit the bullets. The GitHub release title is `LMChat <version>`; do not repeat it as a heading in the body.

Describe changes that matter to users. Keep notes short. Omit debug/development installation caveats, build and test reports, device details, signing information, internal implementation details, and repeated license or installation sections. Put contributor validation details in the commit or PR instead. Include a brief data-loss or privacy behavior notice when an update changes those expectations.

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
