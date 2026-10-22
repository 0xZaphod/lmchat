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
