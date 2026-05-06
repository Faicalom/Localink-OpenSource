# Localink Legacy Snapshot

Localink is an offline Windows-to-Android communication and local transfer project.
This repository is the older pre-update snapshot, kept as a cleaned source archive
for publishing, study, and historical reference.

It focuses on:
- local-only discovery
- pairing by short token
- trusted device flow
- text chat
- file transfer over hotspot / Wi-Fi / LAN
- Bluetooth fallback for lighter scenarios

## Snapshot Scope

This repository intentionally preserves the older project line and is not the
newer QR-expanded release tree. It is meant to stay stable, lightweight, and
clean for source publishing.

## Main Features

- offline chat between Windows and Android
- local file sharing over hotspot / Wi-Fi / LAN
- Bluetooth fallback for chat and smaller transfers
- first-time pairing with a short code
- trusted peer persistence
- transfer status and local history
- local-only design without cloud dependency

## Repository Structure

```text
Localink.sln
README.md
LICENSE.txt
.gitignore
src/
  Localink.Core/
  Localink.Desktop/
  README.md
android/
  Localink.Android/
  README.md
tests/
  Localink.Core.Tests/
installer/
tools/
```

## Project Parts

### `src`

Contains the shared core library and the Windows desktop host.

### `android`

Contains the Android companion application.

### `tests`

Contains core/protocol validation tests.

### `installer`

Contains the Windows installer script and packaging helper.

### `tools`

Contains local diagnostics helper scripts.

## Build Basics

### Windows desktop

- open `Localink.sln`
- build `src/Localink.Desktop`

### Android

- open `android/Localink.Android` in Android Studio
- sync Gradle
- build on a real Android device for practical validation

## Transport Model

- primary path: hotspot / Wi-Fi / LAN
- fallback path: Bluetooth

LAN remains the preferred path for normal day-to-day use.

## Publishing Notes

This cleaned snapshot removes duplicated documentation files, local build
artifacts, and machine-specific files so it is easier to publish safely.

## License

See [LICENSE.txt](LICENSE.txt).
