package com.example.meshsenger.mesh.contacts

data class MeshContact(
    val nodeId: String,
    val displayName: String?,
    val avatarEmoji: String? = null,
    val endpoints: List<BleEndpoint> = emptyList(),
    val lastPeerId: String? = null,
    val isWritable: Boolean = false,
    val lastSeenMillis: Long = System.currentTimeMillis(),
    val discoveredVia: String = "mesh",
) {
    val bestName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: nodeId

    val isDirectlyConnected: Boolean
        get() = endpoints.any { it.isWritable }
}

data class BleEndpoint(
    val peerId: String,
    val lastSeenMillis: Long,
    val isWritable: Boolean,
)
