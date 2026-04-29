# Localink Protocol Specification

This document defines the shared wire protocol used by the Windows desktop app in Phase 1 and the future Android client in Phase 2.

Status: active draft for implementation
Protocol version: `1.1`
Encoding: `UTF-8`
Metadata format: `JSON`
Chunk transport: `multipart/form-data` with raw binary bytes

## Goals

- Work fully offline on the same hotspot or LAN
- Keep the protocol simple enough for Windows and Android to implement consistently
- Separate transport metadata from payload data
- Avoid platform-specific serialization quirks
- Support discovery, pairing, chat, and chunked file transfer

## Transport Summary

- Discovery: `UDP broadcast` on port `45871`
- Connection and chat control: `HTTP/JSON` on port `45870`
- File chunks: `HTTP multipart/form-data` on port `45870`
- Bluetooth fallback: `RFCOMM` using the same protocol envelope and DTOs over a framed stream
- All non-binary packets use the same JSON envelope format
- Auto mode prefers hotspot/LAN first. Bluetooth is a slower fallback aimed mainly at text messages.

## Envelope Format

All JSON packets use the same top-level envelope.

```json
{
  "meta": {
    "version": "1.1",
    "packetType": "chat.text.message",
    "messageId": "8a6ccf420af9448cb38ae96b8204d7fd",
    "sentAtUtc": "2026-04-08T11:32:45.5123456+00:00",
    "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
    "senderDeviceId": "windows-pc-a",
    "receiverDeviceId": "android-phone-b",
    "correlationId": "8a6ccf420af9448cb38ae96b8204d7fd"
  },
  "payload": {},
  "error": null
}
```

### `meta` fields

- `version`: protocol version string. Phase 1 uses `1.1`.
- `packetType`: logical packet name such as `discovery.reply` or `transfer.prepare.request`.
- `messageId`: unique id for this envelope.
- `sentAtUtc`: sender timestamp in ISO-8601 with offset.
- `sessionId`: optional logical session id after handshake.
- `senderDeviceId`: optional sender device id.
- `receiverDeviceId`: optional intended receiver device id.
- `correlationId`: optional id linking a response to an earlier request.

### `error` fields

- `code`: machine-readable error code.
- `message`: human-readable summary.
- `isRetryable`: whether the client may retry.
- `details`: optional free-form detail text.

Responses may include both `payload` and `error`. In practice, LocalBridge uses:

- success response: `payload` present, `error` omitted
- failure response: `payload` present with failure state, `error` present

## Versioning Rules

- Only `meta.version` controls protocol compatibility.
- Payload DTOs do not carry duplicate version fields.
- A receiver must reject an envelope when `meta.version` is unsupported.
- Phase 1 currently treats only exact version `1.1` as compatible.
- Future changes should prefer additive fields over breaking renames.

## Packet Types

### Discovery

Transport:
- `UDP` broadcast or directed reply
- destination port `45871`
- body is a single JSON envelope

Valid `packetType` values:
- `discovery.probe`
- `discovery.reply`
- `discovery.announcement`

Payload schema:

```json
{
  "deviceId": "windows-pc-a",
  "deviceName": "Windows-PC",
  "platform": "Windows",
  "localIp": "192.168.137.1",
  "apiPort": 45870,
  "appVersion": "1.0.0",
  "supportedModes": ["local-lan", "bluetooth-fallback"],
  "pairingRequired": true,
  "sentAtUtc": "2026-04-08T11:30:10.0000000+00:00"
}
```

Required discovery fields:
- `deviceId`
- `deviceName`
- `platform`
- `localIp`
- `apiPort`
- `appVersion`
- `supportedModes`

Behavior:
- `discovery.announcement`: periodic presence broadcast
- `discovery.probe`: explicit scan request
- `discovery.reply`: direct answer to a probe

### Handshake

Transport:
- `POST /api/connection/handshake`
- body is a JSON envelope

Request packet type:
- `connection.handshake.request`

Request payload:

```json
{
  "deviceId": "android-phone-b",
  "deviceName": "Pixel 8",
  "platform": "Android",
  "appVersion": "1.0.0",
  "pairingToken": "641289",
  "supportedModes": ["local-lan", "bluetooth-fallback"]
}
```

Response packet type:
- `connection.handshake.response`

Success payload:

```json
{
  "accepted": true,
  "sessionState": "paired",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "failureReason": null,
  "serverDeviceId": "windows-pc-a",
  "serverDeviceName": "Windows-PC",
  "serverPlatform": "Windows",
  "serverAppVersion": "1.0.0",
  "supportedModes": ["local-lan", "bluetooth-fallback"],
  "issuedAtUtc": "2026-04-08T11:32:10.0000000+00:00"
}
```

Failure payload keeps the same shape with:
- `accepted: false`
- `sessionState: "waiting_for_pairing"` or `failed`
- `failureReason` set

Important handshake rules:
- `pairingToken` is the short trust code shown by Windows
- the responder rejects self-connections
- the responder may reject unknown or empty pairing tokens

### Heartbeat

Transport:
- `POST /api/connection/heartbeat`

Request packet type:
- `connection.heartbeat.request`

Response packet type:
- `connection.heartbeat.response`

Purpose:
- keep session alive
- detect dropped peers
- confirm connection state before transfer

### Disconnect

Transport:
- `POST /api/connection/disconnect`

Request packet type:
- `connection.disconnect.request`

Response packet type:
- `connection.disconnect.response`

## Bluetooth Fallback Transport

Windows now supports a Bluetooth fallback transport using classic RFCOMM. The logical packet types, DTOs, `meta.version`, `messageId`, `sessionId`, and response correlation rules stay exactly the same as LAN mode.

Bluetooth framing rules:
- underlying transport: RFCOMM stream
- frame header byte `1`: JSON-only envelope
- frame header byte `2`: JSON envelope plus raw binary payload
- after the frame kind, metadata length is a 4-byte big-endian integer
- metadata bytes are UTF-8 JSON
- when binary is present, binary length is another 4-byte big-endian integer followed by raw bytes

Current Windows behavior:
- discovery over Bluetooth uses nearby device enumeration, then identity resolves during handshake
- handshake, heartbeat, disconnect, and text chat are supported over Bluetooth
- file transfer packets are currently rejected with `transfer_service_unavailable`
- UI should clearly recommend hotspot/LAN for normal transfers because Bluetooth is slower and less reliable on Windows desktop stacks

## Text Messages

Transport:
- `POST /api/chat/messages`

Request packet type:
- `chat.text.message`

Message payload:

```json
{
  "id": "msg-20260408-001",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "senderId": "windows-pc-a",
  "senderName": "Windows-PC",
  "receiverId": "android-phone-b",
  "text": "Hello from LocalBridge",
  "timestampUtc": "2026-04-08T11:35:00.0000000+00:00"
}
```

Response packet type:
- `chat.text.receipt`

Receipt payload:

```json
{
  "accepted": true,
  "messageId": "msg-20260408-001",
  "status": "delivered",
  "failureReason": null,
  "receiverDeviceId": "android-phone-b",
  "receiverDeviceName": "Pixel 8",
  "receivedAtUtc": "2026-04-08T11:35:00.2300000+00:00"
}
```

Allowed message statuses:
- `sending`
- `sent`
- `delivered`
- `failed`

## File Transfer

Phase 1 target types:
- images
- pdf files
- txt files
- small generic files
- light video files

Current Windows limits:
- default chunk size: `65536` bytes
- current max file size guard: `67108864` bytes (`64 MiB`)

### Transfer Start

Transport:
- `POST /api/transfers/prepare`

Request packet type:
- `transfer.prepare.request`

Request payload:

```json
{
  "transferId": "tr-20260408-001",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "senderId": "windows-pc-a",
  "senderName": "Windows-PC",
  "receiverId": "android-phone-b",
  "fileName": "photo.jpg",
  "fileSize": 182344,
  "mimeType": "image/jpeg",
  "kind": "image",
  "fileCreatedAtUtc": "2026-04-08T11:20:00.0000000+00:00",
  "chunkSize": 65536,
  "totalChunks": 3,
  "requestedAtUtc": "2026-04-08T11:36:00.0000000+00:00"
}
```

Response packet type:
- `transfer.prepare.response`

Response payload:

```json
{
  "accepted": true,
  "transferId": "tr-20260408-001",
  "status": "receiving",
  "failureReason": null,
  "nextExpectedChunkIndex": 0,
  "receivedBytes": 0,
  "receiverDeviceId": "android-phone-b",
  "receiverDeviceName": "Pixel 8",
  "suggestedFilePath": "C:\\\\Users\\\\User\\\\Documents\\\\Localink\\\\photo.jpg",
  "respondedAtUtc": "2026-04-08T11:36:00.1200000+00:00"
}
```

Notes:
- `suggestedFilePath` is advisory and platform-specific
- Android clients should ignore it or map it to their own storage model

### Transfer Chunk

Transport:
- `POST /api/transfers/chunk`
- content type `multipart/form-data`

Multipart parts:
- `metadata`: JSON envelope with packet type `transfer.chunk.request`
- `chunk`: raw binary bytes for the chunk

Metadata payload:

```json
{
  "transferId": "tr-20260408-001",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "senderId": "windows-pc-a",
  "chunkIndex": 0,
  "chunkOffset": 0,
  "chunkLength": 65536
}
```

Chunk rules:
- `chunkIndex` is zero-based
- `chunkOffset` is the absolute byte offset in the full file
- `chunkLength` is the exact binary length carried in the `chunk` part
- the binary part is raw bytes, not base64
- chunks are sent in ascending order
- the receiver currently expects contiguous ordered chunks
- on conflict, the receiver returns `nextExpectedChunkIndex` and `receivedBytes`

Response packet type:
- `transfer.chunk.response`

Response payload:

```json
{
  "accepted": true,
  "transferId": "tr-20260408-001",
  "chunkIndex": 0,
  "status": "receiving",
  "failureReason": null,
  "nextExpectedChunkIndex": 1,
  "receivedBytes": 65536,
  "respondedAtUtc": "2026-04-08T11:36:01.0150000+00:00"
}
```

### Transfer Completion

Transport:
- `POST /api/transfers/complete`

Request packet type:
- `transfer.complete.request`

Request payload:

```json
{
  "transferId": "tr-20260408-001",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "senderId": "windows-pc-a",
  "totalChunks": 3,
  "totalBytes": 182344,
  "sentAtUtc": "2026-04-08T11:36:03.0000000+00:00"
}
```

Response packet type:
- `transfer.complete.response`

Response payload:

```json
{
  "accepted": true,
  "transferId": "tr-20260408-001",
  "status": "completed",
  "failureReason": null,
  "savedFilePath": "C:\\\\Users\\\\User\\\\Documents\\\\Localink\\\\photo.jpg",
  "completedAtUtc": "2026-04-08T11:36:03.2500000+00:00"
}
```

### Transfer Cancel

Transport:
- `POST /api/transfers/cancel`

Request packet type:
- `transfer.cancel.request`

Request payload:

```json
{
  "transferId": "tr-20260408-001",
  "sessionId": "b7cc51fd35b24742b62e40df72e2b6db",
  "senderId": "windows-pc-a",
  "reason": "sender_canceled",
  "sentAtUtc": "2026-04-08T11:36:02.5000000+00:00"
}
```

Response packet type:
- `transfer.cancel.response`

Response payload:

```json
{
  "accepted": true,
  "transferId": "tr-20260408-001",
  "status": "canceled",
  "failureReason": null,
  "canceledAtUtc": "2026-04-08T11:36:02.6200000+00:00"
}
```

### Transfer State Values

Allowed transfer states:
- `queued`
- `preparing`
- `sending`
- `receiving`
- `paused`
- `completed`
- `failed`
- `canceled`

## Error Responses

Error responses still use the normal envelope and packet type of the endpoint response.

Example:

```json
{
  "meta": {
    "version": "1.1",
    "packetType": "connection.handshake.response",
    "messageId": "7f8fe2bf7bce4a9f9d82fddf25d43429",
    "sentAtUtc": "2026-04-08T11:33:00.0000000+00:00",
    "receiverDeviceId": "android-phone-b",
    "correlationId": "db34a67c09b94baf9328d44bc0a5792a"
  },
  "payload": {
    "accepted": false,
    "sessionState": "waiting_for_pairing",
    "sessionId": null,
    "failureReason": "invalid_pairing_token",
    "serverDeviceId": "windows-pc-a",
    "serverDeviceName": "Windows-PC",
    "serverPlatform": "Windows",
    "serverAppVersion": "1.0.0",
    "supportedModes": ["wifi_lan"],
    "issuedAtUtc": "2026-04-08T11:33:00.0000000+00:00"
  },
  "error": {
    "code": "invalid_pairing_token",
    "message": "The supplied pairing token is not valid on this host.",
    "isRetryable": false
  }
}
```

Current error codes:
- `invalid_request`
- `protocol_mismatch`
- `session_not_found`
- `self_connection_not_allowed`
- `pairing_token_required`
- `invalid_pairing_token`
- `not_connected`
- `empty_message`
- `wrong_receiver`
- `transfer_service_unavailable`
- `invalid_transfer_prepare`
- `invalid_transfer_chunk`
- `invalid_transfer_complete`
- `invalid_transfer_cancel`

## Android Compatibility Notes

- Use UTF-8 JSON with `camelCase` field names.
- Preserve exact `packetType` strings. They are contract values.
- Use ISO-8601 timestamps with offsets, not locale-specific time strings.
- Treat `messageId`, `sessionId`, `deviceId`, and `transferId` as opaque strings.
- Do not depend on Windows file paths. `suggestedFilePath` and `savedFilePath` are informational.
- For chunk uploads, use multipart with:
  - `metadata` part as `application/json`
  - `chunk` part as `application/octet-stream`
- Do not base64-encode file chunks.
- Keep chunk order strict unless the protocol is explicitly upgraded later.
- Expect the receiver to reject unknown `packetType` values.
- Expect strict version checks in Phase 1.
- For Bluetooth fallback, reuse the same packet DTOs and envelope metadata. Only the byte framing changes.
- A Kotlin client can implement this cleanly with `OkHttp` or `Ktor` plus `kotlinx.serialization`, Moshi, or Jackson.

## Decisions and Refactor Notes

The Windows code was refactored to make the protocol layer cleaner and more reusable:

- protocol version now lives only in `meta.version`
- packet identity now lives only in `meta.packetType`
- discovery now uses the same envelope contract as HTTP packets
- chunk metadata moved from ad hoc request headers into the formal JSON envelope
- transfer chunk binary data remains raw bytes in multipart so Android can stream efficiently
- inbound handlers now validate both protocol version and expected packet type
- outbound Windows calls now validate response envelopes before trusting payloads

These changes reduce ambiguity, remove duplicated version fields, and give Android a single contract to follow.
