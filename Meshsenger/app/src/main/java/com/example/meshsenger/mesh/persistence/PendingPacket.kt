package com.example.meshsenger.mesh.persistence

data class PendingPacket(
    val kind: String,
    val targetNodeId: String,
    val messageId: String?,
    val bytes: ByteArray,
    val sentPeerIds: Set<String> = emptySet(),
) {
    companion object {
        const val KIND_DIRECT = "direct"
        const val KIND_FORWARD = "forward"
    }
}
