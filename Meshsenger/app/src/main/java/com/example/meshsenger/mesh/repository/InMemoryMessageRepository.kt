package com.example.meshsenger.mesh.repository

import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryMessageRepository : MessageRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    override fun saveMessage(message: ChatMessage) {
        val currentMessages = _messages.value

        if (currentMessages.any { it.id == message.id }) {
            return
        }

        _messages.value = (currentMessages + message).sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }

    override fun updateMessageStatus(messageId: String, status: MessageDeliveryStatus) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(status = message.status.bestOf(status))
            } else {
                message
            }
        }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }

    override fun updateMessageReaction(messageId: String, emoji: String, delta: Int) {
        val safeEmoji = emoji.trim().take(8)
        if (safeEmoji.isBlank()) return
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                val nextCount = ((message.reactions[safeEmoji] ?: 0) + delta).coerceAtLeast(0)
                val nextReactions = if (nextCount == 0) message.reactions - safeEmoji else message.reactions + (safeEmoji to nextCount)
                message.copy(reactions = nextReactions)
            } else {
                message
            }
        }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }

    private fun MessageDeliveryStatus.rank(): Int = when (this) {
        MessageDeliveryStatus.Pending -> 0
        MessageDeliveryStatus.Relayed -> 1
        MessageDeliveryStatus.Sent -> 2
        MessageDeliveryStatus.Delivered, MessageDeliveryStatus.Received -> 3
        MessageDeliveryStatus.Read -> 4
        MessageDeliveryStatus.Failed -> -1
    }

    private fun MessageDeliveryStatus.bestOf(other: MessageDeliveryStatus): MessageDeliveryStatus {
        return if (other.rank() >= this.rank()) other.normalized() else this.normalized()
    }

    private fun MessageDeliveryStatus.normalized(): MessageDeliveryStatus {
        return if (this == MessageDeliveryStatus.Received) MessageDeliveryStatus.Delivered else this
    }

    override fun getMessages(chatId: String): List<ChatMessage> {
        return _messages.value.filter { it.chatId == chatId }
    }

    override fun clearChat(chatId: String) {
        _messages.value = _messages.value.filterNot { it.chatId == chatId }
    }

    override fun clearAll() {
        _messages.value = emptyList()
    }

}
