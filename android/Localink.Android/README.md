# Localink Android

This repository contains the Android companion app for Localink.

Localink is an offline Windows-to-Android bridge focused on:
- local discovery
- QR-assisted pairing
- direct local connection
- chat
- local file transfer

This Android app is intended to work with the Windows Localink host on the same
hotspot, Wi-Fi, or LAN.

## Current Release

- App name: `Localink`
- Android package id: `com.localbridge.android`
- Version name: `1.1.0`
- Version code: `3`
- Main protocol version: `1.1`

## What The App Does

The Android app provides:
- local peer discovery
- manual pairing with the Windows pairing token
- QR scanning to speed up pairing and connection
- automatic direct connection attempt after QR scan
- trusted device persistence
- text chat
- LAN image and small file transfer
- transfer history with image previews
- Android share-target support for sending files into Localink
- local logs and receive-folder settings

## Important Recent Updates

The current version includes the recent development work completed in this
project:

- QR-based pairing support with camera scanning
- QR entry integrated into the modern Android UI
- direct connection attempt immediately after QR scan
- Android share flow from file manager / gallery into Localink
- automatic handoff into the send flow after opening Localink from Android Share
- transfer preview improvements
- Windows UI updates to support QR pairing visually
- release packaging updates for Windows and Android `v1.1.0`

## Recommended Usage Flow

### Normal pairing flow

1. Open Localink on Windows.
2. Keep discovery enabled.
3. Open Localink on Android.
4. Scan the Windows QR or pair manually with the token.
5. Wait for the direct local connection.
6. Start chat or transfer files.

### Android share flow

1. Open a file, image, or supported item in Android.
2. Tap `Share`.
3. Choose `Localink`.
4. Localink opens the send flow.
5. Scan the Windows QR if a connection is not already active.
6. Send the selected file(s).

## Transport Model

Primary transport:
- local Wi-Fi
- hotspot
- same LAN

Fallback transport:
- Bluetooth on Windows for slower message-first scenarios

Important note:
- LAN / hotspot is the recommended and release-critical path.
- Bluetooth should be treated as fallback, not the main file-transfer path.

## Project Structure

```text
Localink.Android/
  app/
    src/
      main/
        java/com/localbridge/android/
          core/
          features/
          models/
          repositories/
          services/
          ui/
  gradle/
  gradlew
  gradlew.bat
  settings.gradle.kts
  build.gradle.kts
```

## Build Requirements

- Android Studio
- Android SDK matching the project
- Java 17
- Gradle through the wrapper or Android Studio
- physical Android device for realistic LAN testing

## Local Build

Debug build:

```powershell
gradlew.bat assembleDebug
```

Release build:

```powershell
gradlew.bat assembleRelease
```

Bundle build:

```powershell
gradlew.bat bundleRelease
```

## Release Notes

Release builds are configured to be harder to inspect casually than debug
builds:
- code shrinking is enabled for release
- resource shrinking is enabled for release
- signing is loaded only from private local signing files when available

This helps with published binaries, but it does **not** make a public GitHub
repository private or unreadable.

## Very Important About GitHub Publishing

If you publish source code to a **public** GitHub repository, other people can
still read and copy that source code.

What has been done in this repository to reduce accidental leakage:
- local build folders are ignored
- IDE folders are ignored
- signing files are ignored
- generated APK/AAB files are ignored
- release logs are ignored

What still remains true:
- public source code can always be viewed
- true protection requires either a private repository or publishing binaries
  without publishing the full private source

## Signing And Security

Private signing files must never be committed:
- `keystore.properties`
- `*.jks`
- `*.keystore`

If you change the signing key later, users of an older signed version may not
be able to update in place.

## Permissions And Practical Notes

The app may request permissions relevant to:
- local network discovery
- Bluetooth, depending on Android version and testing path
- camera for QR scanning
- file access / share flows where needed

## Testing Checklist

Before publishing a release, verify at minimum:
- the app installs correctly
- QR scanning opens and reads the Windows QR
- direct local connection succeeds over LAN / hotspot
- text chat works both directions
- image transfer works both directions
- share-to-Localink flow opens correctly from Android apps
- transfer history and previews render correctly

## License And Usage

This repository is source-available for publishing control by its owner.
No public open-source permission is granted by default unless the owner adds a
separate permissive license explicitly.
