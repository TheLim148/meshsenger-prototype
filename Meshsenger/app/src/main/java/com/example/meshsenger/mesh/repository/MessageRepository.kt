package com.example.meshsenger.mesh.repository

import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import kotlinx.coroutines.flow.StateFlow

interface MessageRepository {
    val messages: StateFlow<List<ChatMessage>>

    fun saveMessage(message: ChatMessage)
    fun updateMessageStatus(messageId: String, status: MessageDeliveryStatus)
    fun updateMessageReaction(messageId: String, emoji: String, delta: Int = 1)
    fun getMessages(chatId: String): List<ChatMessage>
    fun clearChat(chatId: String)
    fun clearAll()
}
