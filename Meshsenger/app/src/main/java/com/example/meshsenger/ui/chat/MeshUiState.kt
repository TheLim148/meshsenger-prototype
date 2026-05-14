package com.example.meshsenger.ui.chat

import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.ChatPreviewUiModel
import com.example.meshsenger.mesh.model.PeerUiModel
import com.example.meshsenger.mesh.contacts.MeshContact
import com.example.meshsenger.mesh.logging.DebugLogEntry

sealed interface AppScreen {
    data object Onboarding : AppScreen
    data object Permissions : AppScreen
    data object Chats : AppScreen
    data object Contacts : AppScreen
    data object Nearby : AppScreen
    data object Settings : AppScreen
    data object Profile : AppScreen
    data object Debug : AppScreen
    data object About : AppScreen
    data object ContactCode : AppScreen
    data object ImportContact : AppScreen
    data class Chat(val chatId: String) : AppScreen
    data class ChatInfo(val chatId: String) : AppScreen
}

data class MeshUiState(
    val localNodeId: String = "",
    val displayName: String = "",
    val avatarEmoji: String = "🌿",
    val appScreen: AppScreen = AppScreen.Onboarding,
    val peers: List<PeerUiModel> = emptyList(),
    val contacts: List<MeshContact> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val chatPreviews: List<ChatPreviewUiModel> = emptyList(),
    val currentChatMessages: List<ChatMessage> = emptyList(),
    val currentChatId: String? = null,
    val currentChatTitle: String = "",
    val messageText: String = "",
    val replyToMessage: ChatMessage? = null,
    val contactCodeInput: String = "",
    val myContactCode: String = "",
    val status: String = "Готово",
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val debugLogs: List<DebugLogEntry> = emptyList(),
) {
    val canSendMessage: Boolean
        get() = currentChatId != null && messageText.isNotBlank()
}
