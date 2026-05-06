# Source Legacy Tree

This folder contains the Windows and shared-core source code for the older
Localink project line.

## Included Projects

- `LocalBridge.Core`
- `LocalBridge.Desktop`

## Responsibilities

### `LocalBridge.Core`

Shared protocol, discovery, and security-related models used by the project.

### `LocalBridge.Desktop`

Windows WPF host that handles:
- local discovery
- pairing
- trusted devices
- chat
- local transfer
- Bluetooth fallback

## Notes

This subtree was cleaned for source publication:
- no generated build outputs
- no duplicated markdown clutter
- no local machine artifacts
