# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

VoiceCraft.Java is een ultieme voicechat-plugin voor Java Minecraft-servers.

Het maakt van VoiceCraft een serverbrede voice-laag waarin spelers met verschillende voiceclients dezelfde proximity voice-ruimte delen. Simple Voice Chat-gebruikers, Plasmo Voice-gebruikers en spelers die via de VoiceCraft client verbonden zijn, kunnen elkaar via dezelfde backend horen en met elkaar praten.

Dit is vooral nuttig voor cross-play servers: Bedrock-spelers kunnen een Java-server joinen en de VoiceCraft client gebruiken voor voicechat, terwijl Java-spelers vertrouwde clients zoals Simple Voice Chat of Plasmo Voice kunnen blijven gebruiken.

## Het Idee

VoiceCraft is de audio-engine. VoiceCraft.Java is de Minecraft-side plugin die ervoor zorgt dat die engine je server begrijpt.

De plugin is verantwoordelijk voor:

- Paper, Velocity of BungeeCord verbinden met de VoiceCraft backend;
- VoiceCraft entities maken en aan Minecraft-spelers binden;
- spelersnamen, werelden, posities, rotatie, proximity, cave echo en muffle-status synchroniseren;
- audio tussen ondersteunde clients routeren via de VoiceCraft audio bridge;
- ondersteuning voor single-server Paper installs en multi-server proxy-netwerken.

In de praktijk is VoiceCraft.Java niet zomaar "een bridge naar VoiceCraft". Het is de control layer waarmee meerdere voice-ecosystemen samen op een Minecraft-server kunnen werken.

## Wat Spelers Kunnen Gebruiken

| Spelerclient | Ondersteund via | Wat de plugin doet |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | Bindt de externe VoiceCraft-sessie aan de speler in Minecraft en werkt positie/effecten bij. |
| Simple Voice Chat | Paper adapter | Verwerkt plugin-message/UDP handshake, maakt een VoiceCraft entity en forwardt Opus audio. |
| Plasmo Voice | Paper adapter | Verwerkt het Plasmo TCP/UDP protocol, maakt een VoiceCraft entity en forwardt Opus audio. |
| Bedrock-spelers op Java cross-play servers | VoiceCraft client | Geeft Bedrock-spelers voicechat zonder Java client mods. |

Wanneer ze samen op Paper zijn ingeschakeld, worden Simple Voice Chat en Plasmo Voice clients verbonden met dezelfde VoiceCraft audio bridge. Een speler die via Simple Voice Chat spreekt, kan worden gehoord door Plasmo Voice- en VoiceCraft-clientgebruikers, en andersom.

## Belangrijkste Features

- Unified proximity voicechat voor VoiceCraft client, Simple Voice Chat en Plasmo Voice.
- Voicechat voor Bedrock-spelers op Java cross-play servers via de VoiceCraft client.
- Direct Paper mode voor single-server setups.
- Velocity en BungeeCord proxy mode voor multi-server netwerken.
- Managed VoiceCraft runtime op Paper: automatische download, startup, config sync en shutdown.
- Server-side player positioning, dimension tracking, proximity distance, cave echo en muffle updates.
- Binding flow voor externe VoiceCraft clients met korte in-game bind keys.
- Gelokaliseerde config/message templates: `en`, `ru`, `nl`, `ja`.
- Optionele PlaceholderAPI support op Paper.

## Hoe Het Werkt

### VoiceCraft Client Flow

Spelers met de VoiceCraft client verbinden met de VoiceCraft backend. VoiceCraft.Java bindt daarna die externe voice entity aan de Minecraft-speler.

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

Typische flow:

1. De speler verbindt met VoiceCraft.
2. VoiceCraft maakt een nieuwe network entity.
3. VoiceCraft.Java ontvangt die entity en kent een korte binding key toe.
4. De speler voert `/voice bind <key>` uit in Minecraft.
5. VoiceCraft.Java koppelt de voice entity aan de Minecraft-speler.
6. De plugin werkt continu world, position, rotation en voice effects van die entity bij.

Dit is de primaire route voor spelers die geen Java voice mods kunnen gebruiken, inclusief Bedrock-spelers.

### Simple Voice Chat en Plasmo Voice Flow

Op Paper kan VoiceCraft.Java als compatibility server voor Java voice mods werken.

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

De adapters spreken de clientprotocollen, beheren UDP-sessies en sturen Opus frames naar de gedeelde VoiceCraft audio bridge. Audio die terugkomt van VoiceCraft wordt naar de geregistreerde adapters gestuurd, zodat spelers op verschillende clients aan hetzelfde gesprek kunnen deelnemen.

## Runtime Modes

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

Gebruik dit voor een enkele Paper-server.

De Paper-plugin beheert de VoiceCraft-verbinding. Hij kan verbinden met een bestaande VoiceCraft backend of automatisch de VoiceCraft runtime downloaden en starten.

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

Gebruik dit voor netwerken met meerdere backend servers.

Installeer VoiceCraft.Java op elke Paper backend en op de proxy. De proxy beheert de VoiceCraft-verbinding, terwijl Paper backends player snapshots en bind/unbind requests versturen via het `voicecraft-java:main` plugin channel.

## Platform Support

| Platform | Rol |
| --- | --- |
| Paper | Direct runtime, backend runtime, managed VoiceCraft process, Simple Voice Chat adapter, Plasmo Voice adapter |
| Velocity | Proxy runtime en centrale VoiceCraft-verbinding |
| BungeeCord | Proxy runtime en centrale VoiceCraft-verbinding |

## Vereisten

- Java 21.
- Paper, Velocity of BungeeCord.
- VoiceCraft backend, tenzij Paper managed runtime is ingeschakeld.
- Bereikbare UDP-poorten voor ingeschakelde Simple Voice Chat of Plasmo Voice adapters.

Het project wordt momenteel gebouwd tegen:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Installatie

1. Download of build de jar voor je platform.
2. Plaats hem in de `plugins` directory van de server.
3. Start de server een keer om `config.yml` te genereren.
4. Configureer de VoiceCraft-verbinding en de benodigde adapters.
5. Herstart de server of voer `/voice reload` uit.

Build outputs:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
```

Gebruik de shaded jars zonder `thin` of `sources` classifier.

## Configuratie

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

Zet `auto-start: true` als de Paper-plugin VoiceCraft moet beheren. Zet het op `false` als je VoiceCraft zelf runt.

### Paper Backend Behind Proxy

```yaml
config:
  proxy:
    enabled: true
```

In backend mode verbindt Paper niet direct met VoiceCraft. Het stuurt Minecraft player state door naar Velocity of BungeeCord.

### Velocity of BungeeCord

```yaml
config:
  voicecraft:
    transport:
      host: "127.0.0.1"
      port: 9050
      login-token: "change-me"
```

De proxy-plugin beheert de VoiceCraft-verbinding voor het hele netwerk.

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

| Command | Permission | Beschrijving |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | Slaat verbindingsinstellingen op en verbindt met VoiceCraft. |
| `/voice reconnect [true\|false]` | `voice.reconnect` | Verbindt opnieuw met VoiceCraft. Gebruik `true` om reconnect te forceren. |
| `/voice disconnect` | `voice.disconnect` | Verbreekt de verbinding met VoiceCraft. |
| `/voice bind <key>` | `voice.bind` | Bindt de huidige Minecraft-speler aan een VoiceCraft client entity. |
| `/voice bindfake <key> <name>` | `voice.bindfake` | Bindt een VoiceCraft entity aan een fake player name. |
| `/voice reload` | `voice.reload` | Herlaadt config en past settings toe. |

## Projectstructuur

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

## Builden

```bash
./gradlew clean build
```

Op Windows:

```powershell
.\gradlew.bat clean build
```

Tests uitvoeren:

```bash
./gradlew test
```

## Links

- Project site: https://java.voicecraft.chat
- Wiki / Docs: https://java.voicecraft.chat/docs
- Contribution guide: https://java.voicecraft.chat/docs/contribution

## Third-Party Projects

VoiceCraft.Java implementeert compatibility layers voor deze voicechat-projecten. Het zijn aparte upstream projecten met eigen repositories en licenties.

| Project | Repository | License |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## Licentie

VoiceCraft.Java is gelicentieerd onder de GNU Affero General Public License v3.0 or later. Zie [LICENSE](LICENSE) voor details.
