# Localink Desktop

Windows WPF host for LocalBridge.

## Responsibilities

- local hotspot/LAN discovery and hosting
- pairing, trust, session heartbeat, and reconnect
- chat and LAN file transfer
- Bluetooth fallback for discovery and text-first sessions

## Run

```powershell
dotnet run --project .\src\LocalBridge.Desktop\LocalBridge.Desktop.csproj
```

## Notes

- use Local Wi-Fi / Hotspot for normal transfers
- Bluetooth fallback is slower and intentionally limited in this build
- local state is stored under `%LocalAppData%\LocalBridge\Desktop`
