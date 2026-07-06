# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

VoiceCraft.Java - ультимативный плагин голосового чата для Java Minecraft серверов.

Он превращает VoiceCraft в общий серверный голосовой слой, где игроки с разными голосовыми клиентами могут находиться в одном proximity voice пространстве. Пользователи Simple Voice Chat, Plasmo Voice и игроки, подключенные через VoiceCraft client, могут слышать и говорить друг с другом через один backend.

Это особенно полезно для cross-play серверов: Bedrock игроки могут заходить на Java сервер и использовать VoiceCraft client для голосового чата, а Java игроки могут продолжать пользоваться привычными клиентами вроде Simple Voice Chat или Plasmo Voice.

## Идея

VoiceCraft - это аудио-движок. VoiceCraft.Java - Minecraft-side плагин, который помогает этому движку понимать ваш сервер.

Плагин отвечает за:

- подключение Paper, Velocity или BungeeCord к VoiceCraft backend;
- создание VoiceCraft сущностей и их привязку к Minecraft игрокам;
- синхронизацию имен, миров, позиций, поворота, proximity, cave echo и muffle состояния;
- маршрутизацию аудио между поддерживаемыми клиентами через VoiceCraft audio bridge;
- поддержку одиночных Paper серверов и multi-server proxy сетей.

На практике VoiceCraft.Java - это не просто мост к VoiceCraft. Это control layer, который позволяет нескольким voice-экосистемам работать вместе на одном Minecraft сервере.

## Что могут использовать игроки

| Клиент игрока | Поддерживается через | Что делает плагин |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | Привязывает внешнюю VoiceCraft сессию к игроку в Minecraft и обновляет позицию/эффекты. |
| Simple Voice Chat | Paper adapter | Обрабатывает plugin-message/UDP handshake, создает VoiceCraft сущность и пересылает Opus аудио. |
| Plasmo Voice | Paper adapter | Обрабатывает Plasmo TCP/UDP protocol, создает VoiceCraft сущность и пересылает Opus аудио. |
| Bedrock игроки на Java cross-play серверах | VoiceCraft client | Дает Bedrock игрокам голосовой чат без Java client mods. |

Если адаптеры включены вместе на Paper, клиенты Simple Voice Chat и Plasmo Voice подключаются к одному VoiceCraft audio bridge. Игрок из Simple Voice Chat может быть услышан игроками Plasmo Voice и VoiceCraft client, и наоборот.

## Основные возможности

- Единый proximity voice chat для VoiceCraft client, Simple Voice Chat и Plasmo Voice.
- Голосовой чат для Bedrock игроков на Java cross-play серверах через VoiceCraft client.
- Direct Paper mode для одиночных серверов.
- Velocity и BungeeCord proxy mode для multi-server сетей.
- Managed VoiceCraft runtime на Paper: автоматическая загрузка, запуск, синхронизация конфигурации и остановка.
- Server-side позиционирование игроков, отслеживание dimension, proximity distance, cave echo и muffle updates.
- Binding flow для внешних VoiceCraft клиентов с короткими in-game bind keys.
- Локализованные config/message templates: `en`, `ru`, `nl`, `ja`.
- Optional PlaceholderAPI support на Paper.

## Как это работает

### VoiceCraft Client Flow

Игроки с VoiceCraft client подключаются к VoiceCraft backend. Затем VoiceCraft.Java привязывает внешнюю голосовую сущность к Minecraft игроку.

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

Типичный flow:

1. Игрок подключается к VoiceCraft.
2. VoiceCraft создает новую network entity.
3. VoiceCraft.Java получает эту entity и назначает короткий binding key.
4. Игрок выполняет `/voice bind <key>` в Minecraft.
5. VoiceCraft.Java связывает голосовую entity с Minecraft игроком.
6. Плагин постоянно обновляет world, position, rotation и voice effects этой entity.

Это основной путь для игроков, которые не могут использовать Java voice mods, включая Bedrock игроков.

### Simple Voice Chat и Plasmo Voice Flow

На Paper VoiceCraft.Java может выступать compatibility server для Java voice mods.

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

Адаптеры говорят на протоколах клиентов, управляют UDP сессиями и передают Opus frames в общий VoiceCraft audio bridge. Аудио, приходящее обратно из VoiceCraft, рассылается зарегистрированным адаптерам, поэтому игроки на разных клиентах могут участвовать в одном разговоре.

## Runtime Modes

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

Используйте для одиночного Paper сервера.

Paper плагин владеет подключением к VoiceCraft. Он может подключиться к уже запущенному VoiceCraft backend или автоматически скачать и запустить VoiceCraft runtime.

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

Используйте для сетей с несколькими backend серверами.

Установите VoiceCraft.Java на каждый Paper backend и на proxy. Proxy владеет подключением к VoiceCraft, а Paper backends отправляют player snapshots и bind/unbind requests через plugin channel `voicecraft-java:main`.

## Поддержка платформ

| Платформа | Роль |
| --- | --- |
| Paper | Direct runtime, backend runtime, managed VoiceCraft process, Simple Voice Chat adapter, Plasmo Voice adapter |
| Velocity | Proxy runtime и центральное подключение к VoiceCraft |
| BungeeCord | Proxy runtime и центральное подключение к VoiceCraft |

## Требования

- Java 21.
- Paper, Velocity или BungeeCord.
- VoiceCraft backend, если Paper managed runtime не включен.
- Доступные UDP порты для включенных Simple Voice Chat или Plasmo Voice adapters.

Проект сейчас собирается против:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Установка

1. Скачайте или соберите jar для вашей платформы.
2. Поместите его в директорию `plugins` сервера.
3. Запустите сервер один раз, чтобы сгенерировать `config.yml`.
4. Настройте подключение к VoiceCraft и нужные адаптеры.
5. Перезапустите сервер или выполните `/voice reload`.

Build outputs:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
```

Используйте shaded jars без classifier `thin` или `sources`.

## Конфигурация

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

Установите `auto-start: true`, если Paper плагин должен управлять VoiceCraft. Установите `false`, если запускаете VoiceCraft самостоятельно.

### Paper Backend Behind Proxy

```yaml
config:
  proxy:
    enabled: true
```

В backend mode Paper не подключается к VoiceCraft напрямую. Он пересылает состояние Minecraft игроков на Velocity или BungeeCord.

### Velocity или BungeeCord

```yaml
config:
  voicecraft:
    transport:
      host: "127.0.0.1"
      port: 9050
      login-token: "change-me"
```

Proxy плагин владеет подключением к VoiceCraft для всей сети.

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

## Команды

| Команда | Permission | Описание |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | Сохранить настройки подключения и подключиться к VoiceCraft. |
| `/voice reconnect [true\|false]` | `voice.reconnect` | Переподключиться к VoiceCraft. Используйте `true` для force reconnect. |
| `/voice disconnect` | `voice.disconnect` | Отключиться от VoiceCraft. |
| `/voice bind <key>` | `voice.bind` | Привязать текущего Minecraft игрока к VoiceCraft client entity. |
| `/voice bindfake <key> <name>` | `voice.bindfake` | Привязать VoiceCraft entity к fake player name. |
| `/voice reload` | `voice.reload` | Перезагрузить config и применить settings. |

## Структура проекта

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

## Сборка

```bash
./gradlew clean build
```

На Windows:

```powershell
.\gradlew.bat clean build
```

Запуск тестов:

```bash
./gradlew test
```

## Ссылки

- Project site: https://java.voicecraft.chat
- Wiki / Docs: https://java.voicecraft.chat/docs
- Contribution guide: https://java.voicecraft.chat/docs/contribution

## Third-Party Projects

VoiceCraft.Java реализует compatibility layers для этих voice chat проектов. Это отдельные upstream проекты со своими репозиториями и лицензиями.

| Project | Repository | License |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## Лицензия

VoiceCraft.Java распространяется по лицензии GNU Affero General Public License v3.0 or later. Подробности см. в [LICENSE](LICENSE).
