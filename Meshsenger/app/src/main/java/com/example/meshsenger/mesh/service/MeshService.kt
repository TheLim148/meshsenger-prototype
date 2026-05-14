package com.example.meshsenger.mesh.service

import com.example.meshsenger.mesh.bluetooth.BluetoothTransport
import com.example.meshsenger.mesh.bluetooth.BluetoothTransportListener
import com.example.meshsenger.mesh.bridge.MeshCoreBridge
import com.example.meshsenger.mesh.contacts.ContactManager
import com.example.meshsenger.mesh.contacts.MeshContact
import com.example.meshsenger.mesh.logging.MeshLogger
import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.MeshAction
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import com.example.meshsenger.mesh.model.MessageTextCodec
import com.example.meshsenger.mesh.model.PeerUiModel
import com.example.meshsenger.mesh.repository.MessageRepository
import com.example.meshsenger.mesh.notifications.MessageNotifier
import com.example.meshsenger.mesh.persistence.PendingPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class MeshService(
    private val localNodeId: String,
    initialDisplayName: String = "Мой узел",
    initialAvatarEmoji: String = "🌿",
    private val onboardingDone: Boolean = false,
    private var autoMeshEnabled: Boolean = false,
    initialContacts: List<MeshContact> = emptyList(),
    initialPendingPackets: List<PendingPacket> = emptyList(),
    private val onDisplayNameSaved: (String) -> Unit = {},
    private val onAvatarEmojiSaved: (String) -> Unit = {},
    private val onAutoMeshEnabledSaved: (Boolean) -> Unit = {},
    private val onContactsSaved: (List<MeshContact>) -> Unit = {},
    private val onPendingPacketsSaved: (List<PendingPacket>) -> Unit = {},
    private val bluetoothTransport: BluetoothTransport,
    private val meshCoreBridge: MeshCoreBridge,
    private val messageRepository: MessageRepository,
    private val messageNotifier: MessageNotifier? = null,
) {
    val debugLogs = MeshLogger.logs

    val messages: StateFlow<List<ChatMessage>> = messageRepository.messages

    private val _peers = MutableStateFlow<List<PeerUiModel>>(emptyList())
    val peers: StateFlow<List<PeerUiModel>> = _peers.asStateFlow()

    private val _contacts = MutableStateFlow<List<MeshContact>>(emptyList())
    val contacts: StateFlow<List<MeshContact>> = _contacts.asStateFlow()

    private val _status = MutableStateFlow("Готово")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private var displayName: String = initialDisplayName.trim().ifBlank { "Мой узел" }
    private var avatarEmoji: String = normalizeAvatarEmoji(initialAvatarEmoji)
    private val seenDeliveryAckIds = linkedSetOf<String>()
    private val readAckSentMessageIds = linkedSetOf<String>()
    private val seenReadAckIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private var lastNodeAnnounceRebroadcastAtMillis: Long = 0L
    private val contactManager = ContactManager(
        localNodeId = localNodeId,
        initialContacts = initialContacts,
        onContactsChanged = { contacts ->
            _contacts.value = contacts
            onContactsSaved(contacts)
        },
    )
    private val pendingDirectByNodeId = linkedMapOf<String, MutableList<PendingEnvelope>>()
    private val pendingForwardByNodeId = linkedMapOf<String, MutableList<PendingEnvelope>>()
    private val pendingBroadcastById = linkedMapOf<String, PendingBroadcastEnvelope>()

    init {
        initialPendingPackets.forEach { packet ->
            val envelope = PendingEnvelope(
                messageId = packet.messageId,
                bytes = packet.bytes,
                // При восстановлении приложения считаем все pending-пакеты недоставленными.
                // Старые BLE peerId могли смениться или умереть, поэтому разрешаем повторную отправку.
                sentPeerIds = linkedSetOf(),
            )
            when (packet.kind) {
                PendingPacket.KIND_DIRECT -> pendingDirectByNodeId.getOrPut(packet.targetNodeId) { mutableListOf() }.add(envelope)
                PendingPacket.KIND_FORWARD -> pendingForwardByNodeId.getOrPut(packet.targetNodeId) { mutableListOf() }.add(envelope)
            }
        }
        if (initialPendingPackets.isNotEmpty()) {
            MeshLogger.info(TAG, "Loaded pending packets: ${initialPendingPackets.size}")
        }

        MeshLogger.info(TAG, "MeshService создан. localNodeId=$localNodeId")
        _contacts.value = contactManager.snapshot()
        bluetoothTransport.setListener(
            object : BluetoothTransportListener {
                override fun onPeerFound(peerId: String, name: String?) {
                    MeshLogger.info(TAG, "onPeerFound: peerId=$peerId, name=${name ?: "null"}")
                    contactManager.rememberDiscoveredEndpoint(peerId = peerId, displayName = name)
                    upsertPeer(peerId = peerId, name = name, nodeId = contactManager.nodeIdForPeer(peerId), isWritable = false)
                    _status.value = "Найден узел: ${name ?: peerId}"
                }

                override fun onPeerConnected(peerId: String) {
                    MeshLogger.info(TAG, "onPeerConnected: peerId=$peerId")
                    contactManager.markPeerWritable(peerId = peerId, isWritable = true)
                    upsertPeer(peerId = peerId, name = null, nodeId = contactManager.nodeIdForPeer(peerId), isWritable = true)
                    setPeerConnected(peerId = peerId, isConnected = true)
                    // В BLE peerId — это адрес/идентификатор транспорта, а mesh nodeId узнаётся
                    // только после hello/hello_ack. Поэтому Rust помечаем подключённым после handshake,
                    // чтобы не засорять mesh-core транспортными id.
                    sendControlHello(peerId = peerId, type = CONTROL_HELLO)
                    _status.value = "Подключено: $peerId"
                }

                override fun onPeerDisconnected(peerId: String) {
                    MeshLogger.info(TAG, "onPeerDisconnected: peerId=$peerId")
                    val nodeId = contactManager.knownNodeIdForPeer(peerId)

                    markPendingRetryAllowedForPeer(peerId)
                    contactManager.disconnectPeer(peerId)
                    setPeerConnected(peerId = peerId, isConnected = false)

                    if (nodeId != null) {
                        if (!contactManager.hasWritableNode(nodeId)) {
                            meshCoreBridge.markPeerDisconnected(peerId)
                        }
                        pendingDirectByNodeId[nodeId]?.forEach { envelope ->
                            envelope.messageId?.let { messageRepository.updateMessageStatus(it, MessageDeliveryStatus.Pending) }
                        }
                    } else {
                        MeshLogger.info(TAG, "Peer $peerId отключён до handshake; Rust не уведомляем, чтобы не сохранить BLE-адрес как node_id")
                    }

                    _status.value = "Отключено: $peerId"
                }

                override fun onBytesReceived(peerId: String, bytes: ByteArray) {
                    MeshLogger.info(TAG, "onBytesReceived: peerId=$peerId, bytes=${bytes.size}")
                    handleIncomingBytes(fromPeerId = peerId, bytes = bytes)
                }

                override fun onError(message: String) {
                    MeshLogger.warning(TAG, "Bluetooth event/error: $message")
                    if (message.contains("scan", ignoreCase = true) || message.contains("скан", ignoreCase = true)) {
                        _isScanning.value = false
                    }
                    if (
                        message.contains("advert", ignoreCase = true) ||
                        message.contains("приём", ignoreCase = true) ||
                        message.contains("Bluetooth выключен", ignoreCase = true) ||
                        message.contains("разреш", ignoreCase = true)
                    ) {
                        _isAdvertising.value = false
                    }
                    if (message.contains("Bluetooth выключен", ignoreCase = true)) {
                        _isScanning.value = false
                    }
                    _status.value = message
                }
            },
        )

        if (onboardingDone && autoMeshEnabled) {
            MeshLogger.info(TAG, "Auto mesh mode enabled, starting BLE scan/advertising")
            startScan()
        }
    }

    fun getLocalNodeId(): String = localNodeId

    fun getDisplayName(): String = displayName

    fun getAvatarEmoji(): String = avatarEmoji

    fun isOnboardingDone(): Boolean = onboardingDone

    fun setDisplayName(value: String) {
        displayName = value.trim().ifBlank { "Мой узел" }
        onDisplayNameSaved(displayName)
        MeshLogger.info(TAG, "setDisplayName($displayName)")
        broadcastNodeAnnounce()
    }

    fun setAvatarEmoji(value: String) {
        avatarEmoji = normalizeAvatarEmoji(value)
        onAvatarEmojiSaved(avatarEmoji)
        MeshLogger.info(TAG, "setAvatarEmoji($avatarEmoji)")
        broadcastNodeAnnounce()
    }

    fun startScan() {
        MeshLogger.info(TAG, "startScan()")
        autoMeshEnabled = true
        onAutoMeshEnabledSaved(true)
        runCatching {
            // Для mesh-режима телефон должен не только искать, но и сам быть видимым.
            // Если пользователь нажал только «Сканировать», автоматически поднимаем BLE advertising.
            // Иначе другой телефон будет искать нас, но не увидит.
            if (!_isAdvertising.value) {
                bluetoothTransport.startAdvertising()
                _isAdvertising.value = true
            }
            _isScanning.value = true
            _status.value = "BLE-поиск запущен"
            bluetoothTransport.startScan()
        }.onFailure { error ->
            _isScanning.value = false
            val message = "Краш/ошибка при запуске сканирования: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun stopScan() {
        MeshLogger.info(TAG, "stopScan()")
        runCatching {
            _isScanning.value = false
            bluetoothTransport.stopScan()
            _status.value = "BLE-поиск остановлен"
        }.onFailure { error ->
            val message = "Ошибка при остановке сканирования: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun startAdvertising() {
        MeshLogger.info(TAG, "startAdvertising()")
        runCatching {
            _isAdvertising.value = true
            _status.value = "Запуск BLE-приёма..."
            bluetoothTransport.startAdvertising()
        }.onFailure { error ->
            _isAdvertising.value = false
            val message = "Ошибка при запуске приёма: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun stopAdvertising() {
        MeshLogger.info(TAG, "stopAdvertising()")
        autoMeshEnabled = false
        onAutoMeshEnabledSaved(false)
        runCatching {
            _isAdvertising.value = false
            bluetoothTransport.stopAdvertising()
            _status.value = "BLE-приём остановлен"
        }.onFailure { error ->
            val message = "Ошибка при остановке приёма: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun connect(peerId: String) {
        MeshLogger.info(TAG, "connect(peerId=$peerId)")
        runCatching { bluetoothTransport.connect(peerId) }
            .onFailure { error ->
                val message = "Ошибка connect($peerId): ${error.localizedMessage ?: error.javaClass.simpleName}"
                MeshLogger.error(TAG, message, error)
                _status.value = message
            }
    }

    fun disconnect(peerId: String) {
        MeshLogger.info(TAG, "disconnect(peerId=$peerId)")
        runCatching { bluetoothTransport.disconnect(peerId) }
            .onFailure { error ->
                val message = "Ошибка disconnect($peerId): ${error.localizedMessage ?: error.javaClass.simpleName}"
                MeshLogger.error(TAG, message, error)
                _status.value = message
            }
    }

    fun sendPrivateTextMessage(toPeerId: String, text: String, replyTo: ChatMessage? = null) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            _status.value = "Нельзя отправить пустое сообщение"
            return
        }

        MeshLogger.info(TAG, "sendPrivateTextMessage(to=$toPeerId, textLength=${trimmedText.length})")
        runCatching {
            val toNodeId = when {
                toPeerId.startsWith("node_") -> toPeerId
                else -> contactManager.nodeIdForPeer(toPeerId).let { node ->
                    if (node == toPeerId) meshCoreBridge.resolveNodeIdForPeer(toPeerId) else node
                }
            }
            contactManager.rememberKnownNode(nodeId = toNodeId, displayName = null, discoveredVia = "manual_chat")

            val directPeerId = contactManager.peerIdForNode(toNodeId)
                ?.takeIf { peerId -> isPeerWritable(peerId) }
                ?: toPeerId.takeIf { !it.startsWith("node_") && isPeerWritable(it) }

            val encodedText = MessageTextCodec.encode(trimmedText, replyTo)
            val bytes = meshCoreBridge.createPrivateTextMessage(to = toNodeId, text = encodedText)
            val messageId = extractMessageId(bytes) ?: UUID.randomUUID().toString()
            val chatId = toNodeId

            messageRepository.saveMessage(
                ChatMessage(
                    id = messageId,
                    chatId = chatId,
                    from = localNodeId,
                    to = toNodeId,
                    text = trimmedText,
                    timestamp = nowMillis(),
                    isOutgoing = true,
                    status = MessageDeliveryStatus.Pending,
                    replyToMessageId = replyTo?.id,
                    replyToSender = replyTo?.from,
                    replyToText = replyTo?.text?.take(180),
                ),
            )

            enqueuePendingDirect(
                toNodeId = toNodeId,
                messageId = messageId,
                bytes = bytes,
                sentPeerId = null,
            )

            val sentPeers = sendEnvelopeToMesh(
                targetNodeId = toNodeId,
                bytes = bytes,
                preferredPeerId = directPeerId,
                excludedPeerId = null,
            )
            pendingDirectByNodeId[toNodeId]
                ?.firstOrNull { it.messageId == messageId }
                ?.sentPeerIds
                ?.addAll(sentPeers)

            when {
                sentPeers.isEmpty() -> {
                    MeshLogger.warning(TAG, "sendPrivateTextMessage: node $toNodeId недоступен, сообщение сохранено в ожидании")
                    _status.value = "Нет связи с узлом. Сообщение будет отправлено после подключения"
                }
                directPeerId != null && directPeerId in sentPeers -> {
                    messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Sent)
                    _status.value = "Сообщение отправлено, ждём подтверждение"
                }
                else -> {
                    messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Relayed)
                    _status.value = "Сообщение передано mesh-посреднику"
                }
            }
        }.onFailure { error ->
            val message = "Ошибка отправки сообщения: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun sendBroadcastTextMessage(text: String, replyTo: ChatMessage? = null) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            _status.value = "Нельзя отправить пустое сообщение"
            return
        }

        MeshLogger.info(TAG, "sendBroadcastTextMessage(textLength=${trimmedText.length})")
        runCatching {
            val encodedText = MessageTextCodec.encode(trimmedText, replyTo)
            val bytes = meshCoreBridge.createBroadcastTextMessage(text = encodedText)
            val messageId = extractMessageId(bytes) ?: UUID.randomUUID().toString()
            val connectedPeerIds = writablePeerIds()

            messageRepository.saveMessage(
                ChatMessage(
                    id = messageId,
                    chatId = CHAT_ID_BROADCAST,
                    from = localNodeId,
                    to = null,
                    text = trimmedText,
                    timestamp = nowMillis(),
                    isOutgoing = true,
                    status = MessageDeliveryStatus.Pending,
                    replyToMessageId = replyTo?.id,
                    replyToSender = replyTo?.from,
                    replyToText = replyTo?.text?.take(180),
                ),
            )

            pendingBroadcastById[messageId] = PendingBroadcastEnvelope(messageId = messageId, bytes = bytes)
            if (connectedPeerIds.isNotEmpty()) {
                connectedPeerIds.forEach { peerId ->
                    bluetoothTransport.send(peerId = peerId, bytes = bytes)
                    pendingBroadcastById[messageId]?.sentPeerIds?.add(peerId)
                }
                messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Sent)
            }

            _status.value = if (connectedPeerIds.isEmpty()) {
                "Общий чат: нет связи, сообщение ожидает отправки"
            } else {
                "Общее сообщение отправлено: ${connectedPeerIds.size} узл."
            }
        }.onFailure { error ->
            val message = "Ошибка broadcast: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    fun clearStaleConnections() {
        MeshLogger.info(TAG, "clearStaleConnections()")
        val stalePeers = _peers.value.filter { !it.isConnected || !it.isWritable }.map { it.peerId }
        stalePeers.forEach { peerId ->
            runCatching { bluetoothTransport.disconnect(peerId) }
        }
        contactManager.clearStaleEndpoints()
        _peers.value = _peers.value
            .filter { it.isConnected && it.isWritable }
            .distinctBy { it.nodeId ?: it.peerId }
        _status.value = "Старые подключения очищены"
    }

    fun clearChat(chatId: String) {
        messageRepository.clearChat(chatId)
        _status.value = "История чата очищена"
    }

    fun clearAllMessages() {
        messageRepository.clearAll()
        MeshLogger.info(TAG, "clearAllMessages()")
        _status.value = "Вся история очищена"
    }

    fun markChatAsRead(chatId: String) {
        val normalizedChatId = chatId.takeIf { it.isNotBlank() } ?: return
        messageNotifier?.clearChatNotification(normalizedChatId)
        val unreadIncoming = messageRepository.messages.value
            .filter { message ->
                message.chatId == normalizedChatId &&
                    !message.isOutgoing &&
                    message.id !in readAckSentMessageIds
            }

        unreadIncoming.forEach { message ->
            readAckSentMessageIds.add(message.id)
            val peerId = contactManager.peerIdForNode(message.from)
            if (peerId != null && isPeerWritable(peerId)) {
                sendReadAck(sourcePeerId = peerId, messageId = message.id)
            } else {
                writablePeerIds().forEach { writablePeerId ->
                    sendReadAck(sourcePeerId = writablePeerId, messageId = message.id)
                }
            }
        }
    }

    fun addReaction(messageId: String, chatId: String, emoji: String) {
        val safeEmoji = emoji.trim().take(8)
        if (messageId.isBlank() || safeEmoji.isBlank()) return
        messageRepository.updateMessageReaction(messageId = messageId, emoji = safeEmoji, delta = 1)
        sendReactionControl(messageId = messageId, chatId = chatId, emoji = safeEmoji, reactionId = UUID.randomUUID().toString(), excludedPeerId = null)
    }


    fun clearDebugLogs() {
        MeshLogger.clear()
        _status.value = "Debug-логи очищены"
    }


    fun createContactCode(): String {
        val encodedName = URLEncoder.encode(displayName, StandardCharsets.UTF_8.name())
        val encodedAvatar = URLEncoder.encode(avatarEmoji, StandardCharsets.UTF_8.name())
        return "meshsenger://contact?v=$PROTOCOL_VERSION&node=$localNodeId&name=$encodedName&avatar=$encodedAvatar"
    }

    fun importContactCode(rawCode: String): Boolean {
        val trimmed = rawCode.trim()
        val json = parseContactCode(trimmed) ?: run {
            _status.value = "Не удалось прочитать контактный код"
            return false
        }
        return importContactJson(json).also { imported ->
            _status.value = if (imported) "Контакт добавлен" else "Контактный код не подходит"
        }
    }

    private fun parseContactCode(rawCode: String): JSONObject? {
        if (rawCode.startsWith("meshsenger://contact")) {
            val query = rawCode.substringAfter('?', missingDelimiterValue = "")
            val params = query.split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', missingDelimiterValue = "")
                    val value = part.substringAfter('=', missingDelimiterValue = "")
                    if (key.isBlank()) null else key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                }
                .toMap()
            val nodeId = (params["node"] ?: params[FIELD_NODE_ID])?.takeIf { isValidNodeId(it) } ?: return null
            val name = params["name"].orEmpty()
            return JSONObject()
                .put(FIELD_CONTROL_TYPE, CONTROL_CONTACT_IMPORT)
                .put(FIELD_PROTOCOL_VERSION, params["v"] ?: PROTOCOL_VERSION)
                .put(FIELD_NODE_ID, nodeId)
                .put(FIELD_DISPLAY_NAME, name)
                .put(FIELD_AVATAR_EMOJI, params["avatar"].orEmpty())
        }

        return runCatching { JSONObject(rawCode) }.getOrNull()
    }

    private fun handleIncomingBytes(fromPeerId: String, bytes: ByteArray) {
        runCatching {
            MeshLogger.info(TAG, "handleIncomingBytes(from=$fromPeerId, bytes=${bytes.size})")

            if (handleControlFrame(fromPeerId = fromPeerId, bytes = bytes)) {
                return
            }

            if (contactManager.knownNodeIdForPeer(fromPeerId) == null) {
                MeshLogger.warning(TAG, "Non-control frame from unknown BLE peer=$fromPeerId dropped until hello/hello_ack")
                return
            }

            val actions = meshCoreBridge.handleIncomingMessage(fromPeerId = fromPeerId, bytes = bytes)
            MeshLogger.info(TAG, "Rust actions count=${actions.size}")
            actions.forEach { action -> executeAction(sourcePeerId = fromPeerId, action = action) }
        }.onFailure { error ->
            val message = "Ошибка обработки входящих байтов от $fromPeerId: ${error.localizedMessage ?: error.javaClass.simpleName}"
            MeshLogger.error(TAG, message, error)
            _status.value = message
        }
    }

    private fun executeAction(sourcePeerId: String, action: MeshAction) {
        when (action) {
            is MeshAction.ShowMessage -> {
                MeshLogger.info(TAG, "Action ShowMessage from=${action.message.from}, chatId=${action.message.chatId}")
                if (action.message.from == localNodeId) {
                    MeshLogger.info(TAG, "ShowMessage пропущен: это echo собственного сообщения id=${action.message.id}")
                    return
                }
                messageRepository.saveMessage(action.message)
                notifyIncomingMessage(action.message)
                sendDeliveryAck(sourcePeerId = sourcePeerId, messageId = action.message.id)
                _status.value = if (action.message.chatId == CHAT_ID_BROADCAST) {
                    "Получено сообщение в общем чате"
                } else {
                    "Получено сообщение от ${action.message.from}"
                }
            }

            is MeshAction.SaveMessage -> {
                MeshLogger.info(TAG, "Action SaveMessage chatId=${action.message.chatId}")
                if (action.message.from == localNodeId) {
                    MeshLogger.info(TAG, "SaveMessage пропущен: это echo собственного сообщения id=${action.message.id}")
                    return
                }
                messageRepository.saveMessage(action.message)
                notifyIncomingMessage(action.message)
                sendDeliveryAck(sourcePeerId = sourcePeerId, messageId = action.message.id)
            }

            is MeshAction.ForwardMessage -> {
                MeshLogger.info(TAG, "Action ForwardMessage targets=${action.targetPeerIds}, bytes=${action.bytes.size}")
                val targets = action.targetPeerIds.filterNot { it == localNodeId }
                if (targets.isEmpty()) {
                    val targetPeerIds = writablePeerIds().filterNot { it == sourcePeerId }
                    if (targetPeerIds.isEmpty()) {
                        MeshLogger.info(TAG, "ForwardMessage: нет подключённых peer-ов для широковещательной пересылки")
                    }
                    targetPeerIds.forEach { peerId -> bluetoothTransport.send(peerId = peerId, bytes = action.bytes) }
                    return
                }

                targets.forEach { targetNodeId ->
                    val messageId = extractMessageId(action.bytes)
                    val envelope = enqueuePendingForwardIfMissing(targetNodeId = targetNodeId, bytes = action.bytes)

                    // Важно: BLE write завершается асинхронно. Если send() сейчас вернул управление,
                    // это ещё не значит, что следующий узел реально получил пакет. Поэтому forward-пакет
                    // остаётся в relay-очереди до delivery_ack от конечного получателя. Именно это чинит
                    // потерю сообщений через посредника, когда A21s успевал отправить relay_ack, а затем
                    // BLE write падал со status=133.
                    sendRelayAck(peerId = sourcePeerId, messageId = messageId)

                    val directPeerId = contactManager.peerIdForNode(targetNodeId)
                        ?.takeIf { it != sourcePeerId && isPeerWritable(it) }
                        ?: meshCoreBridge.resolvePeerIdForNode(targetNodeId).takeIf { it != targetNodeId && it != sourcePeerId && isPeerWritable(it) }

                    val sentPeers = sendEnvelopeToMesh(
                        targetNodeId = targetNodeId,
                        bytes = action.bytes,
                        preferredPeerId = directPeerId,
                        excludedPeerId = sourcePeerId,
                    )
                    envelope.sentPeerIds.addAll(sentPeers)
                    persistPendingPackets()

                    if (sentPeers.isEmpty()) {
                        MeshLogger.info(TAG, "ForwardMessage: target $targetNodeId сейчас недоступен, байты сохранены в relay-очереди")
                    } else {
                        MeshLogger.info(TAG, "ForwardMessage: target=$targetNodeId relayed via $sentPeers; kept until delivery_ack")
                    }
                }
            }

            MeshAction.DropMessage -> {
                MeshLogger.info(TAG, "Action DropMessage")
                _status.value = "Сообщение проигнорировано Rust-ядром"
            }

            is MeshAction.Error -> {
                MeshLogger.warning(TAG, "Action Error: ${action.message}")
                _status.value = "Rust error: ${action.message}"
            }
        }
    }

    private fun notifyIncomingMessage(message: ChatMessage) {
        val senderName = displayNameForNode(message.from)
        messageNotifier?.notifyIncomingMessage(
            chatId = message.chatId,
            senderName = senderName,
            text = message.text,
            isBroadcast = message.chatId == CHAT_ID_BROADCAST,
            messageId = message.id,
        )
    }

    private fun displayNameForNode(nodeId: String): String {
        if (nodeId == localNodeId) return displayName
        return contactManager.snapshot().firstOrNull { it.nodeId == nodeId }?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: nodeId
    }

    private fun sendControlHello(peerId: String, type: String) {
        runCatching {
            val json = JSONObject()
                .put(FIELD_CONTROL_TYPE, type)
                .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .put(FIELD_NODE_ID, localNodeId)
                .put(FIELD_DISPLAY_NAME, displayName)
                .put(FIELD_AVATAR_EMOJI, avatarEmoji)

            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            MeshLogger.info(TAG, "sendControlHello(type=$type, peer=$peerId, node=$localNodeId)")
            bluetoothTransport.send(peerId = peerId, bytes = bytes)
        }.onFailure { error ->
            MeshLogger.warning(TAG, "Не удалось отправить control hello: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun sendNodeAnnounce(peerId: String) {
        runCatching {
            val knownNodes = JSONArray()
            contactManager.snapshot()
                .filter { it.nodeId != localNodeId }
                .take(40)
                .forEach { contact ->
                    knownNodes.put(
                        JSONObject()
                            .put(FIELD_NODE_ID, contact.nodeId)
                            .put(FIELD_DISPLAY_NAME, contact.displayName ?: "")
                            .put(FIELD_AVATAR_EMOJI, contact.avatarEmoji ?: "")
                            .put("last_seen", contact.lastSeenMillis)
                            .put("via", contact.discoveredVia),
                    )
                }

            val json = JSONObject()
                .put(FIELD_CONTROL_TYPE, CONTROL_NODE_ANNOUNCE)
                .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .put(FIELD_NODE_ID, localNodeId)
                .put(FIELD_DISPLAY_NAME, displayName)
                .put(FIELD_AVATAR_EMOJI, avatarEmoji)
                .put(FIELD_KNOWN_NODES, knownNodes)

            MeshLogger.info(TAG, "sendNodeAnnounce(peer=$peerId, known=${knownNodes.length()})")
            bluetoothTransport.send(peerId = peerId, bytes = json.toString().toByteArray(StandardCharsets.UTF_8))
        }.onFailure { error ->
            MeshLogger.warning(TAG, "Не удалось отправить node_announce: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun broadcastNodeAnnounce(excludedPeerId: String? = null) {
        writablePeerIds().filterNot { it == excludedPeerId }.forEach { peerId -> sendNodeAnnounce(peerId) }
    }

    private fun handleNodeAnnounce(json: JSONObject, fromPeerId: String) {
        val nodeId = json.optString(FIELD_NODE_ID).takeIf { isValidNodeId(it) }
        val name = json.optString(FIELD_DISPLAY_NAME).takeIf { isHumanDisplayName(it) }
        val avatar = json.optString(FIELD_AVATAR_EMOJI).takeIf { it.isNotBlank() }
        if (nodeId != null && nodeId != localNodeId) {
            contactManager.rememberKnownNode(nodeId = nodeId, displayName = name, avatarEmoji = avatar, discoveredVia = "announce")
            meshCoreBridge.rememberPeerNode(peerId = fromPeerId, nodeId = nodeId)
        }

        val knownNodes = json.optJSONArray(FIELD_KNOWN_NODES) ?: JSONArray()
        for (index in 0 until knownNodes.length()) {
            val item = knownNodes.optJSONObject(index) ?: continue
            val knownNodeId = item.optString(FIELD_NODE_ID).takeIf { isValidNodeId(it) } ?: continue
            if (knownNodeId == localNodeId) continue
            val knownName = item.optString(FIELD_DISPLAY_NAME).takeIf { isHumanDisplayName(it) }
            val knownAvatar = item.optString(FIELD_AVATAR_EMOJI).takeIf { it.isNotBlank() }
            contactManager.rememberKnownNode(nodeId = knownNodeId, displayName = knownName, avatarEmoji = knownAvatar, discoveredVia = "mesh_contact")
        }
        val now = nowMillis()
        if (now - lastNodeAnnounceRebroadcastAtMillis > NODE_ANNOUNCE_REBROADCAST_COOLDOWN_MS) {
            lastNodeAnnounceRebroadcastAtMillis = now
            broadcastNodeAnnounce(excludedPeerId = fromPeerId)
        } else {
            MeshLogger.info(TAG, "node_announce rebroadcast skipped by cooldown")
        }
        MeshLogger.info(TAG, "handleNodeAnnounce(from=$fromPeerId, node=${nodeId ?: "unknown"}, known=${knownNodes.length()})")
    }

    private fun importContactJson(json: JSONObject): Boolean {
        val nodeId = json.optString(FIELD_NODE_ID).takeIf { isValidNodeId(it) } ?: return false
        if (nodeId == localNodeId) return false
        val name = json.optString(FIELD_DISPLAY_NAME).takeIf { isHumanDisplayName(it) }
        val avatar = json.optString(FIELD_AVATAR_EMOJI).takeIf { it.isNotBlank() }
        contactManager.rememberKnownNode(nodeId = nodeId, displayName = name, avatarEmoji = avatar, discoveredVia = "qr")
        broadcastNodeAnnounce()
        return true
    }

    private fun sendRelayAck(peerId: String, messageId: String?) {
        if (messageId.isNullOrBlank()) return
        if (!isPeerWritable(peerId)) return
        val json = JSONObject()
            .put(FIELD_CONTROL_TYPE, CONTROL_RELAY_ACK)
            .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .put(FIELD_NODE_ID, localNodeId)
            .put(FIELD_MESSAGE_ID, messageId)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        MeshLogger.info(TAG, "sendRelayAck(peer=$peerId, message=$messageId)")
        runCatching { bluetoothTransport.send(peerId = peerId, bytes = bytes) }
    }

    private fun forwardDeliveryAck(sourcePeerId: String, messageId: String) {
        if (!seenDeliveryAckIds.add(messageId)) return
        val json = JSONObject()
            .put(FIELD_CONTROL_TYPE, CONTROL_DELIVERY_ACK)
            .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .put(FIELD_NODE_ID, localNodeId)
            .put(FIELD_MESSAGE_ID, messageId)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        writablePeerIds().filterNot { it == sourcePeerId }.forEach { peerId ->
            MeshLogger.info(TAG, "forwardDeliveryAck(message=$messageId, peer=$peerId)")
            runCatching { bluetoothTransport.send(peerId = peerId, bytes = bytes) }
        }
    }


    private fun sendReadAck(sourcePeerId: String, messageId: String) {
        if (messageId.isBlank() || !isPeerWritable(sourcePeerId)) return
        runCatching {
            val json = JSONObject()
                .put(FIELD_CONTROL_TYPE, CONTROL_READ_ACK)
                .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .put(FIELD_NODE_ID, localNodeId)
                .put(FIELD_MESSAGE_ID, messageId)
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            MeshLogger.info(TAG, "sendReadAck(peer=$sourcePeerId, message=$messageId)")
            bluetoothTransport.send(peerId = sourcePeerId, bytes = bytes)
        }.onFailure { error ->
            MeshLogger.warning(TAG, "Не удалось отправить read_ack: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun forwardReadAck(sourcePeerId: String, messageId: String) {
        if (!seenReadAckIds.add(messageId)) return
        val json = JSONObject()
            .put(FIELD_CONTROL_TYPE, CONTROL_READ_ACK)
            .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .put(FIELD_NODE_ID, localNodeId)
            .put(FIELD_MESSAGE_ID, messageId)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        writablePeerIds().filterNot { it == sourcePeerId }.forEach { peerId ->
            MeshLogger.info(TAG, "forwardReadAck(message=$messageId, peer=$peerId)")
            runCatching { bluetoothTransport.send(peerId = peerId, bytes = bytes) }
        }
    }

    private fun sendReactionControl(messageId: String, chatId: String, emoji: String, reactionId: String, excludedPeerId: String?) {
        val json = JSONObject()
            .put(FIELD_CONTROL_TYPE, CONTROL_REACTION)
            .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .put(FIELD_NODE_ID, localNodeId)
            .put(FIELD_MESSAGE_ID, messageId)
            .put(FIELD_REACTION_ID, reactionId)
            .put(FIELD_CHAT_ID, chatId)
            .put(FIELD_EMOJI, emoji)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        writablePeerIds().filterNot { it == excludedPeerId }.forEach { peerId ->
            MeshLogger.info(TAG, "sendReaction(peer=$peerId, message=$messageId, emoji=$emoji)")
            runCatching { bluetoothTransport.send(peerId = peerId, bytes = bytes) }
        }
    }

    private fun sendDeliveryAck(sourcePeerId: String, messageId: String) {
        if (messageId.isBlank()) return
        runCatching {
            val json = JSONObject()
                .put(FIELD_CONTROL_TYPE, CONTROL_DELIVERY_ACK)
                .put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .put(FIELD_NODE_ID, localNodeId)
                .put(FIELD_MESSAGE_ID, messageId)
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            MeshLogger.info(TAG, "sendDeliveryAck(peer=$sourcePeerId, message=$messageId)")
            bluetoothTransport.send(peerId = sourcePeerId, bytes = bytes)
        }.onFailure { error ->
            MeshLogger.warning(TAG, "Не удалось отправить delivery_ack: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun handleControlFrame(fromPeerId: String, bytes: ByteArray): Boolean {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        if (!text.contains(FIELD_CONTROL_TYPE)) return false

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val controlType = json.optString(FIELD_CONTROL_TYPE)
        if (controlType == CONTROL_DELIVERY_ACK) {
            val messageId = json.optString(FIELD_MESSAGE_ID).takeIf { it.isNotBlank() }
            if (messageId != null) {
                MeshLogger.info(TAG, "handleControlFrame(delivery_ack, peer=$fromPeerId, message=$messageId)")
                messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Delivered)
                removePendingByMessageId(messageId)
                forwardDeliveryAck(sourcePeerId = fromPeerId, messageId = messageId)
                _status.value = "Сообщение доставлено"
            }
            return true
        }

        if (controlType == CONTROL_READ_ACK) {
            val messageId = json.optString(FIELD_MESSAGE_ID).takeIf { it.isNotBlank() }
            if (messageId != null) {
                MeshLogger.info(TAG, "handleControlFrame(read_ack, peer=$fromPeerId, message=$messageId)")
                messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Read)
                forwardReadAck(sourcePeerId = fromPeerId, messageId = messageId)
                _status.value = "Сообщение прочитано"
            }
            return true
        }

        if (controlType == CONTROL_REACTION) {
            val messageId = json.optString(FIELD_MESSAGE_ID).takeIf { it.isNotBlank() }
            val emoji = json.optString(FIELD_EMOJI).takeIf { it.isNotBlank() }
            val reactionId = json.optString(FIELD_REACTION_ID).takeIf { it.isNotBlank() }
                ?: "$fromPeerId:$messageId:$emoji"
            if (messageId != null && emoji != null && seenReactionIds.add(reactionId)) {
                MeshLogger.info(TAG, "handleControlFrame(reaction, peer=$fromPeerId, message=$messageId, emoji=$emoji)")
                messageRepository.updateMessageReaction(messageId = messageId, emoji = emoji, delta = 1)
                sendReactionControl(
                    messageId = messageId,
                    chatId = json.optString(FIELD_CHAT_ID),
                    emoji = emoji,
                    reactionId = reactionId,
                    excludedPeerId = fromPeerId,
                )
            }
            return true
        }

        if (controlType == CONTROL_RELAY_ACK) {
            val messageId = json.optString(FIELD_MESSAGE_ID).takeIf { it.isNotBlank() }
            if (messageId != null) {
                MeshLogger.info(TAG, "handleControlFrame(relay_ack, peer=$fromPeerId, message=$messageId)")
                if (_peers.value.any { it.peerId == fromPeerId && it.isWritable }) {
                    messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Relayed)
                }
            }
            return true
        }

        if (controlType == CONTROL_NODE_ANNOUNCE) {
            handleNodeAnnounce(json = json, fromPeerId = fromPeerId)
            return true
        }

        if (controlType == CONTROL_CONTACT_IMPORT) {
            importContactJson(json)
            return true
        }

        if (controlType != CONTROL_HELLO && controlType != CONTROL_HELLO_ACK) return false

        val remoteNodeId = json.optString(FIELD_NODE_ID).takeIf { isValidNodeId(it) } ?: run {
            MeshLogger.warning(TAG, "Control $controlType from $fromPeerId ignored: invalid node_id=${json.optString(FIELD_NODE_ID)}")
            return true
        }
        val remoteDisplayName = json.optString(FIELD_DISPLAY_NAME).takeIf { isHumanDisplayName(it) }
        val remoteAvatarEmoji = json.optString(FIELD_AVATAR_EMOJI).takeIf { it.isNotBlank() }

        MeshLogger.info(TAG, "handleControlFrame(type=$controlType, peer=$fromPeerId, node=$remoteNodeId, name=${remoteDisplayName ?: "null"})")
        val stalePeers = contactManager.rememberHandshake(peerId = fromPeerId, nodeId = remoteNodeId, displayName = remoteDisplayName, avatarEmoji = remoteAvatarEmoji)
        meshCoreBridge.rememberPeerNode(peerId = fromPeerId, nodeId = remoteNodeId)
        removeStalePeersForNode(nodeId = remoteNodeId, activePeerId = fromPeerId, stalePeerIds = stalePeers)
        upsertPeer(peerId = fromPeerId, name = remoteDisplayName, nodeId = remoteNodeId, isWritable = true)
        setPeerConnected(peerId = fromPeerId, isConnected = true)
        meshCoreBridge.markPeerConnected(fromPeerId)
        sendPendingMessages(peerId = fromPeerId, nodeId = remoteNodeId)
        flushLocalPendingForNode(nodeId = remoteNodeId, peerId = fromPeerId)
        flushAllPendingViaPeer(peerId = fromPeerId, nodeId = remoteNodeId)
        flushPendingBroadcasts(peerId = fromPeerId)
        sendNodeAnnounce(peerId = fromPeerId)

        if (controlType == CONTROL_HELLO) {
            sendControlHello(peerId = fromPeerId, type = CONTROL_HELLO_ACK)
        }

        _status.value = "Mesh handshake: ${remoteDisplayName ?: remoteNodeId}"
        return true
    }

    private fun isPeerConnected(peerId: String): Boolean {
        return _peers.value.any { it.peerId == peerId && it.isConnected }
    }

    private fun isPeerWritable(peerId: String): Boolean {
        return _peers.value.any { it.peerId == peerId && it.isConnected && it.isWritable }
    }

    private fun sendPendingMessages(peerId: String, nodeId: String = contactManager.nodeIdForPeer(peerId)) {
        val pendingForPeer = meshCoreBridge.getPendingMessagesForPeer(peerId)
        val pendingForNode = if (nodeId != peerId) meshCoreBridge.getPendingMessagesForPeer(nodeId) else emptyList()
        val pending = pendingForPeer + pendingForNode
        if (pending.isNotEmpty()) {
            MeshLogger.info(TAG, "sendPendingMessages(peer=$peerId, node=$nodeId) count=${pending.size}")
        }
        pending.forEach { bytes ->
            bluetoothTransport.send(peerId = peerId, bytes = bytes)
        }
    }

    private fun upsertPeer(
        peerId: String,
        name: String?,
        nodeId: String? = null,
        isWritable: Boolean? = null,
    ) {
        val currentPeers = _peers.value
        val existingPeer = currentPeers.firstOrNull { it.peerId == peerId }
        val safeNodeId = nodeId?.takeIf { it != peerId }

        _peers.value = if (existingPeer == null) {
            currentPeers + PeerUiModel(
                peerId = peerId,
                name = name,
                nodeId = safeNodeId,
                isWritable = isWritable == true,
                connectionState = if (isWritable == true) "готов" else "найден",
            )
        } else {
            currentPeers.map {
                if (it.peerId == peerId) {
                    it.copy(
                        name = name ?: it.name,
                        nodeId = safeNodeId ?: it.nodeId,
                        isWritable = isWritable ?: it.isWritable,
                        connectionState = when {
                            isWritable == true -> "готов"
                            it.isConnected -> "подключён"
                            else -> it.connectionState
                        },
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun setPeerConnected(peerId: String, isConnected: Boolean) {
        val currentPeers = _peers.value
        val existingPeer = currentPeers.firstOrNull { it.peerId == peerId }

        _peers.value = if (existingPeer == null && isConnected) {
            currentPeers + PeerUiModel(
                peerId = peerId,
                name = "Узел ${peerId.takeLast(5)}",
                isConnected = true,
                nodeId = contactManager.nodeIdForPeer(peerId).takeIf { it != peerId },
                isWritable = true,
                connectionState = "готов",
            )
        } else {
            currentPeers.map {
                if (it.peerId == peerId) {
                    it.copy(
                        name = it.name ?: if (isConnected) "Узел ${peerId.takeLast(5)}" else null,
                        isConnected = isConnected,
                        isWritable = if (isConnected) it.isWritable else false,
                        connectionState = if (isConnected) "готов" else "отключён",
                    )
                } else {
                    it
                }
            }
        }
    }


    private fun writablePeerIds(): List<String> {
        val fromPeers = _peers.value
            .filter { it.isConnected && it.isWritable }
            .map { it.peerId }
        return (fromPeers + contactManager.writablePeerIds()).distinct()
    }

    /**
     * Отправляет пакет либо напрямую целевому node_id, либо любому mesh-соседу.
     * Это главное отличие mesh-логики от обычного direct-chat: если HONOR не видит
     * Samsung 2 напрямую, но видит Кротика, пакет всё равно отдаётся Кротику,
     * а Rust-ядро/relay-очередь на промежуточных узлах доведут его дальше.
     */
    private fun sendEnvelopeToMesh(
        targetNodeId: String,
        bytes: ByteArray,
        preferredPeerId: String? = null,
        excludedPeerId: String? = null,
    ): List<String> {
        val candidates = buildList {
            if (preferredPeerId != null && isPeerWritable(preferredPeerId)) add(preferredPeerId)
            addAll(writablePeerIds())
        }
            .filter { it != excludedPeerId }
            .filter { isPeerWritable(it) }
            .distinct()

        val sent = mutableListOf<String>()
        candidates.forEach { peerId ->
            runCatching {
                bluetoothTransport.send(peerId = peerId, bytes = bytes)
                sent += peerId
            }.onFailure { error ->
                MeshLogger.warning(TAG, "Не удалось отправить mesh-пакет peer=$peerId target=$targetNodeId: ${error.localizedMessage ?: error.javaClass.simpleName}")
            }
        }
        return sent
    }

    private fun enqueuePendingDirect(
        toNodeId: String,
        messageId: String,
        bytes: ByteArray,
        sentPeerId: String? = null,
    ) {
        val envelope = PendingEnvelope(messageId = messageId, bytes = bytes)
        sentPeerId?.let { envelope.sentPeerIds.add(it) }
        pendingDirectByNodeId.getOrPut(toNodeId) { mutableListOf() }.add(envelope)
        persistPendingPackets()
        MeshLogger.info(TAG, "Pending direct queued node=$toNodeId message=$messageId sentPeer=${sentPeerId ?: "none"}")
    }

    private fun enqueuePendingForward(targetNodeId: String, bytes: ByteArray) {
        enqueuePendingForwardIfMissing(targetNodeId = targetNodeId, bytes = bytes)
        persistPendingPackets()
    }

    private fun enqueuePendingForwardIfMissing(targetNodeId: String, bytes: ByteArray): PendingEnvelope {
        val messageId = extractMessageId(bytes)
        val queue = pendingForwardByNodeId.getOrPut(targetNodeId) { mutableListOf() }
        val existing = messageId?.let { id -> queue.firstOrNull { it.messageId == id } }
        if (existing != null) return existing

        val envelope = PendingEnvelope(messageId = messageId, bytes = bytes)
        queue.add(envelope)
        MeshLogger.info(TAG, "Pending forward queued node=$targetNodeId message=${messageId ?: "unknown"}")
        return envelope
    }

    private fun flushAllPendingViaPeer(peerId: String, nodeId: String) {
        if (!isPeerWritable(peerId)) return
        var sentCount = 0

        pendingDirectByNodeId.forEach { (targetNodeId, envelopes) ->
            envelopes.forEach { envelope ->
                if (targetNodeId != localNodeId && envelope.sentPeerIds.add(peerId)) {
                    bluetoothTransport.send(peerId = peerId, bytes = envelope.bytes)
                    envelope.messageId?.let { messageId ->
                        val status = if (targetNodeId == nodeId) MessageDeliveryStatus.Sent else MessageDeliveryStatus.Relayed
                        messageRepository.updateMessageStatus(messageId, status)
                    }
                    sentCount += 1
                }
            }
        }

        pendingForwardByNodeId.forEach { (targetNodeId, envelopes) ->
            envelopes.forEach { envelope ->
                if (targetNodeId != localNodeId && envelope.sentPeerIds.add(peerId)) {
                    bluetoothTransport.send(peerId = peerId, bytes = envelope.bytes)
                    sentCount += 1
                }
            }
        }

        if (sentCount > 0) {
            persistPendingPackets()
            MeshLogger.info(TAG, "flushAllPendingViaPeer(peer=$peerId, node=$nodeId) count=$sentCount")
            _status.value = "Mesh-очередь синхронизирована: $sentCount"
        }
    }

    private fun flushLocalPendingForNode(nodeId: String, peerId: String) {
        val direct = pendingDirectByNodeId[nodeId].orEmpty()
        val forward = pendingForwardByNodeId[nodeId].orEmpty()
        val pending = direct + forward
        if (pending.isEmpty()) return

        MeshLogger.info(TAG, "flushLocalPendingForNode(node=$nodeId, peer=$peerId) count=${pending.size}")
        var sentCount = 0
        pending.forEach { envelope ->
            if (envelope.sentPeerIds.add(peerId)) {
                bluetoothTransport.send(peerId = peerId, bytes = envelope.bytes)
                envelope.messageId?.let { messageId ->
                    messageRepository.updateMessageStatus(messageId, MessageDeliveryStatus.Sent)
                }
                sentCount += 1
            }
        }
        if (sentCount > 0) {
            persistPendingPackets()
            _status.value = "Отложенные сообщения отправлены: $sentCount"
        }
    }

    private fun flushPendingBroadcasts(peerId: String) {
        if (pendingBroadcastById.isEmpty()) return
        pendingBroadcastById.values.forEach { envelope ->
            if (envelope.sentPeerIds.add(peerId)) {
                bluetoothTransport.send(peerId = peerId, bytes = envelope.bytes)
                messageRepository.updateMessageStatus(envelope.messageId, MessageDeliveryStatus.Sent)
            }
        }
        // Не удаляем broadcast из очереди сразу после первой отправки: если появится новый peer,
        // он тоже должен получить старый pending-broadcast. Очередь очищается по delivery_ack.
    }

    private fun removeStalePeersForNode(nodeId: String, activePeerId: String, stalePeerIds: List<String>) {
        if (stalePeerIds.isEmpty()) return
        MeshLogger.info(TAG, "removeStalePeersForNode(node=$nodeId, active=$activePeerId, stale=$stalePeerIds)")
        stalePeerIds.filterNot { it == activePeerId }.forEach { stalePeerId ->
            runCatching { bluetoothTransport.disconnect(stalePeerId) }
        }
        _peers.value = _peers.value.filterNot { peer ->
            peer.peerId != activePeerId && (peer.peerId in stalePeerIds || peer.nodeId == nodeId)
        }
    }

    /**
     * Разрешает повторную отправку pending-пакетов после обрыва BLE-соединения.
     *
     * BLE peerId часто является временным endpoint-ом. Если пакет уже пробовали
     * отправить через этот endpoint, а потом соединение упало, старую отметку нужно
     * снять. Иначе после переподключения сообщение будет висеть в ожидании и не
     * уйдёт повторно.
     */
    private fun markPendingRetryAllowedForPeer(peerId: String) {
        var changed = false

        pendingDirectByNodeId.values.forEach { envelopes ->
            envelopes.forEach { envelope ->
                if (envelope.sentPeerIds.remove(peerId)) changed = true
            }
        }

        pendingForwardByNodeId.values.forEach { envelopes ->
            envelopes.forEach { envelope ->
                if (envelope.sentPeerIds.remove(peerId)) changed = true
            }
        }

        pendingBroadcastById.values.forEach { envelope ->
            if (envelope.sentPeerIds.remove(peerId)) changed = true
        }

        if (changed) {
            persistPendingPackets()
            MeshLogger.info(TAG, "Pending retry unlocked for disconnected peer=$peerId")
        }
    }

    private fun removePendingByMessageId(messageId: String) {
        pendingDirectByNodeId.entries.removeAll { entry ->
            entry.value.removeAll { it.messageId == messageId }
            entry.value.isEmpty()
        }
        pendingForwardByNodeId.entries.removeAll { entry ->
            entry.value.removeAll { it.messageId == messageId }
            entry.value.isEmpty()
        }
        pendingBroadcastById.remove(messageId)
        persistPendingPackets()
    }

    private fun persistPendingPackets() {
        val packets = buildList {
            pendingDirectByNodeId.forEach { (targetNodeId, envelopes) ->
                envelopes.forEach { envelope ->
                    add(
                        PendingPacket(
                            kind = PendingPacket.KIND_DIRECT,
                            targetNodeId = targetNodeId,
                            messageId = envelope.messageId,
                            bytes = envelope.bytes,
                            sentPeerIds = envelope.sentPeerIds,
                        ),
                    )
                }
            }
            pendingForwardByNodeId.forEach { (targetNodeId, envelopes) ->
                envelopes.forEach { envelope ->
                    add(
                        PendingPacket(
                            kind = PendingPacket.KIND_FORWARD,
                            targetNodeId = targetNodeId,
                            messageId = envelope.messageId,
                            bytes = envelope.bytes,
                            sentPeerIds = envelope.sentPeerIds,
                        ),
                    )
                }
            }
        }
        onPendingPacketsSaved(packets)
    }

    private fun extractMessageId(bytes: ByteArray): String? {
        return runCatching {
            val text = String(bytes, StandardCharsets.UTF_8)
            JSONObject(text).optString("message_id").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun nowMillis(): Long = System.currentTimeMillis()

    private fun isValidNodeId(value: String?): Boolean {
        return !value.isNullOrBlank() && value.startsWith("node_")
    }

    private fun normalizeAvatarEmoji(value: String): String {
        return value.trim().takeIf { it.isNotBlank() }?.take(4) ?: "🌿"
    }

    private fun isHumanDisplayName(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        if (text.startsWith("BLE узел", ignoreCase = true)) return false
        if (text.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"))) return false
        return true
    }

    private data class PendingEnvelope(
        val messageId: String?,
        val bytes: ByteArray,
        val sentPeerIds: MutableSet<String> = linkedSetOf(),
    )

    private data class PendingBroadcastEnvelope(
        val messageId: String,
        val bytes: ByteArray,
        val sentPeerIds: MutableSet<String> = linkedSetOf(),
    )

    private companion object {
        const val TAG = "MeshService"
        const val CHAT_ID_BROADCAST = "broadcast"
        const val PROTOCOL_VERSION = "0.1.0"
        const val FIELD_CONTROL_TYPE = "meshsenger_control"
        const val FIELD_PROTOCOL_VERSION = "protocol_version"
        const val FIELD_NODE_ID = "node_id"
        const val FIELD_DISPLAY_NAME = "display_name"
        const val FIELD_AVATAR_EMOJI = "avatar_emoji"
        const val CONTROL_HELLO = "hello"
        const val CONTROL_HELLO_ACK = "hello_ack"
        const val CONTROL_DELIVERY_ACK = "delivery_ack"
        const val CONTROL_RELAY_ACK = "relay_ack"
        const val CONTROL_READ_ACK = "read_ack"
        const val CONTROL_REACTION = "reaction"
        const val CONTROL_NODE_ANNOUNCE = "node_announce"
        const val CONTROL_CONTACT_IMPORT = "contact_import"
        const val FIELD_MESSAGE_ID = "message_id"
        const val FIELD_CHAT_ID = "chat_id"
        const val FIELD_REACTION_ID = "reaction_id"
        const val FIELD_EMOJI = "emoji"
        const val FIELD_KNOWN_NODES = "known_nodes"
        const val NODE_ANNOUNCE_REBROADCAST_COOLDOWN_MS = 15_000L
    }
}
