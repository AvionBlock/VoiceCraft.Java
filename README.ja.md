# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

**VoiceCraft.Java — Java と Bedrock 上の VoiceCraft、Simple Voice Chat、Plasmo Voice をつなぐ究極の cross-client proximity voice chat プラグイン。**

VoiceCraft.Java は、これらすべての client を Java Minecraft サーバー上の 1 つの共有ボイス空間に統合する相互運用プラグインです。

VoiceCraft をサーバー全体のボイスレイヤーとして使えるようにし、異なるボイスクライアントを使うプレイヤーが同じ proximity voice 空間を共有できます。Simple Voice Chat、Plasmo Voice、VoiceCraft client のプレイヤーが、1 つの backend を通して互いの声を聞き、会話できます。

これは cross-play サーバーで特に便利です。Bedrock プレイヤーは Geyser 経由で参加してスタンドアロン VoiceCraft client を利用でき、Java プレイヤーは Simple Voice Chat や Plasmo Voice を使い続けられます。これらの Java mod を持たないプレイヤーには、Geyser でも表示できる小さな vanilla 互換ステータスが表示されます。

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

| プレイヤークライアント | 対応方式 | プラグインの役割 |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | 外部 VoiceCraft session をゲーム内プレイヤーに bind し、位置や効果を更新します。 |
| Simple Voice Chat | Paper adapter | plugin-message/UDP handshake を処理し、VoiceCraft entity を作成して Opus audio を転送します。 |
| Plasmo Voice | Paper adapter | Plasmo TCP/UDP protocol を処理し、VoiceCraft entity を作成して Opus audio を転送します。 |
| Java cross-play サーバー上の Bedrock プレイヤー | VoiceCraft client | Java client mods なしで Bedrock プレイヤーに voice path を提供します。 |

Paper 上で一緒に有効化すると、Simple Voice Chat と Plasmo Voice のクライアントは同じ VoiceCraft audio bridge に接続されます。Simple Voice Chat で話すプレイヤーの声は Plasmo Voice や VoiceCraft client のユーザーに届き、その逆も同様です。

## 主な機能

- VoiceCraft client、Simple Voice Chat、Plasmo Voice をまたいだ統合 proximity voice chat。
- VoiceCraft client による Java cross-play サーバー上の Bedrock プレイヤー向け voice chat。
- 単一サーバー向け Direct Paper mode。
- 複数サーバーネットワーク向け Velocity / BungeeCord proxy mode。
- Velocity / BungeeCord ネットワーク向け Simple Voice Chat / Plasmo Voice の proxy UDP relay。
- Paper 上の managed VoiceCraft Server 1.7 runtime: 自動ダウンロード、起動、runtime option の同期、停止。
- サーバー側の player positioning、dimension tracking、proximity distance、cave echo、muffle updates。
- 外部 VoiceCraft clients 向けの短い in-game bind keys による binding flow。
- mod なしの Java/Bedrock プレイヤー向け viewer 単位の vanilla ステータス。SVC/Plasmo クライアントでは標準 UI のみが表示されます。
- メッセージ翻訳: `en`, `de`, `ru`, `nl`, `pl`, `ja`, `th`, `zh_CN`, `zh_TW`。ローカライズ済み config template: `en`, `ru`, `nl`, `ja`。

## 仕組み

### VoiceCraft Client の流れ

VoiceCraft client を使うプレイヤーは VoiceCraft backend に接続します。その後 VoiceCraft.Java が、その外部 voice entity を Minecraft プレイヤーに bind します。

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

典型的な流れ:

1. プレイヤーが VoiceCraft に接続します。
2. VoiceCraft が新しい network entity を作成します。
3. VoiceCraft.Java がその entity を受け取り、短い binding key を割り当てます。
4. プレイヤーが Minecraft 内で `/voice bind <key>` を実行します。
5. VoiceCraft.Java が voice entity を Minecraft プレイヤーにリンクします。
6. プラグインがその entity の world、position、rotation、voice effects を継続的に更新します。

これは Java voice mods を使えないプレイヤー、特に Bedrock プレイヤー向けの主要な経路です。

### Simple Voice Chat と Plasmo Voice の流れ

Paper 上では、VoiceCraft.Java は Java voice mods 向けの compatibility server として動作できます。

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

Adapters はクライアントプロトコルを扱い、UDP sessions を管理し、Opus frames を共有 VoiceCraft audio bridge に渡します。VoiceCraft から戻ってきた audio は登録済み adapters に配信されるため、異なるクライアントのプレイヤーが同じ会話に参加できます。

共有 compatibility contract は proximity audio、identity、speaking lifecycle、player voice state を扱います。Simple Voice Chat の private group など client 固有の機能は各 ecosystem 内に残り、異なる client 間では意図的に変換されません。対応する VoiceCraft.Java adapter がポートを使用する場合、同じ Paper instance に元の SVC/Plasmo server plugin をインストールしないでください。

## 実行モード

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

単一の Paper サーバー向けです。

Paper plugin が VoiceCraft connection を所有します。既存の VoiceCraft backend に接続することも、VoiceCraft runtime を自動でダウンロードして起動することもできます。

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

複数の backend servers を持つネットワーク向けです。

VoiceCraft.Java をすべての Paper backend と proxy にインストールします。Proxy が VoiceCraft connection を所有し、Paper backends は `voicecraft-java:main` plugin channel 経由で player snapshots と bind/unbind requests を送信します。

Proxy mode でも Simple Voice Chat と Plasmo Voice の audio は UDP のままです。Paper は `voicecraft-java:main` で voice routes を登録し、Velocity/BungeeCord は public UDP relay ports を開いて raw voice datagrams を現在の backend Paper adapter に転送します。

## 対応プラットフォーム

| プラットフォーム | 役割 |
| --- | --- |
| Paper | Direct runtime、backend runtime、managed VoiceCraft Server process、Simple Voice Chat adapter、Plasmo Voice adapter |
| Velocity | Proxy runtime、中央 VoiceCraft connection、Simple Voice Chat / Plasmo の UDP relay |
| BungeeCord | Proxy runtime、中央 VoiceCraft connection、Simple Voice Chat / Plasmo の UDP relay |

## 要件

- Java 21。
- Paper、Velocity、BungeeCord。
- Paper managed runtime を使わない場合は VoiceCraft Server 1.7 backend。
- 有効化した Simple Voice Chat または Plasmo Voice adapters 用の到達可能な UDP ports。

現在のビルド対象:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## インストール

1. 使用するプラットフォーム向けの jar をダウンロードまたはビルドします。
2. サーバーの `plugins` ディレクトリに配置します。
3. 一度サーバーを起動して `config.yml` を生成します。
4. VoiceCraft connection と必要な adapters を設定します。
5. サーバーを再起動するか `/voice reload` を実行します。

両 adapter はデフォルトで無効です。有効にすると VoiceCraft.Java がその client mod の server endpoint になるため、UDP port を外部から到達可能にし、元の voice-chat server plugin と重複させないでください。

ビルド成果物:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
```

`thin` や `sources` classifier のない shaded jars を使用してください。

## 設定

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

Paper plugin に VoiceCraft を管理させる場合は `auto-start: true` にします。自分で VoiceCraft を実行する場合は `false` にしてください。管理ランチャーは McTcp を有効にして `VoiceCraft.Server` を起動し、設定された transport host、transport port、login token、VoiceCraft voice port を runtime options として渡します。

### Proxy 配下の Paper Backend

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

### Proxy UDP Relay

Paper backends が proxy を Plasmo/Simple Voice Chat の public endpoint として広告する場合、Velocity または BungeeCord でこれを有効にします。

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

Proxy network では、Paper adapter の `public.host` と `public.port` を proxy address と relay port に設定します。Protocol session は Paper adapter が引き続き所有し、proxy は route を登録した backend へ raw UDP packets を転送するだけです。

### Voice 設定

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

vanilla overlay は display entity ではなく、デフォルトで非表示の ArmorStand を使います。viewer と target の両方が SVC/Plasmo を使っていない場合だけ表示されます。symbol、color、高さ、更新間隔、speaking hold、表示距離を設定できます。

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

`bind-host` はローカル socket、`public.host` は Plasmo client に送信する値です。direct Paper では `0.0.0.0` がデフォルトです。proxy mode では到達可能な proxy address と relay port を `public.port` に設定してください。

## コマンド

| コマンド | 権限 | 説明 |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | 接続設定を保存し VoiceCraft に接続します。 |
| `/voice reconnect [true\|false]` | `voice.reconnect` | VoiceCraft に再接続します。`true` で強制再接続します。 |
| `/voice disconnect` | `voice.disconnect` | VoiceCraft から切断します。 |
| `/voice bind <key>` | `voice.bind` | 現在の Minecraft player を VoiceCraft client entity に bind します。 |
| `/voice bindfake <key> <name>` | `voice.bindfake` | VoiceCraft entity を fake player name に bind します。 |
| `/voice reload` | `voice.reload` | Config を再読み込みし、設定を適用します。 |

## プロジェクト構成

```text
modules/
  core/                             VoiceCraft protocol、session、proxy、config、localization の共有コード
  adapter-api/                      audio と entity identity 用の共有 adapter contract
  adapter-simplevoicechat-protocol/ Simple Voice Chat packet/session protocol support
  adapter-plasmo-protocol/          Plasmo Voice packet/session protocol support
  paper/                            Paper plugin、managed runtime、adapters、backend mode
  velocity/                         Velocity proxy plugin
  bungeecord/                       BungeeCord proxy plugin
```

## ビルド

```bash
./gradlew clean build
```

Windows:

```powershell
.\gradlew.bat clean build
```

テスト:

```bash
./gradlew test
```

## リンク

- プロジェクトサイト: https://java.voicecraft.chat
- Wiki / ドキュメント: https://java.voicecraft.chat/docs
- コントリビューションガイド: https://java.voicecraft.chat/docs/contribution

## サードパーティプロジェクト

VoiceCraft.Java は以下の voice chat projects 向け compatibility layers を実装しています。これらはそれぞれ独立した upstream projects であり、各自の repositories と licenses を持ちます。

| プロジェクト | リポジトリ | ライセンス |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## ライセンス

VoiceCraft.Java は GNU Affero General Public License v3.0 or later の下でライセンスされています。詳細は [LICENSE](LICENSE) を参照してください。
