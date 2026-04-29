# Localink Phase 1 Architecture

## Goal

Phase 1 delivers a practical Windows desktop application that works offline over local hotspot/LAN and acts as the first stable node for a future Android client.

The canonical wire contract for the current implementation is documented in `LOCALINK_PROTOCOL.md`.

This phase focuses on:

- local device discovery without internet
- direct local connectivity
- two-way text chat
- image and small file transfer
- first-time pairing and trusted reconnection
- protocol stability for later Android support

Bluetooth is intentionally deferred to a later phase.

## Recommended Tech Stack

### Windows desktop

- `.NET 10`
- `WPF` for the desktop UI
- `ASP.NET Core Minimal API` hosted in-process inside the desktop app
- `UDP` for LAN discovery
- `System.Text.Json` for protocol serialization
- `HTTP JSON envelopes + multipart chunk uploads` for the current wire protocol
- local JSON persistence for identity and trusted devices

### Shared logic and protocol

- `LocalBridge.Core` class library for:
  - protocol constants
  - DTOs and envelopes
  - discovery packets
  - trust and identity models

### Future Android compatibility

The most practical compatibility strategy is protocol sharing, not UI sharing.

- Keep the wire protocol language-neutral: JSON over HTTP/WebSocket
- Keep discovery packets small and versioned
- Keep transfer endpoints simple and stateless
- Later Android client can be written in `Kotlin` using `OkHttp/Ktor` against the same protocol

Why not optimize for code sharing first:

- WPF is the most practical Windows-first desktop choice
- Native Android will likely be Kotlin, so direct binary code sharing is less valuable than a stable protocol contract
- protocol-first design reduces risk and keeps Phase 1 simpler

## Architectural Style

Use a desktop-hosted local hub architecture.

In Phase 1, the Windows app is both:

- the interactive desktop UI
- the local LAN server node that accepts trusted peers

This gives a single practical runtime with a clear split between UI, application state, and transport services.

## High-Level Component Model

### 1. Presentation layer

Responsible for:

- current node status
- pairing approval prompts
- chat timeline
- transfer progress
- device list
- logs

Suggested structure:

- `MainWindow`
- `MainViewModel`
- observable UI state models

### 2. Application layer

Responsible for orchestration:

- start and stop server services
- refresh dashboard state
- trigger send message and share file actions
- approve or reject pairing
- translate transport events into UI state

Key application services:

- `LocalBridgeServer`
- `LanDiscoveryService`
- `AppStateStore`

### 3. Domain and protocol layer

Responsible for stable shared models:

- protocol version
- envelope types
- text payloads
- file offer payloads
- transfer status payloads
- discovery packets
- trusted device records

This layer must stay framework-light so Android can implement the same contract later.

### 4. Transport layer

Responsible for communication over the LAN:

- UDP broadcast discovery
- HTTP status and pairing endpoints
- WebSocket chat session
- HTTP upload and download for files

### 5. Persistence layer

Responsible for local offline state:

- desktop identity
- trusted peers
- incoming file storage
- outgoing file offers

Phase 1 can use files and JSON.
No database is required yet.

## Internal Project Structure

Recommended structure for Phase 1:

- `src/LocalBridge.Core`
  - protocol models
  - discovery models
  - security models
  - network constants
- `src/LocalBridge.Desktop`
  - `Infrastructure`
  - `Models`
  - `Services`
  - `ViewModels`
  - WPF views

This keeps the Windows app concrete while isolating protocol decisions into a reusable core.

## Networking Approach

## Primary mode

Hotspot/LAN only.

Supported scenarios:

- Android joins the Windows hotspot
- Windows joins the Android hotspot
- both devices are on the same offline Wi-Fi LAN

Requirements:

- no internet routing required
- IPv4 local network support first
- direct peer-to-host communication over TCP/HTTP/WebSocket

## Connection model

Phase 1 should use a hub-style practical connection model:

- Windows desktop acts as the local host/server
- Android later acts as a client peer that discovers and connects to the desktop

Why this is practical:

- Windows remains online and visible while the desktop app is open
- pairing approvals are easy on a larger UI
- file storage and transfer control are easier on the desktop first
- Android can be added later without redesigning the protocol

This still supports two-way communication because once connected:

- both sides can send chat messages
- both sides can upload files to the host
- both sides can download offered files from the host

In a later phase, direct peer-to-peer symmetry can be expanded if needed.

## Discovery Approach

Use UDP broadcast on a fixed local port.

### Phase 1 discovery flow

1. Desktop periodically broadcasts an `announcement`
2. A client can actively broadcast a `probe`
3. Desktop replies with a `reply`
4. The reply contains:
   - device id
   - device name
   - API port
   - protocol version
   - pairing-required flag

### Why UDP broadcast

- works on normal local hotspot/LAN without internet
- lightweight and fast
- supported on both Windows and Android
- avoids mDNS complexity in the first phase

### Discovery packet design

Packet fields:

- `packetType`
- `deviceId`
- `deviceName`
- `apiPort`
- `protocolVersion`
- `pairingRequired`
- `sentAtUtc`

### Future compatibility note

If some Android hotspot combinations later limit broadcast behavior, add fallback discovery options in a later phase:

- manual IP connect
- mDNS
- cached last-known peer address

But Phase 1 should start with UDP broadcast because it is the simplest working offline option.

## Message and File Transfer Protocol

Use a hybrid protocol:

- `HTTP` for request/response endpoints and file transfer
- `WebSocket` for live two-way messaging and presence
- `JSON` envelopes for structured messages

## Protocol principles

- version every contract
- keep messages small and explicit
- separate chat events from bulk file bytes
- use authenticated headers after pairing

## Core endpoints

- `GET /api/status`
  Returns server identity, ports, addresses, and protocol version

- `POST /api/pairing/request`
  Requests trust using the desktop pairing code

- `GET /ws`
  Opens authenticated WebSocket session

- `PUT /api/transfers/upload`
  Uploads a file to the desktop

- `GET /api/transfers/download/{transferId}`
  Downloads a previously offered desktop file

## Envelope model

Every WebSocket message should use an envelope:

- `type`
- `messageId`
- `sentAtUtc`
- `payload`

Core message types:

- `text_message`
- `file_offer`
- `transfer_status`
- `peer_presence`
- `system`

## File flow

For files, avoid sending bytes through WebSocket.

Recommended flow:

1. sender creates file metadata
2. sender announces a `file_offer` over WebSocket when appropriate
3. actual bytes move over HTTP
4. transfer progress updates are reflected in local app state

Why:

- HTTP streaming is simpler for files
- WebSocket remains responsive for chat
- progress tracking is clearer
- Android implementation later is straightforward

## Pairing and Trust Flow

The first connection should require human confirmation on the desktop.

### First-time pairing

1. Desktop shows a short pairing code
2. Client discovers the desktop and reads `/api/status`
3. Client submits `deviceId`, `deviceName`, and `pairingCode`
4. Desktop shows a pending approval request
5. User approves or rejects on Windows
6. If approved, desktop returns a generated shared secret
7. Client stores the shared secret securely
8. Future requests authenticate with:
   - `X-LocalBridge-DeviceId`
   - `X-LocalBridge-SharedSecret`

### Trust model

Store on the desktop:

- trusted device id
- device name
- shared secret
- paired timestamp
- last seen timestamp

### Why this trust model

- simple enough for offline local use
- no external PKI or cloud identity
- easy to implement now
- compatible with future Android secure storage

### Phase 1 security boundary

This is local trust, not enterprise-grade zero-trust networking.

Good enough for Phase 1:

- explicit pairing approval
- shared-secret authentication
- only local LAN scope

Later phases can add:

- QR-based pairing
- stronger session tokens
- optional TLS over local network
- device revocation UI

## App States

Define app behavior around clear runtime states.

### Desktop application states

- `Starting`
  - load identity
  - load trusted devices
  - start HTTP/WebSocket host
  - start UDP discovery

- `Ready`
  - waiting for discovery, pairing, or active sessions

- `PairingPending`
  - one or more unapproved devices waiting for user action

- `Connected`
  - at least one trusted peer has an active WebSocket session

- `Transferring`
  - one or more active uploads/downloads are running

- `ShuttingDown`
  - stop discovery
  - close sockets
  - flush state

These states can overlap in UI terms, but they help structure services and logs.

### Peer lifecycle states

- `Discovered`
- `PairingRequested`
- `Trusted`
- `Connected`
- `Disconnected`
- `Revoked` later

### Transfer lifecycle states

- `Offered`
- `Receiving`
- `Sending`
- `Completed`
- `Failed`
- `Cancelled` later

## Core Services

Recommended service responsibilities:

### `LocalBridgeServer`

- own the host lifecycle
- expose HTTP endpoints
- manage WebSocket sessions
- coordinate transfers
- publish logs and snapshots

### `LanDiscoveryService`

- send broadcast announcements
- answer probes
- receive discovery packets

### `AppStateStore`

- load and save identity
- load and save trusted devices
- define incoming and outgoing storage locations

### `SessionManager` later optional extraction

In Phase 1 this can live inside `LocalBridgeServer`.
Later, extract it if session logic grows.

Responsibilities:

- active peer registry
- presence changes
- send fan-out
- disconnect cleanup

### `TransferManager` later optional extraction

In Phase 1 this can also stay inside `LocalBridgeServer`.
Later, extract it when resumable transfer or throttling is added.

Responsibilities:

- transfer metadata
- file offer registry
- streaming progress
- failure handling

## Logging and Error Handling

Phase 1 should log:

- server start and stop
- discovery send and receive
- pairing requests and decisions
- socket connect and disconnect
- message delivery failures
- file transfer progress and failures

Error handling principles:

- never crash the whole app because one peer disconnected
- isolate transfer errors from chat/session errors
- keep logs human-readable in the UI
- prefer retry only for transient network operations

## Phase 1 Roadmap

## Phase 1A: Desktop foundation

- create solution structure
- create `LocalBridge.Core`
- create WPF desktop shell
- add state storage and identity

Deliverable:

- desktop app launches and persists identity

## Phase 1B: Local host and discovery

- embed ASP.NET Core host
- expose status endpoint
- add UDP broadcast discovery
- display listening addresses in UI

Deliverable:

- desktop app is discoverable on local LAN

## Phase 1C: Pairing and trust

- add pairing code UI
- add pairing request endpoint
- add approval and rejection flow
- persist trusted peers

Deliverable:

- new devices can be explicitly trusted

## Phase 1D: Live messaging

- add authenticated WebSocket endpoint
- support text messages
- track peer presence
- show chat timeline

Deliverable:

- real-time two-way text chat on LAN

## Phase 1E: File transfer

- add upload endpoint
- add file offer model
- add download endpoint
- show transfer progress and completion

Deliverable:

- images and small files transfer successfully

## Phase 1F: Stability and packaging

- improve retry and timeout behavior
- add better validation and edge-case handling
- test on Windows hotspot and Android hotspot scenarios
- prepare packaging and install instructions

Deliverable:

- Windows app is ready for Android integration phase

## Deliberately Deferred

Not part of this phase:

- Bluetooth transport
- end-to-end encryption beyond shared-secret trust
- background Windows service mode
- group chat semantics
- large resumable video transfer
- NAT traversal or internet connectivity
- simultaneous multi-host mesh architecture

## Final Recommendation

For Phase 1, the most practical architecture is:

- `WPF` Windows desktop app
- in-process `ASP.NET Core` local host
- `UDP` discovery
- `WebSocket + HTTP` transport
- `JSON` protocol contracts in `LocalBridge.Core`
- pairing-based local trust

This gives a working Windows-first solution now and a clean, stable protocol for the later Android client without forcing premature cross-platform UI decisions.
