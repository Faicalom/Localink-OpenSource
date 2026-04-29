# Localink Android Setup

## Requirements

- Android Studio
- Android SDK matching the project configuration
- Java and Gradle through Android Studio
- a physical Android device on the same hotspot/LAN as the Windows host

## Open The Project

- open `android/LocalBridge.Android` in Android Studio
- let Gradle sync finish
- install the app on a physical device

## Permissions

Grant the permissions requested by the app when testing:

- nearby / network discovery permissions as prompted by Android version
- Bluetooth permissions if you later test Bluetooth-related groundwork
- file picker access for outbound transfers

## Practical Flow

1. Open the app and go to `Devices`.
2. Refresh discovery while the phone is on the same hotspot/LAN as Windows.
3. Select the Windows peer.
4. Enter the pairing code shown on Windows.
5. Test chat first.
6. Test images and small file transfer next.
7. Open `Settings` and either keep the private fallback subfolder or pick an external SAF folder for received files.

## Android Notes

- received files can now be saved either to a persisted SAF-picked external folder or to the app-private fallback subfolder
- if the picked SAF folder is unavailable later, the app falls back automatically to app-private storage
- trusted devices are persisted locally
- settings include preferred mode, device alias, SAF receive-folder selection, private fallback subfolder name, resolved save-path guidance, and recent logs
- transfer cards show image previews when the underlying file is an image and a local/content URI is available
- chat now shows inline attachment/media awareness bubbles based on local transfer history without changing the text protocol
- this session could not compile the Android app locally because the current environment did not include Java or Gradle

## Build Notes

- recommended path: Android Studio `Build > Build APK(s)` or `Build > Generate Signed Bundle / APK`
- command-line builds are also possible inside Android Studio's Gradle environment, for example `assembleDebug`
- release signing config is not committed in this repo; supply signing locally in Android Studio or a private Gradle setup
