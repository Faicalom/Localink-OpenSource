# Localink Core

This folder contains the shared core library for Localink.

`Localink.Core` is an important part of the project and should not be deleted.
It provides the common protocol and shared models used by the rest of the
system.

## Why It Matters

The Localink project is split into multiple parts:
- `Localink.Desktop` for the Windows host
- `Localink.Android` for the Android companion
- `Localink.Core` for shared logic and protocol contracts

Without `Localink.Core`, the project loses the common language used by the two
sides of the system.

## What It Contains

This core library currently includes shared pieces such as:
- protocol models
- protocol envelope validation
- discovery packet definitions
- security / trust models
- JSON defaults
- network ports and shared constants

## Main Folders

```text
Localink.Core/
  Discovery/
  Protocol/
  Security/
  JsonDefaults.cs
  NetworkPorts.cs
  Localink.Core.csproj
```

## Current Role In The Project

The core library supports:
- consistent protocol versioning
- shared serialization structure
- local discovery payloads
- pairing and trust-related models
- connection metadata used by Windows and Android

## Important Recent Context

After the recent project updates, this core library remains part of the same
system that now includes:
- QR-assisted pairing
- direct local connection after QR scan
- LAN-first transfer flow
- Bluetooth fallback support
- release packaging updates

Even when the QR user experience changed in the apps, the shared core still
matters because it defines the contracts that keep both sides aligned.

## Build Notes

This project is a shared .NET library:

- target framework: `net8.0`
- nullable enabled
- implicit usings enabled

Typical build from solution root:

```powershell
dotnet build .\Localink.sln
```

## Publishing Notes

This folder is source code, not a distributable application by itself.

It should be kept in the repository if you want:
- the Windows app to build correctly
- the shared protocol to remain documented in code
- the project structure to stay maintainable

It should not contain:
- build outputs
- temporary object files
- generated binaries for publishing

## Security And Source Protection Reality

This folder has been cleaned for source publishing, but an important truth
still applies:

- if the repository is public, the source code can still be read and copied

What we can do realistically:
- remove generated outputs
- remove local machine artifacts
- keep repository structure clean
- attach a closed license notice

What we cannot do:
- make public source code unreadable while still publishing it publicly

## License And Rights

No open-source permission is granted by default in this folder unless the owner
explicitly changes the license.
