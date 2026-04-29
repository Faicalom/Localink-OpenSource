# Localink Android

Android companion app for the Localink Windows desktop host.

## Implemented Scope

- hotspot/LAN discovery compatible with Windows
- local handshake and reconnect flow
- local text chat using the shared Localink protocol
- inline attachment/media awareness in chat using local transfer history
- LAN image and small file transfer
- transfer thumbnails/previews for image files
- trusted device persistence
- settings, logs, SAF receive-folder selection, and app-private fallback control

## Main Folders

```text
app/src/main/java/com/localbridge/android/
  core/
  features/
  models/
  repositories/
  services/
  ui/
```

## Open In Android Studio

- open `android/LocalBridge.Android`
- sync Gradle
- run on a physical Android device for real LAN testing
- use Android Studio `Build > Build APK(s)` for debug artifacts
- use Android Studio `Generate Signed Bundle / APK` for release signing; signing config is intentionally not committed here

## Notes

- this session could not compile Android locally because the environment did not include Java or Gradle
- the implementation is aligned to `../../LOCALINK_PROTOCOL.md`
- hotspot/LAN is the primary recommended path for testing
- Android can now target a persisted SAF-picked external folder, while keeping app-private storage as the fallback path
