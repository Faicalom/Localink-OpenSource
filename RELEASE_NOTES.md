# Localink Release Notes

## Current Project Status

Localink is in a practical pre-release testing state for Windows + Android offline communication over local hotspot/LAN.

## Release Candidate Status

- Windows is at release-candidate validation quality for the primary LAN path
- Android is feature-complete enough for focused release-candidate field testing, but still needs Android Studio build confirmation and real-device SAF validation
- Bluetooth remains intentionally non-release-critical and text-first

## Supported Features

- Windows desktop host with local discovery, pairing, trusted devices, chat, and LAN transfers
- Android companion with compatible discovery, pairing, trusted devices, chat, and LAN transfers
- shared JSON protocol documented in `LOCALINK_PROTOCOL.md`
- transfer history and local logs on both platforms
- inline attachment/media awareness in chat using local transfer history
- image previews in transfer cards where the local file or content URI is available
- Bluetooth fallback on Windows for discovery, pairing, reconnect, and message-first text chat

## Important Limitations

- LAN is the primary and recommended transport for all normal transfers
- Bluetooth file transfer is still intentionally disabled
- Android receive location now supports a persisted SAF-picked external folder with automatic app-private fallback
- this repository state was verified with `.NET` build/test on Windows, but Android still requires Android Studio/Gradle validation on a real device

## Recommended Practical Test Order

1. Windows build + startup smoke test
2. Android install on a real phone
3. LAN discovery and first-time pairing
4. trusted-device persistence
5. text chat both directions
6. image/file transfer both directions
7. reconnect behavior after a temporary network drop
8. Bluetooth fallback text-only validation

## Next Steps

- perform end-to-end real-device validation and capture logs with the updated checklist
- add richer media presentation inside chat only if the current lightweight timeline proves too limited in field testing
- improve SAF UX further only if more provider-specific edge cases appear during device validation
- package Windows and Android release artifacts for easier field testing
