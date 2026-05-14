package com.example.meshsenger.mesh.bridge

import com.example.meshsenger.mesh.model.MeshAction

interface MeshCoreBridge {
    fun createPrivateTextMessage(to: String, text: String): ByteArray

    fun createBroadcastTextMessage(text: String): ByteArray

    fun handleIncomingMessage(fromPeerId: String, bytes: ByteArray): List<MeshAction>

    fun getPendingMessagesForPeer(peerId: String): List<ByteArray>

    fun markPeerConnected(peerId: String)

    fun markPeerDisconnected(peerId: String)

    /**
     * BLE peerId и mesh nodeId — разные сущности.
     * Реальная связь peerId ↔ nodeId появляется после hello/hello_ack handshake.
     */
    fun rememberPeerNode(peerId: String, nodeId: String) = Unit

    fun resolveNodeIdForPeer(peerId: String): String = peerId

    fun resolvePeerIdForNode(nodeId: String): String = nodeId
}
