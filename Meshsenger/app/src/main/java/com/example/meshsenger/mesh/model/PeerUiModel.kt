package com.example.meshsenger.mesh.model

data class PeerUiModel(
    val peerId: String,
    val name: String?,
    val isConnected: Boolean = false,
    val nodeId: String? = null,
    val isWritable: Boolean = false,
    val connectionState: String = if (isConnected) "готов" else "найден",
)
