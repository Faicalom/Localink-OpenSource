# Localink Practical Test Checklist

## 1. .NET Build And Shared Tests

- [x] Run `dotnet build .\Localink.sln`
  Expected: the Windows solution builds with no blocking errors.
- [x] Run `dotnet test .\Localink.sln --no-build`
  Expected: shared protocol tests pass.
- [x] Start the Windows app once.
  Expected: the main window stays open, shows a pairing code, and the Logs panel begins updating.

## 2. Windows-Only LAN Host Validation

- [ ] Run `powershell -ExecutionPolicy Bypass -File .\tools\Invoke-LocalBridgeDiagnostics.ps1`
  Expected: the status endpoint returns `connection.status` JSON and recent desktop log lines.
- [ ] Run `powershell -ExecutionPolicy Bypass -File .\tools\Get-LocalBridgeMarkerLog.ps1 -Marker DISCOVERY`
  Expected: the marker-filtered output shows discovery lifecycle entries without unrelated noise.
- [ ] Confirm `%LocalAppData%\LocalBridge\Desktop\desktop.log` exists and grows while the app is running.
  Expected: markers such as `[DISCOVERY]`, `[PAIRING]`, `[RECONNECT]`, `[SESSION]`, and `[TRANSFER]` are present.

## 3. Windows <-> Android LAN Discovery

- [ ] Put the Windows PC and Android phone on the same hotspot/LAN with no internet required.
  Expected: discovery still works over local IP only.
- [ ] Start the Windows app first and keep discovery enabled.
  Expected: the Windows side advertises a reachable local endpoint and a visible pairing code.
- [ ] Open Android `Devices`, refresh once, and wait for one scan window to finish.
  Expected: the Windows peer appears with platform, version, transport info, and online state.
- [ ] Turn off the hotspot or leave the LAN long enough to exceed the stale timeout.
  Expected: the stale peer disappears and logs show `[DISCOVERY]` timeout cleanup.

## 4. First-Time Pairing

- [ ] On Windows, note the pairing code from the device/connection panel.
- [ ] On Android, pick the Windows peer and enter the exact same code.
  Expected: Android moves through `connecting` and `paired` to `connected`.
- [ ] Try once with a wrong code.
  Expected: the attempt fails cleanly, the UI shows a clear pairing problem, and logs include `[PAIRING]` failure details.
- [ ] Retry with the correct code.
  Expected: heartbeat validation succeeds and both sides show the connected peer name.

## 5. Trusted Device Persistence

- [ ] Mark the paired peer as trusted.
  Expected: the device appears in trusted-device lists.
- [ ] Restart the relevant app.
  Expected: the trusted state persists.
- [ ] Remove the trusted device from Settings or Devices.
  Expected: trust is removed immediately and remains removed after restart.

## 6. Text Chat

- [ ] Send a message from Windows to Android.
  Expected: Android shows the bubble, timestamp, and delivered state.
- [ ] Send a message from Android to Windows.
  Expected: Windows shows the bubble, timestamp, and delivered state.
- [ ] Force a temporary delivery failure by dropping the connection during send, then reconnect and retry.
  Expected: the message shows failed state first, then succeeds after retry.

## 7. Inline Attachment Awareness In Chat

- [ ] Send an image from Windows to Android over LAN.
  Expected: the Transfers screen completes, and the Chat screen shows an inline attachment/media bubble with a lightweight preview once the file exists locally.
- [ ] Send an image from Android to Windows over LAN.
  Expected: the Windows Chat panel shows an inline attachment bubble with a preview tile based on local transfer history.
- [ ] Send a non-image file such as PDF or TXT.
  Expected: chat shows a fallback attachment/file bubble with metadata instead of a broken preview.
- [ ] Confirm chat remains usable as text-first messaging.
  Expected: sending, retry, timestamps, and status indicators still behave as before.

## 8. Transfer History, Previews, And File Actions

- [ ] Send JPG/PNG both directions.
  Expected: Transfers screens show progress, speed, ETA, and then a thumbnail/preview tile after completion.
- [ ] Send PDF and TXT both directions.
  Expected: transfer completes and fallback file cards remain clear and readable.
- [ ] Open a received file from Windows.
  Expected: the OS launches the file normally.
- [ ] Open and share a received file from Android.
  Expected: file intents work from either app-private fallback storage or the picked SAF folder.
- [ ] Restart the app after completed transfers.
  Expected: transfer history persists and completed previews/attachment bubbles still resolve correctly where the local file/content URI remains available.

## 9. Android Save-Location Behavior

- [ ] In Android `Settings`, leave SAF unset and choose a private fallback subfolder name such as `field-test`.
  Expected: the active destination shows app-private storage and the fallback path updates immediately.
- [ ] Receive a file with no SAF folder selected.
  Expected: the file lands in the app-private fallback directory shown in Settings/Transfers.
- [ ] Pick an external folder via the SAF directory picker.
  Expected: the selected folder name is persisted and becomes the active receive destination in Settings and Transfers.
- [ ] Receive a file after selecting the SAF folder.
  Expected: the file is saved to the picked external folder when the document provider allows it.
- [ ] Remove or invalidate the SAF folder selection, then receive another file.
  Expected: LocalBridge falls back automatically to app-private storage and logs a `[STORAGE]` fallback reason instead of failing the whole transfer.

## 10. Reconnect After Connection Drop

- [ ] While Windows and Android are connected, temporarily break the LAN path.
  Expected: the active session drops, the UI shows disconnected/reconnect state, and logs include `[RECONNECT]`.
- [ ] Restore the LAN path within a few seconds.
  Expected: reconnect attempts resume automatically and the session becomes connected again without restarting the apps.
- [ ] Inspect marker logs after the drop.
  Expected: you can follow the sequence through `[SESSION]` failure, `[RECONNECT]` scheduling, retry attempts, and recovery.

## 11. Bluetooth Fallback Sanity Check

- [ ] Switch Windows preferred mode to Bluetooth fallback only after the LAN path is validated.
  Expected: the UI clearly warns that Bluetooth is slower and message-first.
- [ ] Pair devices at the OS level if Windows RFCOMM needs it.
  Expected: Bluetooth discovery/connection works when the adapter/driver stack supports it.
- [ ] Send a short text message over Bluetooth.
  Expected: message delivery works if RFCOMM is supported on the test machine.
- [ ] Attempt a file transfer over Bluetooth.
  Expected: the app rejects it clearly and recommends switching back to LAN.

## 12. Logs To Capture For Bug Reports

- [ ] Windows desktop log: `%LocalAppData%\LocalBridge\Desktop\desktop.log`
- [ ] Windows marker filter example:
  `powershell -ExecutionPolicy Bypass -File .\tools\Get-LocalBridgeMarkerLog.ps1 -Marker TRANSFER`
- [ ] Android in-app recent logs from `Settings`
- [ ] Connection status payload from `Invoke-LocalBridgeDiagnostics.ps1`
- [ ] Exact transport mode, pairing code step, peer names, and whether the failure happened during discovery, pairing, transfer prepare, transfer completion, preview rendering, SAF save, or reconnect
