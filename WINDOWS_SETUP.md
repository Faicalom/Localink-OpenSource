# Localink Windows Setup

## Requirements

- Windows 10 or Windows 11
- .NET 10 SDK
- local Wi-Fi adapter or hotspot support
- optional Bluetooth adapter for fallback testing

## Build

```powershell
dotnet build .\Localink.sln
```

## Run

```powershell
dotnet run --project .\src\LocalBridge.Desktop\LocalBridge.Desktop.csproj
```

## Diagnostics

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Invoke-LocalBridgeDiagnostics.ps1
```

- marker-focused log filtering:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Get-LocalBridgeMarkerLog.ps1 -Marker TRANSFER
```

- desktop logs are written to `%LocalAppData%\LocalBridge\Desktop\desktop.log`
- the in-app Logs panel now mirrors the same runtime events and shows the active log-file path
- structured markers currently include `[DISCOVERY]`, `[PAIRING]`, `[RECONNECT]`, `[SESSION]`, and `[TRANSFER]`

## What To Check Before Testing

- keep the Windows firewall open for the LocalBridge process if prompted
- confirm the Windows machine and phone are on the same hotspot/LAN
- for Bluetooth fallback, pair the devices in the operating system first if RFCOMM socket setup fails

## Practical Flow

1. Launch the Windows app.
2. Confirm the pairing code shown in the connection panel.
3. Keep discovery enabled.
4. On Android, discover the Windows device and enter the code.
5. Test chat first, then LAN file transfer.
6. Confirm image transfers show a preview tile in the Transfers panel after send/receive completes.
7. Confirm chat now shows inline attachment/media awareness bubbles based on local transfer history.

## Windows Notes

- hotspot/LAN is the primary path and gives the best transfer experience
- Bluetooth is slower and intended mainly for text messaging fallback
- trusted devices are stored under `%LocalAppData%\LocalBridge\Desktop`
- received files default to the configured download folder shown in the app
- transfer previews are rendered from local files only; generic files still use safe fallback cards

## Publish Notes

- release publish example:

```powershell
dotnet publish .\src\LocalBridge.Desktop\LocalBridge.Desktop.csproj -c Release -r win-x64 --self-contained false
```

- output lands under `src\LocalBridge.Desktop\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish`
- Windows Bluetooth RFCOMM capability still depends on the target machine adapter/driver stack, so LAN remains the release-critical path to validate first
