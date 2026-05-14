package com.example.meshsenger.mesh.model

data class ChatMessage(
    val id: String,
    val chatId: String,
    val from: String,
    val to: String?,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageDeliveryStatus = MessageDeliveryStatus.Sent,
    val replyToMessageId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
)
