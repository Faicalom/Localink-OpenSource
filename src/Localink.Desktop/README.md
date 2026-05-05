# Localink Desktop

This repository folder contains the Windows desktop host for Localink.

Localink Desktop is the Windows side of an offline bridge between:
- a Windows PC
- an Android phone

It is designed for local-only operation over:
- Wi-Fi
- hotspot
- same LAN

It also includes a slower Bluetooth fallback path for selected scenarios.

## Current Release

- Product: `Localink`
- Desktop version: `1.1.0`
- Target framework: `net10.0-windows10.0.19041.0`
- UI stack: `WPF`

## What This Desktop App Does

The Windows desktop app acts as the host node in the Localink flow.

It is responsible for:
- showing the pairing code
- showing the pairing QR
- local device discovery
- accepting Android pairing
- maintaining trusted device state
- hosting chat
- hosting transfer sessions
- rendering transfer previews
- exposing logs and diagnostics

## Important Recent Updates

This version includes the main updates completed during the latest development cycle:

- QR pairing support added to Windows
- QR rendered directly in the main desktop UI
- desktop layout adjusted so QR does not break the existing interface
- settings and tab layout fixed after QR integration
- Android companion can now scan the desktop QR and attempt direct local connection automatically
- release packaging updated for installer distribution
- project version updated to `v1.1.0`

## Main Desktop Experience

The app presents:
- pairing code
- pairing QR
- current lifecycle state
- current transport mode
- devices list
- chat tab
- transfers tab
- settings tab
- logs tab

## Transport Model

Primary path:
- local hotspot / Wi-Fi / LAN

Fallback path:
- Windows Bluetooth fallback

Important note:
- LAN / hotspot is the recommended production path.
- Bluetooth is slower and should be treated as fallback, especially for normal file-transfer expectations.

## Technical Stack

- .NET
- WPF
- ASP.NET Core components hosted in-process where required by the app architecture
- QRCoder for QR generation
- InTheHand.Net.Bluetooth for Bluetooth support

## Folder Structure

```text
Localink.Desktop/
  Assets/
  Core/
  Features/
  Infrastructure/
  Models/
  Repositories/
  Services/
  Theme/
  Ui/
  ViewModels/
  App.xaml
  App.xaml.cs
  MainWindow.xaml
  MainWindow.xaml.cs
  Localink.Desktop.csproj
```

## Build

From the solution root:

```powershell
dotnet build .\Localink.sln
```

Run directly:

```powershell
dotnet run --project .\src\Localink.Desktop\Localink.Desktop.csproj
```

## Packaging

The desktop app is intended to be packaged as a Windows installer.

Typical publish direction:
- self-contained Windows build
- packaged setup executable for distribution

## Practical Usage Flow

1. Launch Localink on Windows.
2. Keep discovery enabled.
3. Open Localink on Android.
4. Pair using QR or the pairing token.
5. Wait for connection.
6. Start chat or send files.

## Logs And Diagnostics

The Windows side keeps local logs for:
- discovery
- pairing
- reconnect
- session
- transfer

These logs are useful for release testing and bug reports.

## Security And Publishing Notes

This folder has been cleaned for source publishing:
- build outputs should not be committed
- local caches should not be committed
- generated binaries should not be committed from this source folder

Realistic protection note:
- if you publish source code to a public GitHub repository, people can still read and copy that source
- no source repository can be made public and simultaneously unreadable

What has been done here to help:
- build artifacts are ignored
- a closed rights notice can be included
- source is kept clean from generated output

What still remains true:
- real secrecy requires a private repository or binary-only release

## Recommended Publishing Approach

If you want the best practical protection:
- publish binaries publicly
- keep the full working repository private
- or publish only selected source parts publicly

## License And Rights

No open-source permission is granted by default in this folder unless the owner explicitly changes the license.
