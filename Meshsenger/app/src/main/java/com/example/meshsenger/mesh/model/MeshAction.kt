package com.example.meshsenger.mesh.model

sealed interface MeshAction {
    data class ShowMessage(
        val message: ChatMessage,
    ) : MeshAction

    data class ForwardMessage(
        val targetPeerIds: List<String>,
        val bytes: ByteArray,
    ) : MeshAction

    data class SaveMessage(
        val message: ChatMessage,
    ) : MeshAction

    data object DropMessage : MeshAction

    data class Error(
        val message: String,
    ) : MeshAction
}
