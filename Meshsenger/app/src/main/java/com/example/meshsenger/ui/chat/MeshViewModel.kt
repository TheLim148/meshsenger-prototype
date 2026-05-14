package com.example.meshsenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.ChatPreviewUiModel
import com.example.meshsenger.mesh.model.PeerUiModel
import com.example.meshsenger.mesh.logging.DebugLogEntry
import com.example.meshsenger.mesh.service.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MeshViewModel(
    private val meshService: MeshService,
) : ViewModel() {
    private val inputState = MutableStateFlow(
        MeshUiState(
            localNodeId = meshService.getLocalNodeId(),
            displayName = meshService.getDisplayName(),
            avatarEmoji = meshService.getAvatarEmoji(),
            myContactCode = meshService.createContactCode(),
            appScreen = if (meshService.isOnboardingDone()) AppScreen.Chats else AppScreen.Onboarding,
        ),
    )

    private val transportState = combine(
        meshService.isScanning,
        meshService.isAdvertising,
        meshService.debugLogs,
    ) { isScanning, isAdvertising, debugLogs ->
        TransportState(
            isScanning = isScanning,
            isAdvertising = isAdvertising,
            debugLogs = debugLogs,
        )
    }

    private val meshState = combine(
        meshService.peers,
        meshService.contacts,
        meshService.messages,
        meshService.status,
    ) { peers, contacts, messages, status ->
        MeshState(
            peers = peers,
            contacts = contacts,
            messages = messages,
            status = status,
        )
    }

    val uiState = combine(
        inputState,
        meshState,
        transportState,
    ) { input, meshState, transportState ->
        val peers = meshState.peers
        val contacts = meshState.contacts
        val messages = meshState.messages
        val status = meshState.status
        val rawChatId = (input.appScreen as? AppScreen.Chat)?.chatId
            ?: (input.appScreen as? AppScreen.ChatInfo)?.chatId
        val normalizedChatId = rawChatId?.let { chatId -> normalizeChatId(chatId = chatId, peers = peers) }
        val currentChatAliases = buildSet {
            rawChatId?.let { add(it) }
            normalizedChatId?.let { add(it) }
            peers.filter { peer ->
                peer.nodeId != null && (peer.nodeId == normalizedChatId || peer.peerId == rawChatId)
            }.forEach { peer -> add(peer.peerId) }
        }

        input.copy(
            peers = peers,
            contacts = contacts,
            messages = messages,
            chatPreviews = buildChatPreviews(peers = peers, contacts = contacts, messages = messages),
            myContactCode = meshService.createContactCode(),
            currentChatId = normalizedChatId,
            currentChatMessages = messages
                .filter { message -> message.chatId in currentChatAliases }
                .distinctBy { it.id }
                .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id }),
            currentChatTitle = normalizedChatId?.let { chatTitle(chatId = it, peers = peers, contacts = contacts) }.orEmpty(),
            status = status,
            isScanning = transportState.isScanning,
            isAdvertising = transportState.isAdvertising,
            debugLogs = transportState.debugLogs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = inputState.value,
    )

    fun onDisplayNameChange(name: String) {
        inputState.update { it.copy(displayName = name) }
        meshService.setDisplayName(name)
    }

    fun onAvatarEmojiChange(emoji: String) {
        val normalized = emoji.trim().take(4)
        inputState.update { it.copy(avatarEmoji = normalized) }
        meshService.setAvatarEmoji(normalized)
    }

    fun onCompleteOnboarding() {
        val normalizedName = uiState.value.displayName.trim().ifBlank { "Мой узел" }
        meshService.setDisplayName(normalizedName)
        inputState.update {
            it.copy(
                displayName = normalizedName,
                appScreen = AppScreen.Permissions,
            )
        }
    }

    fun onPermissionsReady() {
        inputState.update { it.copy(appScreen = AppScreen.Chats) }
    }

    fun onOpenChats() {
        inputState.update { it.copy(appScreen = AppScreen.Chats) }
    }

    fun onOpenContacts() {
        inputState.update { it.copy(appScreen = AppScreen.Contacts) }
    }

    fun onOpenNearby() {
        inputState.update { it.copy(appScreen = AppScreen.Nearby) }
    }

    fun onOpenSettings() {
        inputState.update { it.copy(appScreen = AppScreen.Settings) }
    }

    fun onOpenProfile() {
        inputState.update { it.copy(appScreen = AppScreen.Profile) }
    }

    fun onOpenDebug() {
        inputState.update { it.copy(appScreen = AppScreen.Debug) }
    }

    fun onOpenAbout() {
        inputState.update { it.copy(appScreen = AppScreen.About) }
    }

    fun onOpenContactCode() {
        inputState.update { it.copy(appScreen = AppScreen.ContactCode, myContactCode = meshService.createContactCode()) }
    }

    fun onOpenImportContact() {
        inputState.update { it.copy(appScreen = AppScreen.ImportContact, contactCodeInput = "") }
    }

    fun onContactCodeInputChange(value: String) {
        inputState.update { it.copy(contactCodeInput = value) }
    }


    fun onScannedContactCode(value: String) {
        inputState.update { it.copy(contactCodeInput = value) }
        val imported = meshService.importContactCode(value)
        if (imported) {
            inputState.update { it.copy(appScreen = AppScreen.Contacts, contactCodeInput = "") }
        }
    }

    fun onImportContactClick() {
        val imported = meshService.importContactCode(uiState.value.contactCodeInput)
        if (imported) {
            inputState.update { it.copy(appScreen = AppScreen.Contacts, contactCodeInput = "") }
        }
    }

    fun onOpenChat(chatId: String) {
        inputState.update { it.copy(appScreen = AppScreen.Chat(chatId = chatId), messageText = "", replyToMessage = null) }
    }

    fun onOpenChatInfo(chatId: String) {
        inputState.update { it.copy(appScreen = AppScreen.ChatInfo(chatId = chatId)) }
    }

    fun onBack() {
        val screen = uiState.value.appScreen
        val nextScreen = when (screen) {
            is AppScreen.Chat -> AppScreen.Chats
            is AppScreen.ChatInfo -> AppScreen.Chat(screen.chatId)
            AppScreen.Debug,
            AppScreen.About,
            AppScreen.Profile,
            AppScreen.ContactCode,
            AppScreen.ImportContact -> AppScreen.Settings
            AppScreen.Nearby,
            AppScreen.Contacts,
            AppScreen.Settings -> AppScreen.Chats
            AppScreen.Permissions -> AppScreen.Onboarding
            AppScreen.Chats,
            AppScreen.Onboarding -> AppScreen.Chats
        }
        inputState.update { it.copy(appScreen = nextScreen) }
    }

    fun onStartScanClick() {
        if (uiState.value.isScanning) {
            meshService.stopScan()
        } else {
            meshService.startScan()
        }
    }

    fun onStartAdvertisingClick() {
        if (uiState.value.isAdvertising) {
            meshService.stopAdvertising()
        } else {
            meshService.startAdvertising()
        }
    }

    fun onConnectPeerClick(peerId: String) {
        meshService.connect(peerId)
    }

    fun onDisconnectPeerClick(peerId: String) {
        meshService.disconnect(peerId)
    }

    fun onMessageTextChange(text: String) {
        inputState.update { it.copy(messageText = text) }
    }

    fun onSendCurrentChatClick() {
        val chatId = uiState.value.currentChatId ?: return
        val text = uiState.value.messageText

        if (chatId == CHAT_ID_BROADCAST) {
            meshService.sendBroadcastTextMessage(
                text = text,
                replyTo = uiState.value.replyToMessage,
            )
        } else {
            meshService.sendPrivateTextMessage(
                toPeerId = chatId,
                text = text,
                replyTo = uiState.value.replyToMessage,
            )
        }

        inputState.update { it.copy(messageText = "", replyToMessage = null) }
    }



    fun onReplyToMessage(message: ChatMessage) {
        inputState.update { it.copy(replyToMessage = message) }
    }

    fun onCancelReply() {
        inputState.update { it.copy(replyToMessage = null) }
    }

    fun onReactToMessage(message: ChatMessage, emoji: String) {
        meshService.addReaction(
            messageId = message.id,
            chatId = message.chatId,
            emoji = emoji,
        )
    }

    fun onCurrentChatVisible() {
        val chatId = uiState.value.currentChatId ?: return
        meshService.markChatAsRead(chatId)
    }

    fun onClearCurrentChat() {
        val chatId = uiState.value.currentChatId ?: return
        meshService.clearChat(chatId)
    }

    fun onClearAllMessages() {
        meshService.clearAllMessages()
    }

    fun onClearDebugLogs() {
        meshService.clearDebugLogs()
    }

    fun onClearStaleConnections() {
        meshService.clearStaleConnections()
    }

    private fun normalizeChatId(chatId: String, peers: List<PeerUiModel>): String {
        if (chatId == CHAT_ID_BROADCAST) return chatId
        return peers.firstOrNull { it.peerId == chatId }?.nodeId ?: chatId
    }

    private fun buildChatPreviews(
        peers: List<PeerUiModel>,
        contacts: List<com.example.meshsenger.mesh.contacts.MeshContact>,
        messages: List<ChatMessage>,
    ): List<ChatPreviewUiModel> {
        val contactIds = contacts.map { it.nodeId }.filter { it.startsWith("node_") }.toSet()
        val peerIds = peers.map { it.nodeId ?: it.peerId }.toSet()
        val messageChatIds = messages.map { it.chatId }.toSet()
        val chatIds = linkedSetOf(CHAT_ID_BROADCAST).apply {
            addAll(contactIds)
            addAll(peerIds)
            addAll(messageChatIds)
        }

        return chatIds.map { chatId ->
            val lastMessage = messages
                .filter { it.chatId == chatId }
                .maxByOrNull { it.timestamp }
            val peer = peers.firstOrNull { it.nodeId == chatId || it.peerId == chatId }
            val contact = contacts.firstOrNull { it.nodeId == chatId }
            val isBroadcast = chatId == CHAT_ID_BROADCAST
            val title = when {
                isBroadcast -> "Общий чат"
                contact?.displayName != null -> contact.displayName
                peer?.name != null -> peer.name
                else -> chatId
            }
            val connectedDirectly = (peer?.isConnected == true && peer.isWritable) || contact?.isDirectlyConnected == true
            val reachableThroughMesh = !connectedDirectly && chatId != CHAT_ID_BROADCAST && contact != null && peers.any { it.isConnected && it.isWritable } && isRecentlySeen(contact.lastSeenMillis)

            ChatPreviewUiModel(
                chatId = chatId,
                title = title,
                subtitle = lastMessage?.previewText() ?: if (isBroadcast) {
                    val connectedCount = peers.count { it.isConnected }
                    if (connectedCount > 0) {
                        "Сообщение всем mesh-узлам: $connectedCount сосед."
                    } else {
                        "Нет соседей, сообщения сохранятся в ожидании"
                    }
                } else {
                    when {
                        connectedDirectly -> "в сети • напрямую"
                        reachableThroughMesh -> "в сети • через mesh"
                        contact != null -> "не в сети"
                        else -> "пока нет сообщений"
                    }
                },
                lastMessageTime = lastMessage?.timestamp?.formatShortTime().orEmpty(),
                unreadCount = 0,
                avatarText = if (isBroadcast) "🌐" else title.avatarText(),
                avatarEmoji = if (isBroadcast) "🌐" else contact?.avatarEmoji,
                isOnline = if (isBroadcast) peers.any { it.isConnected } else connectedDirectly || reachableThroughMesh,
                isBroadcast = isBroadcast,
            )
        }.sortedWith(
            compareBy<ChatPreviewUiModel> { if (it.isBroadcast) 0 else 1 }
                .thenByDescending { preview ->
                    messages.filter { it.chatId == preview.chatId }.maxOfOrNull { it.timestamp } ?: 0L
                }
                .thenBy { it.title },
        )
    }

    private fun chatTitle(
        chatId: String,
        peers: List<PeerUiModel>,
        contacts: List<com.example.meshsenger.mesh.contacts.MeshContact>,
    ): String {
        if (chatId == CHAT_ID_BROADCAST) return "Общий чат"
        return contacts.firstOrNull { it.nodeId == chatId }?.displayName
            ?: peers.firstOrNull { it.nodeId == chatId || it.peerId == chatId }?.name
            ?: chatId
    }

    private fun ChatMessage.previewText(): String {
        return if (isOutgoing) "Вы: $text" else text
    }

    private fun String.avatarText(): String {
        val value = trim()
        return if (value.isBlank()) "?" else value.first().uppercase()
    }

    private fun Long.formatShortTime(): String {
        val localDateTime = Instant.ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        return TIME_FORMATTER.format(localDateTime)
    }

    private fun isRecentlySeen(lastSeenMillis: Long): Boolean {
        if (lastSeenMillis <= 0L) return false
        return System.currentTimeMillis() - lastSeenMillis <= ONLINE_WINDOW_MS
    }

    private companion object {
        const val CHAT_ID_BROADCAST = "broadcast"
        const val ONLINE_WINDOW_MS = 2 * 60 * 1000L
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

class MeshViewModelFactory(
    private val meshService: MeshService,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MeshViewModel::class.java)) {
            return MeshViewModel(meshService) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private data class MeshState(
    val peers: List<PeerUiModel>,
    val contacts: List<com.example.meshsenger.mesh.contacts.MeshContact>,
    val messages: List<ChatMessage>,
    val status: String,
)

private data class TransportState(
    val isScanning: Boolean,
    val isAdvertising: Boolean,
    val debugLogs: List<DebugLogEntry>,
)
