# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

VoiceCraft.Java is an ultimate voice chat plugin for Java Minecraft servers.

It turns VoiceCraft into a server-wide voice layer where players using different voice clients can share the same proximity voice space. Simple Voice Chat users, Plasmo Voice users, and players connected through the VoiceCraft client can hear and talk to each other through one VoiceCraft backend.

This is especially useful for cross-play servers: Bedrock players can join a Java server and use the VoiceCraft client for voice chat, while Java players can keep using familiar clients such as Simple Voice Chat or Plasmo Voice.

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
| Simple Voice Chat | Paper adapter | Handles plugin-message/UDP handshake, creates a VoiceCraft entity, and forwards Opus audio. |
| Plasmo Voice | Paper adapter | Handles Plasmo TCP/UDP protocol, creates a VoiceCraft entity, and forwards Opus audio. |
| Bedrock players on Java cross-play servers | VoiceCraft client | Gives Bedrock players a voice path without requiring Java client mods. |

When enabled together on Paper, Simple Voice Chat and Plasmo Voice clients are connected to the same VoiceCraft audio bridge. A player speaking from Simple Voice Chat can be heard by Plasmo Voice and VoiceCraft-client users, and the other way around.

## Core Features

- Unified proximity voice chat across VoiceCraft client, Simple Voice Chat, and Plasmo Voice.
- Voice chat support for Bedrock players on Java cross-play servers through the VoiceCraft client.
- Direct Paper mode for single-server setups.
- Velocity and BungeeCord proxy mode for multi-server networks.
- Proxy UDP relay for Simple Voice Chat and Plasmo Voice in Velocity/BungeeCord networks.
- Managed VoiceCraft Server 1.7 runtime on Paper: automatic download, startup, runtime option sync, and shutdown.
- Server-side player positioning, dimension tracking, proximity distance, cave echo, and muffle updates.
- Binding flow for external VoiceCraft clients with short in-game bind keys.
- Localized config/message templates: `en`, `ru`, `nl`, `ja`.

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

On Paper, VoiceCraft.Java can act as a compatibility server for Java voice mods.

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

The adapters speak the client protocols, manage UDP sessions, and pass Opus frames into the shared VoiceCraft audio bridge. Audio coming back from VoiceCraft is fanned out to the registered adapters, so players on different clients can participate in the same conversation.

## Runtime Modes

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

Use this for a single Paper server.

The Paper plugin owns the VoiceCraft connection. It can either connect to an existing VoiceCraft backend or automatically download and start the VoiceCraft runtime.

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

Use this for networks with multiple backend servers.

Install VoiceCraft.Java on every Paper backend and on the proxy. The proxy owns the VoiceCraft connection, while Paper backends send player snapshots and bind/unbind requests through the `voicecraft-java:main` plugin channel.

Simple Voice Chat and Plasmo Voice audio stays on UDP in proxy mode. Paper registers voice routes through `voicecraft-java:main`, while Velocity/BungeeCord exposes public UDP relay ports and forwards raw voice datagrams to the current backend Paper adapter.

## Platform Support

| Platform | Role |
| --- | --- |
| Paper | Direct runtime, backend runtime, managed VoiceCraft Server process, Simple Voice Chat adapter, Plasmo Voice adapter |
| Velocity | Proxy runtime, central VoiceCraft connection, Simple Voice Chat / Plasmo UDP relay |
| BungeeCord | Proxy runtime, central VoiceCraft connection, Simple Voice Chat / Plasmo UDP relay |

## Requirements

- Java 21.
- Paper, Velocity, or BungeeCord.
- VoiceCraft Server 1.7 backend, unless Paper managed runtime is enabled.
- Reachable UDP ports for enabled Simple Voice Chat or Plasmo Voice adapters.

The project is currently built against:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Installation

1. Download or build the jar for your platform.
2. Put it into the server's `plugins` directory.
3. Start the server once to generate `config.yml`.
4. Configure the VoiceCraft connection and the required adapters.
5. Restart the server or run `/voice reload`.

Build outputs:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
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
      port: 1111
    auto-start: true
    shutdown-on-disable: true
```

Set `auto-start: true` if the Paper plugin should manage VoiceCraft for you. Set it to `false` when running VoiceCraft yourself. The managed launcher starts `VoiceCraft.Server` with McTcp enabled and passes the configured transport host, transport port, login token, and VoiceCraft voice port as runtime options.

### Paper Backend Behind Proxy

```yaml
config:
  proxy:
    enabled: true
```

In backend mode, Paper does not connect to VoiceCraft directly. It forwards Minecraft player state to Velocity or BungeeCord.

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

### Proxy UDP Relay

Enable these on Velocity or BungeeCord when Paper backends advertise the proxy as the public Plasmo/Simple Voice Chat endpoint:

```yaml
config:
  adapters:
    plasmo:
      proxy:
        enabled: true
        bind-host: "0.0.0.0"
        port: 24454
    simple-voice-chat:
      proxy:
        enabled: true
        bind-host: "0.0.0.0"
        port: 24455
```

For proxy networks, set the Paper adapter `public.host` and `public.port` to the proxy address and relay port. The Paper adapter still owns the protocol session; the proxy only routes raw UDP packets to the backend that registered the route.

### Voice Settings

```yaml
config:
  voice:
    proximity-distance: 30
    proximity-toggle: true
    voice-effects: true
    position-update-interval-ticks: 5
```

### Simple Voice Chat Adapter

```yaml
config:
  adapters:
    simple-voice-chat:
      enabled: true
      bind-host: "0.0.0.0"
      port: 24455
      public:
        host: ""
        port: 0
      compatibility-version: 20
      mtu-size: 1024
      keep-alive-ms: 1000
      allow-recording: true
```

### Plasmo Voice Adapter

```yaml
config:
  adapters:
    plasmo:
      enabled: true
      bind-host: "0.0.0.0"
      port: 24454
      public:
        host: ""
        port: 0
```

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
  adapter-simplevoicechat-protocol/ Simple Voice Chat packet/session protocol support
  adapter-plasmo-protocol/          Plasmo Voice packet/session protocol support
  paper/                            Paper plugin, managed runtime, adapters, backend mode
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
