package com.example.meshsenger.mesh.persistence

import android.content.Context
import com.example.meshsenger.mesh.contacts.BleEndpoint
import com.example.meshsenger.mesh.contacts.MeshContact
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDisplayName(): String = prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME

    fun getAvatarEmoji(): String = prefs.getString(KEY_AVATAR_EMOJI, DEFAULT_AVATAR_EMOJI) ?: DEFAULT_AVATAR_EMOJI

    fun setAvatarEmoji(value: String) {
        prefs.edit()
            .putString(KEY_AVATAR_EMOJI, normalizeEmoji(value))
            .apply()
    }

    fun setDisplayName(value: String) {
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, value.trim().ifBlank { DEFAULT_DISPLAY_NAME })
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()
    }

    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun isAutoMeshEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_MESH_ENABLED, false)

    fun setAutoMeshEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MESH_ENABLED, value).apply()
    }

    fun loadContacts(): List<MeshContact> {
        val raw = prefs.getString(KEY_CONTACTS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val endpointsArray = item.optJSONArray("endpoints") ?: JSONArray()
                    val endpoints = buildList {
                        for (endpointIndex in 0 until endpointsArray.length()) {
                            val endpoint = endpointsArray.getJSONObject(endpointIndex)
                            add(
                                BleEndpoint(
                                    peerId = endpoint.optString("peerId"),
                                    lastSeenMillis = endpoint.optLong("lastSeenMillis"),
                                    isWritable = false,
                                ),
                            )
                        }
                    }.filter { it.peerId.isNotBlank() }

                    val nodeId = item.optString("nodeId")
                    if (nodeId.isNotBlank()) {
                        add(
                            MeshContact(
                                nodeId = nodeId,
                                displayName = item.optString("displayName").takeIf { it.isNotBlank() },
                                avatarEmoji = item.optString("avatarEmoji").takeIf { it.isNotBlank() },
                                endpoints = endpoints,
                                lastPeerId = item.optString("lastPeerId").takeIf { it.isNotBlank() },
                                isWritable = false,
                                lastSeenMillis = item.optLong("lastSeenMillis", endpoints.maxOfOrNull { it.lastSeenMillis } ?: 0L),
                                discoveredVia = item.optString("discoveredVia", "saved"),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveContacts(contacts: List<MeshContact>) {
        val array = JSONArray()
        contacts
            .filter { it.nodeId.isNotBlank() && it.nodeId.startsWith("node_") }
            .take(MAX_CONTACTS)
            .forEach { contact ->
                val endpoints = JSONArray()
                contact.endpoints.take(5).forEach { endpoint ->
                    endpoints.put(
                        JSONObject()
                            .put("peerId", endpoint.peerId)
                            .put("lastSeenMillis", endpoint.lastSeenMillis),
                    )
                }
                array.put(
                    JSONObject()
                        .put("nodeId", contact.nodeId)
                        .put("displayName", contact.displayName ?: "")
                        .put("avatarEmoji", contact.avatarEmoji ?: "")
                        .put("lastPeerId", contact.lastPeerId ?: "")
                        .put("lastSeenMillis", contact.lastSeenMillis)
                        .put("discoveredVia", contact.discoveredVia)
                        .put("endpoints", endpoints),
                )
            }
        prefs.edit().putString(KEY_CONTACTS_JSON, array.toString()).apply()
    }


    fun loadPendingPackets(): List<PendingPacket> {
        val raw = prefs.getString(KEY_PENDING_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val kind = item.optString("kind")
                    val targetNodeId = item.optString("targetNodeId")
                    val bytesBase64 = item.optString("bytes")
                    if (kind.isNotBlank() && targetNodeId.isNotBlank() && bytesBase64.isNotBlank()) {
                        val sentArray = item.optJSONArray("sentPeerIds") ?: JSONArray()
                        val sent = buildSet {
                            for (sentIndex in 0 until sentArray.length()) add(sentArray.optString(sentIndex))
                        }.filter { it.isNotBlank() }.toSet()
                        add(
                            PendingPacket(
                                kind = kind,
                                targetNodeId = targetNodeId,
                                messageId = item.optString("messageId").takeIf { it.isNotBlank() },
                                bytes = Base64.decode(bytesBase64, Base64.NO_WRAP),
                                sentPeerIds = sent,
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePendingPackets(packets: List<PendingPacket>) {
        val array = JSONArray()
        packets.take(MAX_PENDING_PACKETS).forEach { packet ->
            val sent = JSONArray()
            packet.sentPeerIds.forEach { sent.put(it) }
            array.put(
                JSONObject()
                    .put("kind", packet.kind)
                    .put("targetNodeId", packet.targetNodeId)
                    .put("messageId", packet.messageId ?: "")
                    .put("bytes", Base64.encodeToString(packet.bytes, Base64.NO_WRAP))
                    .put("sentPeerIds", sent),
            )
        }
        prefs.edit().putString(KEY_PENDING_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "meshsenger_app_preferences"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AVATAR_EMOJI = "avatar_emoji"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_AUTO_MESH_ENABLED = "auto_mesh_enabled"
        private const val KEY_CONTACTS_JSON = "contacts_json"
        private const val KEY_PENDING_JSON = "pending_json"
        private const val MAX_CONTACTS = 300
        private const val MAX_PENDING_PACKETS = 1_000
        const val DEFAULT_DISPLAY_NAME = "Мой узел"
        const val DEFAULT_AVATAR_EMOJI = "🌿"

        private fun normalizeEmoji(value: String): String {
            return value.trim().takeIf { it.isNotBlank() }?.take(4) ?: DEFAULT_AVATAR_EMOJI
        }
    }
}
