# Android Legacy Tree

This folder contains the Android side of the older Localink project line.

## Included Project

- `LocalBridge.Android`

## Purpose

This subtree is kept as a cleaned legacy snapshot for publishing and reference.
It is not the newer update branch.

## What The Android App Does

- discovers the Windows host on local networks
- supports first-time pairing
- keeps trusted peers
- sends text chat packets
- performs local file transfer
- uses Bluetooth only as a fallback path

## Open In Android Studio

- open `android/LocalBridge.Android`
- sync Gradle
- run on a physical device for realistic validation

## Clean Publishing Notes

This subtree should contain source only:
- no local Gradle cache
- no generated build outputs
- no signing secrets
- no machine-specific files
