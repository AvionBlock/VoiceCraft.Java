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

На Paper VoiceCraft.Java может выступать compatibility server для Java voice mods.

```text
Simple Voice Chat client -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
Plasmo Voice client       -> VoiceCraft.Java Paper adapter -> VoiceCraft audio bridge
VoiceCraft client         -> VoiceCraft backend            -> VoiceCraft audio bridge
```

Адаптеры говорят на протоколах клиентов, управляют UDP-сессиями и передают Opus frames в общий VoiceCraft audio bridge. Аудио, приходящее обратно из VoiceCraft, рассылается зарегистрированным адаптерам, поэтому игроки на разных клиентах могут участвовать в одном разговоре.

Общий контракт совместимости охватывает proximity-аудио, identity, speaking lifecycle и голосовые состояния игроков. Эксклюзивные функции отдельных клиентов, например приватные группы Simple Voice Chat, остаются внутри своей экосистемы и намеренно не транслируются между разными клиентами. Не устанавливайте оригинальный серверный плагин SVC или Plasmo на тот же Paper-сервер, если соответствующий адаптер VoiceCraft.Java уже владеет его портом.

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

Аудио Simple Voice Chat и Plasmo Voice в proxy mode остается UDP. Paper регистрирует voice routes через `voicecraft-java:main`, а Velocity/BungeeCord открывает публичные UDP relay-порты и пересылает сырые voice datagrams на текущий backend Paper adapter.

## Поддержка платформ

| Платформа | Роль |
| --- | --- |
| Paper | Direct runtime, backend runtime, управляемый процесс VoiceCraft Server, адаптер Simple Voice Chat, адаптер Plasmo Voice |
| Velocity | Proxy runtime, центральное подключение к VoiceCraft, UDP relay для Simple Voice Chat / Plasmo |
| BungeeCord | Proxy runtime, центральное подключение к VoiceCraft, UDP relay для Simple Voice Chat / Plasmo |

## Требования

- Java 21.
- Paper, Velocity или BungeeCord.
- VoiceCraft Server 1.7 backend, если управляемый runtime Paper не включен.
- Доступные UDP-порты для включенных адаптеров Simple Voice Chat или Plasmo Voice.

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

### Proxy UDP Relay

Включите это на Velocity или BungeeCord, когда Paper backends рекламируют proxy как публичный endpoint для Plasmo/Simple Voice Chat:

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

Для proxy-сетей настройте `public.host` и `public.port` в Paper-адаптере на адрес и relay port прокси. Paper-адаптер по-прежнему владеет protocol session; proxy только маршрутизирует raw UDP packets на backend, который зарегистрировал route.

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

### Адаптер Simple Voice Chat

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

### Адаптер Plasmo Voice

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

`bind-host` задаёт локальный сокет. `public.host` отправляется клиенту Plasmo; значение `0.0.0.0` намеренно используется по умолчанию для direct Paper. В proxy mode укажите доступный адрес прокси и relay-порт в `public.port`.

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
  adapter-simplevoicechat-protocol/ Поддержка packet/session protocol Simple Voice Chat
  adapter-plasmo-protocol/          Поддержка packet/session protocol Plasmo Voice
  paper/                            Paper-плагин, managed runtime, адаптеры, backend mode
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
