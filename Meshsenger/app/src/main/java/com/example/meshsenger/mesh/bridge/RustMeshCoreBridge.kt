package com.example.meshsenger.mesh.bridge

import com.example.meshsenger.mesh.logging.MeshLogger
import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.MeshAction
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import com.example.meshsenger.mesh.model.MessageTextCodec
import uniffi.mesh_core.ApiAction as RustApiAction
import uniffi.mesh_core.ApiChatMessage
import uniffi.mesh_core.MeshCoreApi
import java.util.concurrent.ConcurrentHashMap

class RustMeshCoreBridge(
    private val localNodeId: String,
) : MeshCoreBridge {
    private val core = MeshCoreApi(localNodeId)
    private val peerIdToNodeId = ConcurrentHashMap<String, String>()
    private val nodeIdToPeerId = ConcurrentHashMap<String, String>()

    init {
        MeshLogger.info(TAG, "RustMeshCoreBridge создан. localNodeId=$localNodeId")
    }

    override fun createPrivateTextMessage(to: String, text: String): ByteArray {
        val toNodeId = resolveNodeIdForPeer(to)
        MeshLogger.info(TAG, "createPrivateTextMessage(toPeer=$to, toNode=$toNodeId, textLength=${text.length})")

        val safeText = sanitizeForRust(text)
        val result = core.createPrivateTextBytes(
            to = toNodeId,
            text = safeText,
            timestamp = System.currentTimeMillis().toULong(),
        )

        result.error?.let { error ->
            throw IllegalStateException("Rust createPrivateTextBytes error: $error")
        }

        return result.bytes
    }

    override fun createBroadcastTextMessage(text: String): ByteArray {
        MeshLogger.info(TAG, "createBroadcastTextMessage(textLength=${text.length})")

        val safeText = sanitizeForRust(text)
        val result = core.createBroadcastTextBytes(
            text = safeText,
            timestamp = System.currentTimeMillis().toULong(),
        )

        result.error?.let { error ->
            throw IllegalStateException("Rust createBroadcastTextBytes error: $error")
        }

        return result.bytes
    }

    override fun handleIncomingMessage(fromPeerId: String, bytes: ByteArray): List<MeshAction> {
        val fromNodeId = resolveNodeIdForPeer(fromPeerId)
        MeshLogger.info(TAG, "handleIncomingMessage(fromPeer=$fromPeerId, fromNode=$fromNodeId, bytes=${bytes.size})")

        return core.handleIncomingBytes(
            fromPeerId = fromNodeId,
            bytes = bytes,
        ).map { action -> action.toMeshAction(sourcePeerId = fromPeerId) }
    }

    override fun getPendingMessagesForPeer(peerId: String): List<ByteArray> {
        val nodeId = resolveNodeIdForPeer(peerId)
        val pending = core.takePendingBytesForPeer(nodeId)
        MeshLogger.info(TAG, "getPendingMessagesForPeer(peer=$peerId, node=$nodeId) -> ${pending.size}")
        return pending
    }

    override fun markPeerConnected(peerId: String) {
        val nodeId = resolveNodeIdForPeer(peerId)
        MeshLogger.info(TAG, "markPeerConnected(peer=$peerId, node=$nodeId)")
        core.markPeerConnected(nodeId)
    }

    override fun markPeerDisconnected(peerId: String) {
        val nodeId = resolveNodeIdForPeer(peerId)
        MeshLogger.info(TAG, "markPeerDisconnected(peer=$peerId, node=$nodeId)")
        core.markPeerDisconnected(nodeId)
    }

    override fun rememberPeerNode(peerId: String, nodeId: String) {
        if (peerId.isBlank() || nodeId.isBlank()) return

        peerIdToNodeId[peerId] = nodeId
        nodeIdToPeerId[nodeId] = peerId
        MeshLogger.info(TAG, "rememberPeerNode(peer=$peerId, node=$nodeId)")
    }

    override fun resolveNodeIdForPeer(peerId: String): String {
        return peerIdToNodeId[peerId] ?: peerId
    }

    override fun resolvePeerIdForNode(nodeId: String): String {
        return nodeIdToPeerId[nodeId] ?: nodeId
    }

    private fun RustApiAction.toMeshAction(sourcePeerId: String): MeshAction {
        return when (this) {
            is RustApiAction.ShowMessage -> MeshAction.ShowMessage(message.toChatMessage(sourcePeerId))
            is RustApiAction.ForwardMessage -> MeshAction.ForwardMessage(
                targetPeerIds = targetPeerIds,
                bytes = bytes,
            )
            is RustApiAction.DropMessage -> MeshAction.DropMessage
            is RustApiAction.Error -> MeshAction.Error(message)
        }
    }

    private fun sanitizeForRust(value: String): String {
        // На некоторых клавиатурах можно получить битую UTF-16-последовательность
        // (например, половину emoji). UniFFI строго кодирует String в UTF-8 и может упасть
        // с MalformedInputException. Перекодирование через UTF-8 заменяет такие символы безопасно.
        return String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
    }

    private fun ApiChatMessage.toChatMessage(sourcePeerId: String): ChatMessage {
        val normalizedChatId = when (chatType) {
            CHAT_TYPE_BROADCAST -> CHAT_ID_BROADCAST
            else -> from.takeIf { it.isNotBlank() } ?: sourcePeerId
        }

        val parsedText = MessageTextCodec.decode(text)

        return ChatMessage(
            id = messageId,
            chatId = normalizedChatId,
            from = from,
            to = to,
            text = parsedText.text,
            timestamp = normalizeTimestampMillis(timestamp.toLong()),
            isOutgoing = false,
            status = MessageDeliveryStatus.Delivered,
            replyToMessageId = parsedText.replyToMessageId,
            replyToSender = parsedText.replyToSender,
            replyToText = parsedText.replyToText,
        )
    }

    private fun normalizeTimestampMillis(value: Long): Long {
        // Rust получает timestamp в миллисекундах, но старые/тестовые сообщения могли быть в секундах.
        return if (value > MAX_REASONABLE_SECONDS_TIMESTAMP) value else value * 1000L
    }

    private companion object {
        const val TAG = "RustMeshCoreBridge"
        const val CHAT_TYPE_BROADCAST = "broadcast"
        const val CHAT_ID_BROADCAST = "broadcast"
        const val MAX_REASONABLE_SECONDS_TIMESTAMP = 10_000_000_000L
    }
}
