package com.example.meshsenger.mesh.contacts

import com.example.meshsenger.mesh.logging.MeshLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Стабильная адресная книга mesh-узлов.
 *
 * Главный id для приложения — Rust nodeId. BLE peerId является только текущим
 * транспортным endpoint-ом и может меняться даже для одного и того же телефона.
 */
class ContactManager(
    private val localNodeId: String,
    initialContacts: List<MeshContact> = emptyList(),
    private val onContactsChanged: (List<MeshContact>) -> Unit = {},
) {
    private val contactsByNodeId = ConcurrentHashMap<String, MeshContact>()
    private val peerIdToNodeId = ConcurrentHashMap<String, String>()

    init {
        initialContacts
            .filter { isValidNodeId(it.nodeId) && it.nodeId != localNodeId }
            .forEach { contact ->
                contactsByNodeId[contact.nodeId] = contact.copy(
                    isWritable = false,
                    endpoints = contact.endpoints.map { it.copy(isWritable = false) },
                )
                contact.endpoints.forEach { endpoint -> peerIdToNodeId[endpoint.peerId] = contact.nodeId }
            }
        publish()
    }

    fun rememberDiscoveredEndpoint(peerId: String, displayName: String?) {
        if (peerId.isBlank()) return
        // До hello/hello_ack мы знаем только временный BLE endpoint.
        // Не создаём из него контакт, иначе в чатах появятся MAC-адреса.
        val knownNodeId = peerIdToNodeId[peerId] ?: return
        upsert(
            nodeId = knownNodeId,
            displayName = displayName,
            peerId = peerId,
            isWritable = false,
            discoveredVia = "ble",
        )
    }

    fun rememberKnownNode(nodeId: String, displayName: String?, avatarEmoji: String? = null, discoveredVia: String = "announce") {
        if (!isValidNodeId(nodeId) || nodeId == localNodeId) {
            MeshLogger.warning(TAG, "Ignored invalid mesh contact nodeId=$nodeId via=$discoveredVia")
            return
        }
        contactsByNodeId.compute(nodeId) { _, old ->
            MeshContact(
                nodeId = nodeId,
                displayName = displayName ?: old?.displayName,
                avatarEmoji = avatarEmoji ?: old?.avatarEmoji,
                endpoints = old?.endpoints.orEmpty(),
                lastPeerId = old?.lastPeerId,
                isWritable = old?.isWritable == true,
                lastSeenMillis = System.currentTimeMillis(),
                discoveredVia = discoveredVia,
            )
        }
        publish()
    }

    /**
     * @return старые peerId того же nodeId, которые теперь можно убрать из UI/закрыть.
     */
    fun rememberHandshake(peerId: String, nodeId: String, displayName: String?, avatarEmoji: String? = null): List<String> {
        if (peerId.isBlank() || !isValidNodeId(nodeId) || nodeId == localNodeId) return emptyList()

        val oldContact = contactsByNodeId[nodeId]
        val stalePeerIds = oldContact
            ?.endpoints
            .orEmpty()
            .map { it.peerId }
            .filter { it != peerId }

        val previousNodeForPeer = peerIdToNodeId.put(peerId, nodeId)
        if (previousNodeForPeer != null && previousNodeForPeer != nodeId) {
            contactsByNodeId.remove(previousNodeForPeer)
        }

        stalePeerIds.forEach { stalePeerId -> peerIdToNodeId.remove(stalePeerId) }
        peerIdToNodeId[peerId] = nodeId

        upsert(
            nodeId = nodeId,
            displayName = displayName,
            avatarEmoji = avatarEmoji,
            peerId = peerId,
            isWritable = true,
            replaceOldEndpoints = true,
            discoveredVia = "handshake",
        )
        MeshLogger.info(TAG, "rememberHandshake(peer=$peerId, node=$nodeId, name=${displayName ?: "null"}, stale=$stalePeerIds)")
        return stalePeerIds
    }

    fun markPeerWritable(peerId: String, isWritable: Boolean) {
        val nodeId = peerIdToNodeId[peerId] ?: peerId
        val existing = contactsByNodeId[nodeId] ?: return
        contactsByNodeId[nodeId] = existing.copy(
            isWritable = isWritable,
            lastSeenMillis = System.currentTimeMillis(),
            endpoints = existing.endpoints.map { endpoint ->
                if (endpoint.peerId == peerId) {
                    endpoint.copy(isWritable = isWritable, lastSeenMillis = System.currentTimeMillis())
                } else {
                    endpoint.copy(isWritable = false)
                }
            },
        )
        publish()
    }

    fun disconnectPeer(peerId: String) {
        val nodeId = peerIdToNodeId[peerId] ?: peerId
        val existing = contactsByNodeId[nodeId] ?: return
        contactsByNodeId[nodeId] = existing.copy(
            isWritable = existing.endpoints.any { it.peerId != peerId && it.isWritable },
            endpoints = existing.endpoints.map { endpoint ->
                if (endpoint.peerId == peerId) endpoint.copy(isWritable = false) else endpoint
            },
        )
        publish()
    }

    fun hasWritableNode(nodeId: String): Boolean {
        return contactsByNodeId[nodeId]?.endpoints?.any { it.isWritable } == true
    }

    fun clearStaleEndpoints() {
        val now = System.currentTimeMillis()
        contactsByNodeId.replaceAll { _, contact ->
            val liveEndpoints = contact.endpoints.filter { it.isWritable }
            val recentOffline = contact.endpoints
                .filter { !it.isWritable && now - it.lastSeenMillis < RECENT_OFFLINE_KEEP_MS }
            val endpoints = (liveEndpoints + recentOffline).distinctBy { it.peerId }.take(3)
            contact.copy(
                endpoints = endpoints,
                lastPeerId = liveEndpoints.firstOrNull()?.peerId ?: endpoints.firstOrNull()?.peerId,
                isWritable = liveEndpoints.isNotEmpty(),
            )
        }
        val validPeers = contactsByNodeId.values.flatMap { it.endpoints }.map { it.peerId }.toSet()
        peerIdToNodeId.keys.filterNot { it in validPeers }.forEach { peerIdToNodeId.remove(it) }
        publish()
    }

    fun nodeIdForPeer(peerId: String): String = peerIdToNodeId[peerId] ?: peerId

    /**
     * Возвращает стабильный node_id только если peer уже прошёл hello/hello_ack.
     * Важно: BLE peerId нельзя использовать как node_id, иначе в Rust и контакты
     * начинают попадать адреса вида 77:24:... и ломают маршрутизацию.
     */
    fun knownNodeIdForPeer(peerId: String): String? = peerIdToNodeId[peerId]?.takeIf { isValidNodeId(it) }

    fun peerIdForNode(nodeId: String): String? {
        val contact = contactsByNodeId[nodeId] ?: return null
        return contact.endpoints.firstOrNull { it.isWritable }?.peerId ?: contact.lastPeerId
    }

    fun writablePeerIds(): List<String> {
        return contactsByNodeId.values
            .filter { it.isWritable && it.nodeId != localNodeId }
            .mapNotNull { contact -> contact.endpoints.firstOrNull { it.isWritable }?.peerId }
            .distinct()
    }

    fun snapshot(): List<MeshContact> = contactsByNodeId.values
        .filter { it.nodeId != localNodeId }
        .sortedWith(compareByDescending<MeshContact> { it.isWritable }.thenBy { it.bestName })

    private fun upsert(
        nodeId: String,
        displayName: String?,
        avatarEmoji: String? = null,
        peerId: String,
        isWritable: Boolean,
        replaceOldEndpoints: Boolean = false,
        discoveredVia: String = "mesh",
    ) {
        if (nodeId == localNodeId) return
        contactsByNodeId.compute(nodeId) { _, old ->
            val oldEndpoints = if (replaceOldEndpoints) {
                emptyList()
            } else {
                old?.endpoints.orEmpty().filterNot { it.peerId == peerId }
            }
            val endpoint = BleEndpoint(
                peerId = peerId,
                lastSeenMillis = System.currentTimeMillis(),
                isWritable = isWritable,
            )
            MeshContact(
                nodeId = nodeId,
                displayName = displayName ?: old?.displayName,
                avatarEmoji = avatarEmoji ?: old?.avatarEmoji,
                endpoints = (listOf(endpoint) + oldEndpoints).take(5),
                lastPeerId = peerId,
                isWritable = isWritable || oldEndpoints.any { it.isWritable },
                lastSeenMillis = System.currentTimeMillis(),
                discoveredVia = discoveredVia,
            )
        }
        publish()
    }

    private fun publish() {
        onContactsChanged(snapshot())
    }

    private fun isValidNodeId(value: String?): Boolean {
        return !value.isNullOrBlank() && value.startsWith("node_")
    }

    private companion object {
        const val TAG = "ContactManager"
        const val RECENT_OFFLINE_KEEP_MS = 10 * 60 * 1000L
    }
}
