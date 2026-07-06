# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

VoiceCraft.Java は Java Minecraft サーバー向けの究極のボイスチャットプラグインです。

VoiceCraft をサーバー全体のボイスレイヤーとして使えるようにし、異なるボイスクライアントを使うプレイヤーが同じ proximity voice 空間を共有できます。Simple Voice Chat、Plasmo Voice、VoiceCraft client のプレイヤーが、1 つの backend を通して互いに聞き合い、話すことができます。

これは cross-play サーバーで特に便利です。Bedrock プレイヤーは Java サーバーに参加して VoiceCraft client でボイスチャットを使えます。一方で Java プレイヤーは Simple Voice Chat や Plasmo Voice のような慣れたクライアントを使い続けられます。

## コンセプト

VoiceCraft はオーディオエンジンです。VoiceCraft.Java は、そのエンジンに Minecraft サーバーの状態を理解させる Minecraft-side プラグインです。

このプラグインは以下を担当します。

- Paper、Velocity、BungeeCord を VoiceCraft backend に接続する。
- VoiceCraft entities を作成し、Minecraft プレイヤーに bind する。
- プレイヤー名、ワールド、位置、回転、proximity、cave echo、muffle 状態を同期する。
- VoiceCraft audio bridge を通して対応クライアント間の audio を routing する。
- 単一 Paper サーバーと multi-server proxy ネットワークの両方をサポートする。

実際には、VoiceCraft.Java は単なる「VoiceCraft への bridge」ではありません。複数の voice ecosystem を 1 つの Minecraft サーバー上で連携させる control layer です。

## プレイヤーが使えるクライアント

| Player client | Supported through | プラグインの役割 |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | 外部 VoiceCraft session をゲーム内プレイヤーに bind し、位置や効果を更新します。 |
| Simple Voice Chat | Paper adapter | plugin-message/UDP handshake を処理し、VoiceCraft entity を作成して Opus audio を転送します。 |
| Plasmo Voice | Paper adapter | Plasmo TCP/UDP protocol を処理し、VoiceCraft entity を作成して Opus audio を転送します。 |
| Java cross-play サーバー上の Bedrock プレイヤー | VoiceCraft client | Java client mods なしで Bedrock プレイヤーに voice path を提供します。 |

Paper 上で一緒に有効化すると、Simple Voice Chat と Plasmo Voice のクライアントは同じ VoiceCraft audio bridge に接続されます。Simple Voice Chat から話すプレイヤーは Plasmo Voice や VoiceCraft client のユーザーに聞こえ、その逆も同様です。

## 主な機能

- VoiceCraft client、Simple Voice Chat、Plasmo Voice をまたいだ unified proximity voice chat。
- VoiceCraft client による Java cross-play サーバー上の Bedrock プレイヤー向け voice chat。
- 単一サーバー向け Direct Paper mode。
- multi-server ネットワーク向け Velocity / BungeeCord proxy mode。
- Paper 上の managed VoiceCraft runtime: 自動 download、startup、configuration sync、shutdown。
- Server-side の player positioning、dimension tracking、proximity distance、cave echo、muffle updates。
- 外部 VoiceCraft clients 向けの短い in-game bind keys による binding flow。
- ローカライズ済み config/message templates: `en`, `ru`, `nl`, `ja`。
- Paper 上の optional PlaceholderAPI support。

## 仕組み

### VoiceCraft Client Flow

VoiceCraft client を使うプレイヤーは VoiceCraft backend に接続します。その後 VoiceCraft.Java が、その外部 voice entity を Minecraft プレイヤーに bind します。

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

典型的な flow:

1. プレイヤーが VoiceCraft に接続します。
2. VoiceCraft が新しい network entity を作成します。
3. VoiceCraft.Java がその entity を受け取り、短い binding key を割り当てます。
4. プレイヤーが Minecraft 内で `/voice bind <key>` を実行します。
5. VoiceCraft.Java が voice entity を Minecraft プレイヤーにリンクします。
6. プラグインがその entity の world、position、rotation、voice effects を継続的に更新します。

これは Java voice mods を使えないプレイヤー、特に Bedrock プレイヤー向けの主要な経路です。

### Simple Voice Chat と Plasmo Voice Flow

Paper 上では、VoiceCraft.Java は Java voice mods 向けの compatibility server として動作できます。

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

Adapters はクライアントプロトコルを扱い、UDP sessions を管理し、Opus frames を共有 VoiceCraft audio bridge に渡します。VoiceCraft から戻ってきた audio は登録済み adapters に配信されるため、異なるクライアントのプレイヤーが同じ会話に参加できます。

## Runtime Modes

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

単一 Paper サーバー向けです。

Paper plugin が VoiceCraft connection を所有します。既存の VoiceCraft backend に接続することも、VoiceCraft runtime を自動で download して起動することもできます。

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

複数 backend servers を持つネットワーク向けです。

VoiceCraft.Java をすべての Paper backend と proxy にインストールします。Proxy が VoiceCraft connection を所有し、Paper backends は `voicecraft-java:main` plugin channel 経由で player snapshots と bind/unbind requests を送信します。

## Platform Support

| Platform | Role |
| --- | --- |
| Paper | Direct runtime, backend runtime, managed VoiceCraft process, Simple Voice Chat adapter, Plasmo Voice adapter |
| Velocity | Proxy runtime と中央 VoiceCraft connection |
| BungeeCord | Proxy runtime と中央 VoiceCraft connection |

## Requirements

- Java 21。
- Paper、Velocity、BungeeCord。
- Paper managed runtime を使わない場合は VoiceCraft backend。
- 有効化した Simple Voice Chat または Plasmo Voice adapters 用の到達可能な UDP ports。

現在の build target:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Installation

1. 使用する platform 向けの jar を download または build します。
2. サーバーの `plugins` directory に配置します。
3. 一度サーバーを起動して `config.yml` を生成します。
4. VoiceCraft connection と必要な adapters を設定します。
5. サーバーを再起動するか `/voice reload` を実行します。

Build outputs:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
```

`thin` や `sources` classifier のない shaded jars を使用してください。

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

Paper plugin に VoiceCraft を管理させる場合は `auto-start: true` にします。自分で VoiceCraft を実行する場合は `false` にしてください。

### Paper Backend Behind Proxy

```yaml
config:
  proxy:
    enabled: true
```

Backend mode では Paper は VoiceCraft に直接接続しません。Minecraft player state を Velocity または BungeeCord に転送します。

### Velocity または BungeeCord

```yaml
config:
  voicecraft:
    transport:
      host: "127.0.0.1"
      port: 9050
      login-token: "change-me"
```

Proxy plugin がネットワーク全体の VoiceCraft connection を所有します。

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
        host: "0.0.0.0"
        port: 0
```

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | Connection settings を保存し VoiceCraft に接続します。 |
| `/voice reconnect [true\|false]` | `voice.reconnect` | VoiceCraft に再接続します。`true` で force reconnect。 |
| `/voice disconnect` | `voice.disconnect` | VoiceCraft から切断します。 |
| `/voice bind <key>` | `voice.bind` | 現在の Minecraft player を VoiceCraft client entity に bind します。 |
| `/voice bindfake <key> <name>` | `voice.bindfake` | VoiceCraft entity を fake player name に bind します。 |
| `/voice reload` | `voice.reload` | Config を reload し settings を適用します。 |

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

Windows:

```powershell
.\gradlew.bat clean build
```

Tests:

```bash
./gradlew test
```

## Links

- Project site: https://java.voicecraft.chat
- Wiki / Docs: https://java.voicecraft.chat/docs
- Contribution guide: https://java.voicecraft.chat/docs/contribution

## Third-Party Projects

VoiceCraft.Java は以下の voice chat projects 向け compatibility layers を実装しています。これらはそれぞれ独立した upstream projects であり、各自の repositories と licenses を持ちます。

| Project | Repository | License |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## License

VoiceCraft.Java は GNU Affero General Public License v3.0 or later の下でライセンスされています。詳細は [LICENSE](LICENSE) を参照してください。
