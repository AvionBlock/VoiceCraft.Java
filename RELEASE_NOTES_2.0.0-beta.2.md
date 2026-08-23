# VoiceCraft.Java 2.0.0 Beta 2

This is a major interoperability rewrite focused on reliable cross-client proximity voice chat.

Players using **Simple Voice Chat**, **Plasmo Voice**, and the standalone **VoiceCraft client** can now participate in the same positional voice space. Java and Bedrock players can hear each other without requiring everyone to use the same client.

## Highlights

- Replaced the built-in Simple Voice Chat and Plasmo Voice protocol emulation with bridges to their official server plugins/mods and public APIs.
- Added stable, bidirectional Opus audio routing between Simple Voice Chat, Plasmo Voice, and VoiceCraft clients.
- Fixed broken, stuttering, reordered, and partially dropped audio frames in both bridge directions.
- Synchronized connected, muted, deafened, and speaking states across supported voice clients.
- Restored correct player lists, per-player volume controls, overhead speaking indicators, and online status in both native voice mods.
- Preserved positional, direct, entity, and broadcast audio sources together with their intended recipients.
- Added endpoint selection so players with both native clients installed receive audio through only one preferred transport.
- Updated the Minecraft-side protocol implementation for compatibility with VoiceCraft Server 1.7.1.

## Platforms and Minecraft versions

- Paper, Velocity, and BungeeCord.
- Fabric and Forge for Minecraft 1.20.1 and newer supported releases.
- NeoForge for Minecraft 1.21.1 and newer supported releases.
- Current build targets: 1.20.1, 1.20.2-1.20.4, 1.21-1.21.1, 1.21.4, 1.21.5, 1.21.6-1.21.7, 1.21.8, 1.21.10, 1.21.11, 26.1.x, and 26.2.
- Quilt is not supported.
- If an upstream voice mod does not exist for a particular platform/version, VoiceCraft.Java keeps the available bridge running and reports the missing adapter clearly in the server console.

## Server management

- Added managed installation of compatible Simple Voice Chat and Plasmo Voice server dependencies.
- Added one-place management of bind and advertised addresses/ports for both native voice mods.
- Added `config.adapters.native-configs.owner`: use `voicecraft` for centralized management or `native` to leave the official mods' files untouched.
- Changed the default VoiceCraft port to `9050`.
- Native configuration comments and unrelated settings are preserved during synchronization.

## Distribution

- Added separate release artifacts for every supported Minecraft version and loader.
- Added GitLab CI build matrices for Paper, Velocity, BungeeCord, Fabric, Forge, and NeoForge.
- Added manual GitLab-to-Modrinth publishing with release/beta/alpha channels, metadata validation, and correct required/optional dependency declarations.

> This is a beta release. Installing a managed voice dependency or changing an already active UDP port requires a server restart. Please report any platform-specific audio or connection issues.

Changes are summarized relative to commit `2c567a1664113dbf34f13161b6a2853316e97a68`.
