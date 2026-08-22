# VoiceCraft.Java

[English](README.md) | [Русский](README.ru.md) | [Nederlands](README.nl.md) | [日本語](README.ja.md)

**VoiceCraft.Java — ультимативный плагин межклиентского proximity voice chat, объединяющий VoiceCraft, Simple Voice Chat и Plasmo Voice на Java и Bedrock.**

VoiceCraft.Java — плагин совместимости, который объединяет всех этих клиентов в одном голосовом пространстве Java Minecraft-сервера.

Он превращает VoiceCraft в общий серверный голосовой слой, где игроки с разными голосовыми клиентами находятся в одном пространстве proximity voice. Пользователи Simple Voice Chat, Plasmo Voice и игроки, подключенные через VoiceCraft client, могут слышать друг друга и говорить через один VoiceCraft backend.

Это особенно полезно для cross-play серверов: Bedrock-игроки могут заходить через Geyser и использовать отдельный VoiceCraft client, а Java-игроки — продолжать пользоваться Simple Voice Chat или Plasmo Voice. Игроки без этих Java-модов получают небольшой vanilla-совместимый статус, который виден и через Geyser.

## Идея

VoiceCraft - это аудиодвижок. VoiceCraft.Java - Minecraft-side плагин, который помогает этому движку понимать состояние вашего сервера.

Плагин отвечает за:

- подключение Paper, Velocity или BungeeCord к VoiceCraft backend;
- создание VoiceCraft сущностей и их привязку к Minecraft игрокам;
- синхронизацию имен, миров, позиций, поворота, proximity, cave echo и muffle-состояния;
- маршрутизацию аудио между поддерживаемыми клиентами через VoiceCraft audio bridge;
- поддержку одиночных Paper-серверов и multi-server proxy-сетей.

На практике VoiceCraft.Java - это не просто мост к VoiceCraft. Это control layer, который позволяет нескольким voice-экосистемам работать вместе на одном Minecraft-сервере.

## Что могут использовать игроки

| Клиент игрока | Поддерживается через | Что делает плагин |
| --- | --- | --- |
| VoiceCraft client | VoiceCraft backend + `/voice bind` | Привязывает внешнюю VoiceCraft сессию к игроку в Minecraft и обновляет позицию/эффекты. |
| Simple Voice Chat | Paper adapter | Обрабатывает plugin-message/UDP handshake, создает VoiceCraft-сущность и пересылает Opus-аудио. |
| Plasmo Voice | Paper adapter | Обрабатывает Plasmo TCP/UDP protocol, создает VoiceCraft-сущность и пересылает Opus-аудио. |
| Bedrock-игроки на Java cross-play серверах | VoiceCraft client | Дает Bedrock-игрокам голосовой чат без Java client mods. |

Если адаптеры включены вместе на Paper, клиенты Simple Voice Chat и Plasmo Voice подключаются к одному VoiceCraft audio bridge. Игрока из Simple Voice Chat слышат игроки Plasmo Voice и VoiceCraft client, и наоборот.

## Основные возможности

- Единый proximity voice chat для VoiceCraft client, Simple Voice Chat и Plasmo Voice.
- Голосовой чат для Bedrock-игроков на Java cross-play серверах через VoiceCraft client.
- Direct Paper mode для одиночных серверов.
- Velocity и BungeeCord proxy mode для multi-server сетей.
- Proxy UDP relay для Simple Voice Chat и Plasmo Voice в Velocity/BungeeCord сетях.
- Управляемый VoiceCraft Server 1.7 рантайм на Paper: автоматическая загрузка, запуск, синхронизация runtime options и остановка.
- Server-side позиционирование игроков, отслеживание dimension, proximity distance, cave echo и muffle updates.
- Binding flow для внешних VoiceCraft-клиентов с короткими in-game bind keys.
- Персональная vanilla-индикация для Java- и Bedrock-игроков без модов; клиенты с SVC/Plasmo видят только родной интерфейс без дублирования.
- Переводы сообщений для `en`, `de`, `ru`, `nl`, `pl`, `ja`, `th`, `zh_CN` и `zh_TW`; локализованные шаблоны конфига для `en`, `ru`, `nl` и `ja`.

## Как это работает

### Поток VoiceCraft Client

Игроки с VoiceCraft client подключаются к VoiceCraft backend. Затем VoiceCraft.Java привязывает внешнюю голосовую сущность к Minecraft-игроку.

```text
VoiceCraft client -> VoiceCraft backend <- VoiceCraft.Java <- Minecraft server
```

Типичный flow:

1. Игрок подключается к VoiceCraft.
2. VoiceCraft создает новую network entity.
3. VoiceCraft.Java получает эту entity и назначает короткий binding key.
4. Игрок выполняет `/voice bind <key>` в Minecraft.
5. VoiceCraft.Java связывает голосовую entity с Minecraft-игроком.
6. Плагин постоянно обновляет world, position, rotation и voice effects этой entity.

Это основной путь для игроков, которые не могут использовать Java voice mods, включая Bedrock-игроков.

### Поток Simple Voice Chat и Plasmo Voice

На Paper VoiceCraft.Java связывает официальные серверные плагины голосовых модов.

```text
Simple Voice Chat client -> официальный SVC -> мост VoiceCraft.Java -> VoiceCraft Server
Plasmo Voice client       -> официальный Plasmo -> мост VoiceCraft.Java -> VoiceCraft Server
VoiceCraft client         ------------------------------------> VoiceCraft Server
```

Официальные плагины отвечают за handshake клиентов, UDP-транспорт и API аддонов. VoiceCraft.Java получает итоговые серверные аудиособытия и передаёт Opus frames в VoiceCraft Server, который остаётся главным маршрутизатором между клиентами. Обратный звук внедряется через официальные API.

Общий контракт сохраняет player/entity/position/direct/broadcast источники и выбор получателей. При включении моста VoiceCraft.Java может скачать недостающий официальный плагин с Modrinth; после установки нужен перезапуск. Эмуляции сетевых протоколов больше нет.

## Режимы работы

### Direct Paper Mode

```text
Paper -> VoiceCraft.Java -> VoiceCraft
```

Используйте для одиночного Paper-сервера.

Paper-плагин владеет подключением к VoiceCraft. Он может подключиться к уже запущенному VoiceCraft backend или автоматически скачать и запустить VoiceCraft runtime.

### Proxy Mode

```text
Paper backends -> Velocity/BungeeCord -> VoiceCraft
```

Используйте для сетей с несколькими backend-серверами.

Установите VoiceCraft.Java на каждый Paper backend и на proxy. Proxy владеет подключением к VoiceCraft, а Paper backends отправляют player snapshots и bind/unbind requests через plugin channel `voicecraft-java:main`.

Simple Voice Chat и Plasmo Voice используют собственную официальную настройку proxy/network. VoiceCraft.Java больше не открывает сырые UDP relay-порты на Velocity или BungeeCord.

## Поддержка платформ

| Платформа | Роль |
| --- | --- |
| Paper | Direct/backend runtime, управляемый VoiceCraft Server, нативные мосты SVC и Plasmo |
| Fabric | Серверный runtime, управляемый VoiceCraft Server, нативные мосты модов SVC и Plasmo |
| Velocity | Proxy runtime и центральное подключение к VoiceCraft |
| BungeeCord | Proxy runtime и центральное подключение к VoiceCraft |

## Требования

- Java 21.
- Paper, Fabric, Velocity или BungeeCord.
- VoiceCraft Server 1.7 backend, если управляемый runtime не включён.
- Официальный серверный плагин Simple Voice Chat и/или Plasmo Voice для включённых мостов (доступна автоустановка).

Проект сейчас собирается с:

- Paper API `1.21.11-R0.1-SNAPSHOT`
- Velocity API `3.4.0-SNAPSHOT`
- BungeeCord API `26.1-R0.1-SNAPSHOT`

## Установка

1. Скачайте или соберите jar для вашей платформы.
2. Поместите его в директорию `plugins` сервера.
3. Запустите сервер один раз, чтобы сгенерировать `config.yml`.
4. Настройте подключение к VoiceCraft и нужные адаптеры.
5. Перезапустите сервер или выполните `/voice reload`.

Оба адаптера по умолчанию выключены. После включения VoiceCraft.Java становится серверной точкой подключения для соответствующего клиентского мода, поэтому его UDP-порт должен быть доступен игрокам и не должен быть занят оригинальным voice-chat плагином.

Артефакты сборки:

```text
modules/paper/build/libs/VoiceCraft.Java-paper-<version>.jar
modules/velocity/build/libs/VoiceCraft.Java-velocity-<version>.jar
modules/bungeecord/build/libs/VoiceCraft.Java-bungeecord-<version>.jar
```

Используйте shaded jars без классификаторов `thin` или `sources`.

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

Установите `auto-start: true`, если Paper-плагин должен управлять VoiceCraft. Установите `false`, если запускаете VoiceCraft самостоятельно. Управляемый launcher запускает `VoiceCraft.Server` с включенным McTcp и передает настроенные transport host, transport port, login token и VoiceCraft voice port как runtime options.

### Paper Backend за Proxy

```yaml
config:
  proxy:
    enabled: true
```

В backend mode Paper не подключается к VoiceCraft напрямую. Он пересылает состояние Minecraft-игроков на Velocity или BungeeCord.

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

### Настройки голоса

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

Vanilla overlay использует невидимые по умолчанию ArmorStand вместо display entities. Статус показывается только тогда, когда и наблюдатель, и цель не используют SVC/Plasmo, поэтому он не перекрывает родную индикацию модов. Символы, цвета, высота, интервал обновления, время удержания speaking и дистанция отображения настраиваются.

### Нативные голосовые мосты

```yaml
config:
  adapters:
    client-priority: ["simple-voice-chat", "plasmo"]
    managed-dependencies:
      enabled: true
    simple-voice-chat:
      enabled: true
      install:
        enabled: true
    plasmo:
      enabled: true
      install:
        enabled: true
```

Если у игрока стоят оба клиентских мода, `client-priority` выбирает один транспорт и предотвращает дублирование звука. На Paper зависимости устанавливаются в `plugins/`, на Fabric — в `mods/`; для активации нужен перезапуск. Портами и транспортом владеют официальные плагины/моды.

## Команды

| Команда | Разрешение | Описание |
| --- | --- | --- |
| `/voice connect <host> <port> <login-token>` | `voice.connect` | Сохранить настройки подключения и подключиться к VoiceCraft. |
| `/voice reconnect [true\|false]` | `voice.reconnect` | Переподключиться к VoiceCraft. Используйте `true` для force reconnect. |
| `/voice disconnect` | `voice.disconnect` | Отключиться от VoiceCraft. |
| `/voice bind <key>` | `voice.bind` | Привязать текущего Minecraft-игрока к VoiceCraft client entity. |
| `/voice bindfake <key> <name>` | `voice.bindfake` | Привязать VoiceCraft entity к fake player name. |
| `/voice reload` | `voice.reload` | Перезагрузить config и применить настройки. |

## Структура проекта

```text
modules/
  core/                             Общий код протокола VoiceCraft, сессий, proxy, config и локализации
  adapter-api/                      Общие контракты адаптеров для аудио и идентичности сущностей
  paper/                            Paper-плагин, managed runtime, нативные API-мосты, backend mode
  velocity/                         Proxy-плагин Velocity
  bungeecord/                       Proxy-плагин BungeeCord
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

- Сайт проекта: https://java.voicecraft.chat
- Wiki / документация: https://java.voicecraft.chat/docs
- Руководство для контрибьюторов: https://java.voicecraft.chat/docs/contribution

## Сторонние проекты

VoiceCraft.Java реализует compatibility layers для этих voice chat проектов. Это отдельные upstream-проекты со своими репозиториями и лицензиями.

| Проект | Репозиторий | Лицензия |
| --- | --- | --- |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat | Copyright (c) Max Henkel - All Rights Reserved |
| Plasmo Voice | https://github.com/plasmoapp/plasmo-voice | LGPL-3.0 |

## Лицензия

VoiceCraft.Java распространяется по лицензии GNU Affero General Public License v3.0 or later. Подробности см. в [LICENSE](LICENSE).
