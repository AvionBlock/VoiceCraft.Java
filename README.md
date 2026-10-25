# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

**VoiceCraft.Java — the ultimate cross-client proximity voice chat plugin connecting VoiceCraft, Simple Voice Chat, and Plasmo Voice across Java and Bedrock.**

VoiceCraft.Java is the interoperability plugin that brings all of those clients into one shared voice space on Java Minecraft servers.

It turns VoiceCraft into a server-wide voice layer where players using different voice clients can share the same proximity voice space. Simple Voice Chat users, Plasmo Voice users, and players connected through the VoiceCraft client can hear and talk to each other through one VoiceCraft backend.

This is especially useful for cross-play servers: Bedrock players can join through Geyser and use the standalone VoiceCraft client, while Java players can keep using Simple Voice Chat or Plasmo Voice. Players without either Java voice mod receive a small vanilla-compatible status overlay that is also visible through Geyser.

## The Idea

VoiceCraft is the audio engine. VoiceCraft.Java is the Minecraft-side plugin that makes that engine understand your server.

The plugin is responsible for:

- connecting Paper, Velocity, or BungeeCord to the VoiceCraft backend;
- creating and binding VoiceCraft entities to Minecraft players;
- keeping player names, worlds, positions, rotation, proximity, cave echo, and muffle state synchronized;
- routing audio between supported clients through the VoiceCraft audio bridge;
- supporting both single-server Paper installs and multi-server proxy networks.

In practice, VoiceCraft.Java is not just “a bridge to VoiceCraft”. It is the control layer that lets several voice ecosystems work together on one Minecraft server.

## What Players Can Use

| Player client | Supported through | What the plugin does |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | Binds the external VoiceCraft session to the in-game player and updates position/effects. |
| Simple Voice Chat | Native server plugin/mod adapter | Uses the official SVC API, creates a VoiceCraft entity, and forwards Opus audio. |
| Plasmo Voice | Native server plugin/mod adapter | Uses the official Plasmo add-on API, creates a VoiceCraft entity, and forwards Opus audio. |
| Bedrock players on Java cross-play servers | VoiceCraft client | Gives Bedrock players a voice path without requiring Java client mods. |

When enabled together on a supported server target, Simple Voice Chat and Plasmo Voice clients are connected to the same VoiceCraft audio bridge. A player speaking from Simple Voice Chat can be heard by Plasmo Voice and VoiceCraft-client users, and the other way around.

## Core Features

- Unified proximity voice chat across VoiceCraft client, Simple Voice Chat, and Plasmo Voice.
- Voice chat support for Bedrock players on Java cross-play servers through the VoiceCraft client.
- Direct Paper mode for single-server setups.
- Velocity and BungeeCord proxy mode for multi-server networks.
- Velocity and BungeeCord coordination for multi-server VoiceCraft networks.
- Managed VoiceCraft Server 1.7 runtime on Paper: automatic download, startup, runtime option sync, and shutdown.
- Server-side player positioning, dimension tracking, proximity distance, cave echo, and muffle updates.
- Binding flow for external VoiceCraft clients with short in-game bind keys.
- Viewer-scoped vanilla voice statuses for unmodded Java and Bedrock players; modded clients keep their native UI without duplicate indicators.
- Localized messages for `en`, `de`, `ru`, `nl`, `pl`, `ja`, `th`, `zh_CN`, and `zh_TW`; localized config templates for `en`, `ru`, `nl`, and `ja`.

## How It Works

### VoiceCraft Client Flow

Players using the VoiceCraft client connect to the VoiceCraft backend. VoiceCraft.Java then binds that external voice entity to the Minecraft player.

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

Typical flow:

1. The player connects to VoiceCraft.
2. VoiceCraft creates a new network entity.
3. VoiceCraft.Java receives that entity and assigns a short binding key.
4. The player runs `/voice bind <key>` in Minecraft.
5. VoiceCraft.Java links the voice entity to the Minecraft player.
6. The plugin continuously updates that entity with the player's world, position, rotation, and voice effects.

This is the primary path for players who cannot use Java voice mods, including Bedrock players.

### Simple Voice Chat and Plasmo Voice Flow

VoiceCraft.Java bridges the official server plugins/mods through their public APIs.

```text
Simple Voice Chat client -> official SVC plugin -> VoiceCraft.Java bridge -> VoiceCraft Server
Plasmo Voice client       -> official Plasmo     -> VoiceCraft.Java bridge -> VoiceCraft Server
VoiceCraft client         ----------------------------------> VoiceCraft Server
```

The official plugins own client handshakes, UDP transport and add-on APIs. VoiceCraft.Java captures their final server-side audio events and passes Opus frames to VoiceCraft Server, which remains the authoritative cross-client router. Audio coming back is injected through the official APIs, so players on different clients participate in the same conversation.

The shared contract preserves player, entity, positional, direct and broadcast sources plus recipient selection. Enable a bridge to let VoiceCraft.Java install the missing official plugin from Modrinth; a server restart is required after installation. VoiceCraft.Java no longer emulates either wire protocol.

## Runtime Modes

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

Use this for a single Paper server.

The Paper plugin owns the VoiceCraft connection. It can either connect to an existing VoiceCraft backend or automatically download and start the VoiceCraft runtime.

### Proxy Mode

```text
Paper and Fabric 26.3 backends -> Velocity -> VoiceCraft
```

Use this for networks with multiple backend servers.

Install VoiceCraft.Java on every Paper or Fabric 26.3 backend and on the proxy. Set `config.proxy.enabled: true` on each backend. Velocity owns player positions and binds, while backends send snapshots and bind requests through the `voicecraft-java:main` plugin channel. Each backend also opens an auxiliary VoiceCraft MC API connection for its native SVC and Plasmo audio bridges. The VoiceCraft Server must allow at least one MC API client per backend plus the proxy (`McTcpConfig.MaxClients`).

Simple Voice Chat and Plasmo Voice keep using their own official proxy/network setup. VoiceCraft.Java no longer exposes raw UDP relays on Velocity or BungeeCord.

## Platform Support

| Platform | Role |
| --- | --- |
| Paper | Direct/backend runtime, managed VoiceCraft Server process, native SVC and Plasmo bridges |
| Fabric 1.20.1+ | Server runtime, managed VoiceCraft Server process, native SVC and Plasmo mod bridges; Velocity backend mode on 26.3 |
| Forge 1.20.1+ | Server runtime with native SVC and/or Plasmo bridges where upstream provides them |
| NeoForge 1.21.1+ | Server runtime with native SVC and/or Plasmo bridges where upstream provides them |
| Velocity | Proxy runtime and central VoiceCraft connection |
| BungeeCord | Proxy runtime and central VoiceCraft connection |

## Requirements

- Java 17 for Minecraft 1.20.x, Java 21 for Minecraft 1.21.x, and Java 25 for Minecraft 26.x.
- Paper, Fabric, Forge, NeoForge, Velocity, or BungeeCord. Quilt is not supported.
- VoiceCraft Server 1.7 backend, unless the managed runtime is enabled.
- The official Simple Voice Chat and/or Plasmo Voice server plugin for enabled bridges (managed installation is available).

The project is currently built against:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Installation

1. Download or build the jar for your platform.
2. Put it into `plugins/` on Paper or a proxy, or `mods/` on a Fabric server.
3. Start the server once to generate `config.yml`.
4. Configure the VoiceCraft connection and the required adapters. If an upstream voice mod is unavailable for this exact target, VoiceCraft.Java logs a warning and keeps the available adapters running.
5. Restart the server or run `/voice reload`.

Both adapters are disabled by default. Enabling one uses the installed official voice mod and its public API; VoiceCraft.Java does not emulate the SVC or Plasmo wire protocol. When managed dependencies are enabled, a missing compatible official mod is downloaded into `mods/` or `plugins/` and activated after a restart.

Build outputs:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
modules/fabric/build/libs/VoiceCraft.Java-fabric-<version>-<minecraft>.jar
modules/forge/build/libs/VoiceCraft.Java-forge-<version>-<minecraft>.jar
modules/neoforge/build/libs/VoiceCraft.Java-neoforge-<version>-<minecraft>.jar
```

Use the shaded jars without the `thin` or `sources` classifier.

## Configuration

### Paper Direct Mode

```yaml
config:
  proxy:
    enabled: false

  voicecraft:
    transport:
      host: "127.0.0.1"
      port: 9050
      login-token: "change-me"
    voice:
      port: 9050
    auto-start: true
    shutdown-on-disable: true
```

Set `auto-start: true` if the Paper plugin should manage VoiceCraft for you. Set it to `false` when running VoiceCraft yourself. The managed launcher starts `VoiceCraft.Server` with McTcp enabled and passes the configured transport host, transport port, login token, and VoiceCraft voice port as runtime options.

### Paper or Fabric 26.3 Backend Behind Proxy

```yaml
config:
  proxy:
    enabled: true
```

In backend mode, Paper or Fabric 26.3 forwards Minecraft player state to Velocity. A separate auxiliary MC API connection carries native SVC and Plasmo audio; it does not own player binds.

### Velocity or BungeeCord

```yaml
config:
  voicecraft:
    transport:
      host: "127.0.0.1"
      port: 9050
      login-token: "change-me"
```

The proxy plugin owns the VoiceCraft connection for the whole network.

### Voice Settings

```yaml
config:
  voice:
    proximity-distance: 30
    proximity-toggle: true
    voice-effects: true
    position-update-interval-ticks: 5

    vanilla-status-overlay:
      enabled: true
      handshake-grace-ticks: 60
      symbols:
        disconnected: "×"
        connected: "○"
        speaking: "●"
        muted: "M"
        deafened: "D"
```

The vanilla overlay uses invisible-by-default armor stands instead of display entities. A status is shown only when both the viewer and the target lack SVC/Plasmo, preventing it from overlapping either mod's native indicators. Symbols, colors, height, update interval, speaking hold time, and view distance are configurable.

### Native Voice Bridges

```yaml
config:
  adapters:
    client-priority: ["simple-voice-chat", "plasmo"]
    managed-dependencies:
      enabled: true
    native-configs:
      owner: "voicecraft" # Set to "native" to stop editing native voice configs
      simple-voice-chat:
        bind-address: ""
        bind-port: 24454
        advertised-host: ""
        advertised-port: 24454
      plasmo:
        bind-address: "0.0.0.0"
        bind-port: 0
        advertised-host: "0.0.0.0"
        advertised-port: 0
    simple-voice-chat:
      enabled: true
      install:
        enabled: true
    plasmo:
      enabled: true
      install:
        enabled: true
```

When both client mods are present, `client-priority` selects one output transport and prevents duplicate audio. On Paper dependencies are staged in `plugins/`; on Fabric/Forge/NeoForge they are staged in `mods/`. A restart activates newly installed dependencies.

With `native-configs.owner: "voicecraft"`, this file is the source of truth for the bind and advertised UDP ports. VoiceCraft.Java updates only SVC's `port`, `bind_address`, and `voice_host` properties and Plasmo's `[host]`/`[host.public]` address fields; all other native settings and comments are preserved. `/voice reload` writes changed values, but already-open UDP listeners require a server restart. Set the owner to `"native"` when the official mods should manage their own files instead.

## GitLab releases and Modrinth

Every pipeline runs tests and exposes final shaded Paper, Velocity, BungeeCord, Fabric, Forge, and NeoForge JARs as GitLab artifacts. Mod targets are built as a parallel matrix and validated against `gradle/voice-targets.json`.

For a release, run a pipeline from the GitLab UI and set the exact `RELEASE_VERSION`, channel, title, tag, and Markdown changelog. Start the manual `release_build` job to build every supported target. To publish, set `PUBLISH_MODRINTH=true`, keep `MODRINTH_DRY_RUN=true` for the first metadata/file validation, then run again with it set to `false`. Store `MODRINTH_TOKEN` as a masked, protected GitLab CI/CD variable; never put the token in this file or the repository.

Fabric versions declare Fabric API as required on Modrinth. Simple Voice Chat and Plasmo Voice are declared optional only for targets where their adapters exist. Fabric Permissions API is embedded in the Fabric JAR and therefore is not published as an external dependency.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | Save connection settings and connect to VoiceCraft. |
| `/voice reconnect [true\|false]` | `voice.reconnect` | Reconnect to VoiceCraft. Use `true` to force reconnect. |
| `/voice disconnect` | `voice.disconnect` | Disconnect from VoiceCraft. |
| `/voice bind <key>` | `voice.bind` | Bind the current Minecraft player to a VoiceCraft client entity. |
| `/voice bindfake <key> <name>` | `voice.bindfake` | Bind a VoiceCraft entity to a fake player name. |
| `/voice reload` | `voice.reload` | Reload config and apply settings. |

## Project Structure

```text
modules/
  core/                             Shared VoiceCraft protocol, session, proxy, config, localization code
  adapter-api/                      Shared adapter contracts for audio and entity identity
  paper/                            Paper plugin, managed runtime, native API bridges, backend mode
  velocity/                         Velocity proxy plugin
  bungeecord/                       BungeeCord proxy plugin
```

## Building

```bash
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

Run tests:

```bash
./gradlew test
```

## Links

- Project site: https://java.voicecraft.chat
- Wiki / Docs: https://java.voicecraft.chat/docs
- Contribution guide: https://java.voicecraft.chat/docs/contribution

## Third-Party Projects

VoiceCraft.Java implements compatibility layers for these voice chat projects. They are separate upstream projects with their own repositories and licenses.

| Project | Repository | License |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## License

VoiceCraft.Java is licensed under the GNU Affero General Public License v3.0 or later. See [LICENSE](LICENSE) for details.
