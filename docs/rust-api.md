# Rust API mesh-core

## 1. Назначение

Данный документ описывает публичный API Rust-ядра `mesh-core`, которое используется Android-приложением через UniFFI.

Rust-ядро отвечает только за mesh-логику:

- создание сообщений;
- сериализацию сообщений в байты;
- обработку входящих байтов;
- проверку дубликатов;
- TTL;
- пересылку сообщений;
- store-and-forward;
- хранение pending-сообщений;
- учёт подключённых peer-ов.

Rust-ядро не отвечает за:

- Bluetooth;
- поиск устройств;
- подключение к устройствам;
- UI;
- разрешения Android;
- хранение истории в базе Android-приложения.

Android-приложение получает байты от Bluetooth, передаёт их в Rust, а Rust возвращает список действий, которые Android должен выполнить.

---

## 2. Общая схема взаимодействия

```text
Пользователь вводит текст
        ↓
Android вызывает Rust create_private_text_bytes(...)
        ↓
Rust создаёт сообщение и возвращает ByteArray
        ↓
Android отправляет ByteArray по Bluetooth
        ↓
Другое устройство получает ByteArray
        ↓
Android вызывает Rust handle_incoming_bytes(...)
        ↓
Rust возвращает список ApiAction
        ↓
Android выполняет действия:
- показать сообщение
- переслать сообщение
- сохранить сообщение
- проигнорировать сообщение
```

---

## 3. Главный объект API

Основной объект:

```text
MeshCoreApi
```

Каждое устройство должно создать один экземпляр `MeshCoreApi` для своего локального узла.

Пример:

```kotlin
val meshCore = MeshCoreApi("node_a")
```

Точное имя конструктора нужно проверить в сгенерированном файле `mesh_core.kt`, так как UniFFI может немного отличаться в формате вызова.

---

## 4. Node ID

`node_id` — это уникальный идентификатор устройства или пользователя внутри mesh-сети.

Примеры:

```text
node_a
node_b
node_c
```

В текущем прототипе `node_id` передаётся как обычная строка.

Важно: в MVP `node_id` не защищён криптографически. Любое устройство теоретически может назвать себя чужим ID. Это ограничение прототипа.

---

## 5. Создание MeshCoreApi

### Метод

```text
MeshCoreApi::new(node_id: String)
```

### Назначение

Создаёт Rust-ядро для текущего устройства.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `node_id` | `String` | ID текущего устройства |

### Возвращает

Экземпляр `MeshCoreApi`.

### Пример

```kotlin
val core = MeshCoreApi("node_b")
```

---

## 6. Создание личного текстового сообщения

### Метод

```text
create_private_text_bytes(to, text, timestamp)
```

### Назначение

Создаёт личное текстовое сообщение для конкретного получателя и возвращает готовые байты для отправки по Bluetooth.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `to` | `String` | ID получателя |
| `text` | `String` | Текст сообщения |
| `timestamp` | `u64 / Long` | Время создания сообщения |

### Возвращает

```text
ApiBytesResult
```

Структура `ApiBytesResult` содержит:

| Поле | Тип | Описание |
|---|---|---|
| `bytes` | `ByteArray` | Готовые байты сообщения |
| `error` | `String?` | Текст ошибки, если сообщение не удалось создать |

### Логика обработки результата

Если `error == null`, можно отправлять `bytes`.

Если `error != null`, отправлять `bytes` нельзя.

### Пример

```kotlin
val result = core.createPrivateTextBytes(
    to = "node_c",
    text = "Привет",
    timestamp = System.currentTimeMillis()
)

if (result.error == null) {
    bluetoothTransport.send(peerId, result.bytes)
} else {
    println("Rust error: ${result.error}")
}
```

---

## 7. Создание broadcast-сообщения

### Метод

```text
create_broadcast_text_bytes(text, timestamp)
```

### Назначение

Создаёт текстовое сообщение для всех доступных узлов.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `text` | `String` | Текст сообщения |
| `timestamp` | `u64 / Long` | Время создания сообщения |

### Возвращает

```text
ApiBytesResult
```

### Пример

```kotlin
val result = core.createBroadcastTextBytes(
    text = "Всем привет",
    timestamp = System.currentTimeMillis()
)

if (result.error == null) {
    for (peerId in connectedPeers) {
        bluetoothTransport.send(peerId, result.bytes)
    }
}
```

---

## 8. Обработка входящих байтов

### Метод

```text
handle_incoming_bytes(from_peer_id, bytes)
```

### Назначение

Передаёт Rust-ядру входящее сообщение, полученное по Bluetooth.

Rust декодирует байты, проверяет дубликаты, TTL, получателя и возвращает список действий.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `from_peer_id` | `String` | Peer, от которого Android получил байты |
| `bytes` | `ByteArray` | Полученные байты |

### Возвращает

```text
List<ApiAction>
```

### Пример

```kotlin
val actions = core.handleIncomingBytes(
    fromPeerId = peerId,
    bytes = receivedBytes
)

for (action in actions) {
    when (action) {
        is ApiAction.ShowMessage -> {
            // показать сообщение пользователю
        }

        is ApiAction.ForwardMessage -> {
            // переслать bytes указанным targetPeerIds
        }

        is ApiAction.DropMessage -> {
            // ничего не делать
        }

        is ApiAction.Error -> {
            // залогировать ошибку
        }
    }
}
```

---

## 9. ApiAction

`ApiAction` — это действие, которое Rust возвращает Android-приложению.

Возможные варианты:

```text
ShowMessage
ForwardMessage
DropMessage
Error
```

---

## 10. ShowMessage

### Назначение

Сообщение предназначено текущему устройству или является broadcast-сообщением, которое нужно показать пользователю.

### Поля

| Поле | Тип | Описание |
|---|---|---|
| `message` | `ApiChatMessage` | Сообщение для отображения |

### ApiChatMessage

| Поле | Тип | Описание |
|---|---|---|
| `message_id` | `String` | Уникальный ID сообщения |
| `chat_type` | `String` | `private` или `broadcast` |
| `from` | `String` | Отправитель |
| `to` | `String?` | Получатель, если есть |
| `text` | `String` | Текст сообщения |
| `timestamp` | `Long` | Время создания сообщения |

---

## 11. ForwardMessage

### Назначение

Сообщение нужно переслать дальше по mesh-сети.

### Поля

| Поле | Тип | Описание |
|---|---|---|
| `target_peer_ids` | `List<String>` | Список peer-ов, которым нужно отправить сообщение |
| `bytes` | `ByteArray` | Байты сообщения для пересылки |

### Важно

Android не должен изменять `bytes`.

Rust уже уменьшил TTL и подготовил сообщение к дальнейшей отправке.

### Пример

```kotlin
is ApiAction.ForwardMessage -> {
    for (targetPeerId in action.targetPeerIds) {
        bluetoothTransport.send(targetPeerId, action.bytes)
    }
}
```

---

## 12. DropMessage

### Назначение

Сообщение нужно проигнорировать.

Возможные причины:

- сообщение уже было обработано раньше;
- это дубликат;
- сообщение не требует действий.

### Пример

```kotlin
is ApiAction.DropMessage -> {
    // ничего не делаем
}
```

---

## 13. Error

### Назначение

Rust не смог обработать входные данные.

Возможные причины:

- пришли битые байты;
- сообщение не является корректным JSON;
- формат сообщения не соответствует протоколу.

### Поля

| Поле | Тип | Описание |
|---|---|---|
| `message` | `String` | Текст ошибки |

### Пример

```kotlin
is ApiAction.Error -> {
    println("Rust error: ${action.message}")
}
```

---

## 14. Подключение peer-а

### Метод

```text
mark_peer_connected(peer_id)
```

### Назначение

Сообщает Rust-ядру, что peer сейчас доступен для отправки сообщений.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `peer_id` | `String` | ID подключённого peer-а |

### Пример

```kotlin
core.markPeerConnected("node_c")
```

### Когда вызывать

Вызывать после успешного Bluetooth-подключения или после того, как приложение уверено, что peer доступен для передачи данных.

---

## 15. Отключение peer-а

### Метод

```text
mark_peer_disconnected(peer_id)
```

### Назначение

Сообщает Rust-ядру, что peer больше недоступен.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `peer_id` | `String` | ID отключённого peer-а |

### Пример

```kotlin
core.markPeerDisconnected("node_c")
```

---

## 16. Получение pending-сообщений для peer-а

### Метод

```text
take_pending_bytes_for_peer(peer_id)
```

### Назначение

Возвращает сообщения, которые Rust ранее сохранил для недоступного peer-а.

После вызова сообщения удаляются из pending-очереди.

### Параметры

| Параметр | Тип | Описание |
|---|---|---|
| `peer_id` | `String` | ID peer-а, для которого нужно получить pending-сообщения |

### Возвращает

```text
List<ByteArray>
```

### Пример

```kotlin
val pendingMessages = core.takePendingBytesForPeer("node_c")

for (bytes in pendingMessages) {
    bluetoothTransport.send("node_c", bytes)
}
```

### Когда вызывать

Обычно после:

```kotlin
core.markPeerConnected("node_c")
```

Примерный порядок:

```kotlin
core.markPeerConnected("node_c")

val pendingMessages = core.takePendingBytesForPeer("node_c")

for (bytes in pendingMessages) {
    bluetoothTransport.send("node_c", bytes)
}
```

---

## 17. Получение списка подключённых peer-ов

### Метод

```text
connected_peers()
```

### Назначение

Возвращает список peer-ов, которые Rust считает подключёнными.

### Возвращает

```text
List<String>
```

### Пример

```kotlin
val peers = core.connectedPeers()
```

---

## 18. Служебные методы для отладки

### seen_messages_count

```text
seen_messages_count()
```

Возвращает количество сообщений, которые Rust уже видел.

Нужно для отладки дубликатов.

---

### connected_peers_count

```text
connected_peers_count()
```

Возвращает количество подключённых peer-ов.

---

### pending_messages_count

```text
pending_messages_count()
```

Возвращает общее количество pending-сообщений.

---

### pending_messages_count_for_peer

```text
pending_messages_count_for_peer(peer_id)
```

Возвращает количество pending-сообщений для конкретного peer-а.

---

## 19. Главный сценарий A → B → C

Минимальный сценарий MVP:

```text
A хочет отправить сообщение C.
A напрямую не видит C.
A видит B.
B видит C.
B пересылает сообщение от A к C.
C показывает сообщение пользователю.
```

Порядок работы:

```text
1. A создаёт сообщение через create_private_text_bytes("node_c", ...)
2. A отправляет bytes устройству B по Bluetooth.
3. B вызывает handle_incoming_bytes("node_a", bytes).
4. Rust на B возвращает ForwardMessage для node_c.
5. B отправляет bytes устройству C.
6. C вызывает handle_incoming_bytes("node_b", bytes).
7. Rust на C возвращает ShowMessage.
8. C показывает сообщение пользователю.
```

---

## 20. Сценарий store-and-forward

```text
A хочет отправить сообщение C.
A видит B.
B не видит C.
B сохраняет сообщение.
Позже B видит C.
B отдаёт pending-сообщение для C.
C получает сообщение.
```

Порядок работы:

```text
1. A создаёт сообщение для C.
2. A отправляет bytes на B.
3. B вызывает handle_incoming_bytes("node_a", bytes).
4. Rust на B сохраняет сообщение в pending.
5. Позже Android на B обнаруживает C.
6. Android вызывает mark_peer_connected("node_c").
7. Android вызывает take_pending_bytes_for_peer("node_c").
8. Android отправляет полученные bytes устройству C.
9. C вызывает handle_incoming_bytes("node_b", bytes).
10. C получает ShowMessage.
```

---

## 21. Ограничения текущего API

Текущая версия является MVP-прототипом.

Ограничения:

- нет настоящего шифрования;
- нет аутентификации пользователей;
- нет подтверждения доставки;
- нет повторной отправки потерянных сообщений;
- нет передачи изображений и видео;
- нет защиты от вредоносных узлов;
- `node_id` передаётся как обычная строка;
- pending-сообщения хранятся только в памяти;
- после перезапуска приложения состояние Rust-ядра теряется.

---

## 22. Ответственность Android-разработчика

Android-часть должна реализовать:

- поиск Bluetooth-устройств;
- подключение к устройствам;
- отправку `ByteArray`;
- получение `ByteArray`;
- вызов `handle_incoming_bytes`;
- выполнение `ApiAction`;
- отображение сообщений в UI;
- хранение истории сообщений, если нужно;
- запрос Android-разрешений для Bluetooth.

---

## 23. Ответственность Rust-разработчика

Rust-часть реализует:

- протокол сообщения;
- создание сообщения;
- обработку входящего сообщения;
- защиту от дубликатов;
- TTL;
- пересылку;
- store-and-forward;
- UniFFI API для Kotlin.

---

## 24. Что передаётся Kotlin-разработчику

Для интеграции нужны:

```text
mesh_core.kt
libmesh_core.so
```

`mesh_core.kt` — сгенерированный Kotlin-binding.

`libmesh_core.so` — native-библиотека, собранная под Android ABI.

Для реального телефона обычно нужен ABI:

```text
arm64-v8a
```

Для Android-эмулятора на ПК часто нужен:

```text
x86_64
```

---

## 25. Куда класть файлы в Android-проекте

Примерная структура:

```text
android-app/
└── app/
    └── src/
        └── main/
            ├── java/
            │   └── uniffi/
            │       └── mesh_core/
            │           └── mesh_core.kt
            │
            └── jniLibs/
                ├── arm64-v8a/
                │   └── libmesh_core.so
                │
                └── x86_64/
                    └── libmesh_core.so
```

Точное расположение `mesh_core.kt` зависит от `package`, указанного в начале сгенерированного файла.

Например, если в файле указано:

```kotlin
package uniffi.mesh_core
```

то логично положить его в:

```text
app/src/main/java/uniffi/mesh_core/mesh_core.kt
```

---

## 26. Gradle-зависимость

UniFFI Kotlin bindings используют JNA.

В `build.gradle.kts` Android-модуля нужно добавить зависимость:

```kotlin
dependencies {
    implementation("net.java.dev.jna:jna:5.12.0@aar")
}
```

Можно использовать более новую версию JNA, если она совместима с проектом.

---

## 27. Минимальная проверка интеграции

После добавления `mesh_core.kt` и `libmesh_core.so` Kotlin-разработчик может проверить Rust так:

```kotlin
val core = MeshCoreApi("node_a")

val result = core.createPrivateTextBytes(
    to = "node_b",
    text = "Привет из Kotlin",
    timestamp = System.currentTimeMillis()
)

println(result.error)
println(result.bytes.size)
```

Если `error == null` и `bytes.size > 0`, значит Kotlin успешно вызвал Rust.
