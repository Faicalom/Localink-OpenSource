# Localink

Localink is an offline local sharing and chat project for Windows and Android.

It is designed for direct device-to-device communication without cloud services, without internet dependency for local transfers, and without USB cable requirements.

## Main Features

- Offline text chat between Windows and Android
- Local file sharing over Wi-Fi / hotspot / LAN
- Bluetooth fallback for chat and small files
- Pairing code for first connection
- Trusted device flow
- Transfer progress, status, and local history
- Image previews and local-only transfer design

## Platforms

- Windows desktop app
- Android app

## Recommended Transport

- Primary: Local Wi-Fi / Hotspot
- Fallback: Bluetooth for chat and small files

## Downloads

After you publish GitHub Releases, replace `YOUR-USERNAME` with your GitHub username:

- Windows setup:
  `https://github.com/YOUR-USERNAME/Localink/releases/latest/download/Localink-Windows-Setup.exe`
- Android APK:
  `https://github.com/YOUR-USERNAME/Localink/releases/latest/download/Localink-Android-Release-v1.0.0.apk`

You can upload these release files from your prepared public folder:

- `Public-Releases/Windows/Localink-Windows-Setup.exe`
- `Public-Releases/Android/Localink-Android-Release-v1.0.0.apk`

## Repository Layout

```text
Localink.sln
README.md
LOCALINK_PROTOCOL.md
LOCALINK_ARCHITECTURE.md
WINDOWS_SETUP.md
ANDROID_SETUP.md
TEST_CHECKLIST.md
RELEASE_NOTES.md
src/
  LocalBridge.Core/
  LocalBridge.Desktop/
android/
  LocalBridge.Android/
tests/
  LocalBridge.Core.Tests/
tools/
installer/
```

## Build Notes

### Windows

- Open `Localink.sln`
- Build the desktop project
- The app branding is `Localink`

### Android

- Open `android/LocalBridge.Android` in Android Studio
- Sync Gradle
- Build and install on a real Android device

## Documentation

- [WINDOWS_SETUP.md](WINDOWS_SETUP.md)
- [ANDROID_SETUP.md](ANDROID_SETUP.md)
- [LOCALINK_PROTOCOL.md](LOCALINK_PROTOCOL.md)
- [LOCALINK_ARCHITECTURE.md](LOCALINK_ARCHITECTURE.md)
- [TEST_CHECKLIST.md](TEST_CHECKLIST.md)
- [RELEASE_NOTES.md](RELEASE_NOTES.md)

## Open Source Use

You can study, modify, adapt, and improve the codebase for your own workflows.

If you publish this repository publicly, it is a good idea to also add:

- a LICENSE file
- screenshots
- a Releases page with the Windows setup and Android APK
- a short website or landing page for downloads
