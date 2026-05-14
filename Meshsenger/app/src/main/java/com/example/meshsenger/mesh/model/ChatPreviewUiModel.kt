package com.example.meshsenger.mesh.model

data class ChatPreviewUiModel(
    val chatId: String,
    val title: String,
    val subtitle: String,
    val lastMessageTime: String,
    val unreadCount: Int,
    val avatarText: String,
    val isOnline: Boolean,
    val isBroadcast: Boolean = false,
    val avatarEmoji: String? = null,
)
