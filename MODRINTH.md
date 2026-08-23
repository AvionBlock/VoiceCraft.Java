# VoiceCraft.Java

**The ultimate cross-client proximity voice chat plugin connecting VoiceCraft, Simple Voice Chat, and Plasmo Voice across Java and Bedrock.**

VoiceCraft.Java brings players using different voice clients into one shared proximity voice space. A player using Simple Voice Chat can talk to someone using Plasmo Voice or the standalone VoiceCraft client — and hear them back through the client they already use.

It is especially useful for cross-play servers. Java players can keep their preferred voice-chat mods, while Bedrock players joining through Geyser can participate through the VoiceCraft client without installing Java mods.

## One server, every supported client

| Player client | How it connects |
| --- | --- |
| VoiceCraft client | Connects to the VoiceCraft backend and binds to the Minecraft player with `/voice bind` |
| Simple Voice Chat | Connects through the official SVC server plugin/mod and its public API |
| Plasmo Voice | Connects through the official Plasmo Voice server plugin/mod and its public API |
| Bedrock through Geyser | Uses the standalone VoiceCraft client with no Java client mod required |

All connected players share the same positional audio environment. VoiceCraft.Java synchronizes Minecraft worlds, positions, rotation, proximity distance, cave echo, underwater muffling, player identity, speaking lifecycle, and supported mute/deafen states.

## Features

- Cross-client proximity audio between VoiceCraft, Simple Voice Chat, and Plasmo Voice.
- Voice chat for Java and Bedrock players in the same conversation.
- Native SVC and Plasmo API bridges without protocol emulation.
- Automatic installation of compatible official SVC/Plasmo server dependencies.
- One VoiceCraft.Java config for VoiceCraft, SVC, and Plasmo bind/advertised ports.
- Automatic VoiceCraft Server download, configuration, startup, and shutdown on Paper.
- Direct mode for a single Paper server.
- Velocity and BungeeCord mode for multi-server networks.
- UDP relay support for SVC and Plasmo across proxy networks.
- Position, dimension, proximity, cave echo, and underwater audio synchronization.
- Localized configuration and messages.

## Vanilla and Bedrock voice statuses

Players without SVC or Plasmo can see lightweight vanilla-compatible voice statuses above other unmodded players. The indicators work through Geyser and show whether a player is disconnected, connected, speaking, muted, or deafened.

The overlay is viewer-scoped: players using SVC or Plasmo never receive it, so it does not overlap the native indicators provided by their mods. Every symbol, color, height, update interval, and view distance can be configured.

## Installation modes

### Paper

Install the Paper jar for a standalone server. VoiceCraft.Java can automatically download and manage a matching VoiceCraft Server runtime, or connect to an existing VoiceCraft backend.

Enable the Simple Voice Chat and/or Plasmo Voice adapters in `config.yml` when those clients should be accepted. Both adapters are disabled by default.

### Velocity or BungeeCord

Install VoiceCraft.Java on the proxy and every Paper backend. The proxy owns the central VoiceCraft connection, while backend servers provide player state and adapter sessions. Optional UDP relays keep SVC and Plasmo clients connected while players move between backend servers.

## Requirements

- Java 17 for Minecraft 1.20.x, Java 21 for 1.21.x, and Java 25 for 26.x.
- Paper for voice adapters and managed VoiceCraft runtime support.
- Velocity or BungeeCord only when running a multi-server network.
- Reachable UDP ports for every enabled voice adapter.

Enable at least one official voice plugin/mod for the Java clients you want to support. VoiceCraft.Java can download the compatible dependency automatically, or you can install it yourself. The official plugin owns the client handshake and UDP transport; VoiceCraft.Java routes its final Opus frames through VoiceCraft Server.

VoiceCraft.Java focuses on the shared experience that matters across clients: proximity audio, identity, speaking state, and voice controls. Client-exclusive features such as private Simple Voice Chat groups remain within their own ecosystem instead of being falsely emulated for other clients.

## Supported platforms

- Paper, Purpur, Folia, Spigot, and Bukkit
- Fabric
- Forge
- NeoForge
- Velocity
- BungeeCord

Minecraft 1.20.1 and newer are supported through per-version mod artifacts. Not every Minecraft/modloader combination has both upstream voice mods; VoiceCraft.Java keeps the available bridge working and logs a clear warning when an enabled adapter does not exist for that target.

VoiceCraft.Java is licensed under the GNU Affero General Public License v3.0 or later.
