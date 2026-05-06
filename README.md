# Localink

Localink is an offline Windows-to-Android bridge designed for local-only use.

It connects:
- a Windows PC running the desktop host
- an Android phone running the companion app

It is built around:
- local discovery
- pairing
- QR-assisted connection
- chat
- local file transfer

## Current Release

- Version: `1.1.1`
- Protocol version: `1.1`
- Windows desktop host included
- Android companion included

## Main Features

- offline discovery on hotspot / Wi-Fi / LAN
- pairing by short token
- QR pairing from Android to Windows
- automatic direct local connection attempt after QR scan
- trusted device persistence
- text chat
- image and small file transfer over LAN
- transfer previews
- local logs and diagnostics
- Android share-to-Localink flow
- Windows installer-based distribution

## Project Structure

```text
Localink.sln
src/
  Localink.Core/
  Localink.Desktop/
android/
  Localink.Android/
tests/
  Localink.Core.Tests/
installer/
tools/
```

## Important Parts

### `src/Localink.Core`

Shared core library used by the rest of the project.
It contains protocol and shared models, discovery payloads, validation rules,
security/trust models, and shared constants.

### `src/Localink.Desktop`

Windows desktop host application.
It shows the pairing code and QR, manages discovery, hosts chat/transfers, and
provides settings and logs.

### `android/Localink.Android`

Android companion app.
It supports discovery, pairing, QR scanning, direct local connection after QR
scan, chat, LAN file transfer, transfer history, and Android share-target flow.

### `tests/Localink.Core.Tests`

Shared tests for the protocol/core layer.

## Important Recent Updates

The current repository state includes the recent updates completed during the
latest work cycle:

- QR pairing support added to the Windows desktop UI
- QR scan entry integrated into the Android app
- Android now attempts direct local connection after QR scan
- Android share flow now opens Localink from file-manager/gallery sharing
- Windows desktop layout was adjusted to keep the QR visible without breaking
  the existing interface
- QR payload was compacted so Android can scan from a more practical distance
- Android QR scanner now uses ML Kit auto-zoom for far-screen capture
- manual disconnect no longer triggers an immediate unwanted reconnect loop
- Android settings navigation now returns cleanly to the home screen
- release version moved to `v1.1.1`
- Windows installer and Android release packaging were refreshed

## Recommended Usage Flow

1. Launch Localink on Windows.
2. Keep local discovery enabled.
3. Open Localink on Android.
4. Pair using QR or manual pairing token.
5. Wait for connection.
6. Start chat or send files.

### Android share flow

1. Open a file or image on Android.
2. Tap `Share`.
3. Choose `Localink`.
4. Scan the Windows QR if needed.
5. Send the selected file(s).

## Transport Model

Primary transport:
- local hotspot
- same Wi-Fi
- same LAN

Fallback transport:
- Windows Bluetooth fallback for slower message-first scenarios

Important note:
- LAN / hotspot is the recommended production path.
- Bluetooth remains fallback, not the main transfer path.

## Build Notes

Windows build:

```powershell
dotnet build .\Localink.sln
```

Windows run:

```powershell
dotnet run --project .\src\Localink.Desktop\Localink.Desktop.csproj
```

Android debug build:

```powershell
cd .\android\Localink.Android
gradlew.bat assembleDebug
```

Android release build:

```powershell
cd .\android\Localink.Android
gradlew.bat assembleRelease
gradlew.bat bundleRelease
```

## Repository Cleaning Rules

This repository has been prepared as a cleaner source repository:
- generated build outputs should not be committed
- packaged release artifacts should not be committed
- local signing files should not be committed
- local IDE/cache folders should not be committed

## Very Important Source Protection Reality

No public GitHub repository can make source code unreadable while still
publishing that source publicly.

What has been done here:
- build outputs removed
- release artifacts removed from the source repo view
- signing files excluded
- source folders cleaned
- closed rights notices added

What still remains true:
- if the repository is public, people can read and copy the source

If stronger protection is needed, the practical approach is:
- keep the full repo private
- publish binaries publicly
- or publish only selected source parts

## License And Rights

No open-source permission is granted by default unless the owner explicitly
changes the license.
