package com.example.meshsenger.mesh.repository

import android.content.Context
import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import com.example.meshsenger.mesh.logging.MeshLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PersistentMessageRepository(context: Context) : MessageRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _messages = MutableStateFlow(loadMessages())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    override fun saveMessage(message: ChatMessage) {
        val currentMessages = _messages.value
        if (currentMessages.any { it.id == message.id }) return
        _messages.value = (currentMessages + message).sortedForDisplay()
        persist()
    }

    override fun updateMessageStatus(messageId: String, status: MessageDeliveryStatus) {
        val updated = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(status = message.status.bestOf(status))
            } else {
                message
            }
        }
        if (updated != _messages.value) {
            _messages.value = updated.sortedForDisplay()
            persist()
        }
    }

    override fun updateMessageReaction(messageId: String, emoji: String, delta: Int) {
        val safeEmoji = emoji.trim().take(8)
        if (safeEmoji.isBlank()) return
        val updated = _messages.value.map { message ->
            if (message.id == messageId) {
                val nextCount = ((message.reactions[safeEmoji] ?: 0) + delta).coerceAtLeast(0)
                val nextReactions = if (nextCount == 0) {
                    message.reactions - safeEmoji
                } else {
                    message.reactions + (safeEmoji to nextCount)
                }
                message.copy(reactions = nextReactions)
            } else {
                message
            }
        }
        if (updated != _messages.value) {
            _messages.value = updated.sortedForDisplay()
            persist()
        }
    }

    override fun getMessages(chatId: String): List<ChatMessage> {
        return _messages.value.filter { it.chatId == chatId }.sortedForDisplay()
    }

    override fun clearChat(chatId: String) {
        _messages.value = _messages.value.filterNot { it.chatId == chatId }.sortedForDisplay()
        persist()
    }

    override fun clearAll() {
        _messages.value = emptyList()
        persist()
    }

    private fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ChatMessage(
                            id = item.getString("id"),
                            chatId = item.getString("chatId"),
                            from = item.getString("from"),
                            to = item.optString("to").takeIf { it.isNotBlank() },
                            text = item.getString("text"),
                            timestamp = normalizeTimestampMillis(item.optLong("timestamp")),
                            isOutgoing = item.optBoolean("isOutgoing"),
                            status = runCatching {
                                MessageDeliveryStatus.valueOf(item.optString("status"))
                            }.getOrDefault(MessageDeliveryStatus.Sent),
                            replyToMessageId = item.optString("replyToMessageId").takeIf { it.isNotBlank() },
                            replyToSender = item.optString("replyToSender").takeIf { it.isNotBlank() },
                            replyToText = item.optString("replyToText").takeIf { it.isNotBlank() },
                            reactions = item.optJSONObject("reactions")?.toReactionMap().orEmpty(),
                        )
                    )
                }
            }.sortedForDisplay()
        }.onFailure { error ->
            MeshLogger.error(TAG, "Не удалось прочитать историю сообщений", error)
        }.getOrDefault(emptyList())
    }


    private fun normalizeTimestampMillis(value: Long): Long {
        return if (value > 10_000_000_000L) value else value * 1000L
    }

    private fun JSONObject.toReactionMap(): Map<String, Int> {
        return keys().asSequence()
            .associateWith { key -> optInt(key) }
            .filterValues { count -> count > 0 }
    }

    private fun MessageDeliveryStatus.rank(): Int {
        return when (this) {
            MessageDeliveryStatus.Pending -> 0
            MessageDeliveryStatus.Relayed -> 1
            MessageDeliveryStatus.Sent -> 2
            MessageDeliveryStatus.Delivered, MessageDeliveryStatus.Received -> 3
            MessageDeliveryStatus.Read -> 4
            MessageDeliveryStatus.Failed -> -1
        }
    }

    private fun MessageDeliveryStatus.bestOf(other: MessageDeliveryStatus): MessageDeliveryStatus {
        if (this == MessageDeliveryStatus.Failed && other.rank() < MessageDeliveryStatus.Delivered.rank()) return this
        return if (other.rank() >= this.rank()) other.normalized() else this.normalized()
    }

    private fun MessageDeliveryStatus.normalized(): MessageDeliveryStatus {
        return if (this == MessageDeliveryStatus.Received) MessageDeliveryStatus.Delivered else this
    }

    private fun persist() {
        val array = JSONArray()
        _messages.value.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("chatId", message.chatId)
                    .put("from", message.from)
                    .put("to", message.to ?: "")
                    .put("text", message.text)
                    .put("timestamp", message.timestamp)
                    .put("isOutgoing", message.isOutgoing)
                    .put("status", message.status.name)
                    .put("replyToMessageId", message.replyToMessageId ?: "")
                    .put("replyToSender", message.replyToSender ?: "")
                    .put("replyToText", message.replyToText ?: "")
                    .put("reactions", JSONObject().also { reactionsJson ->
                        message.reactions.forEach { (emoji, count) -> reactionsJson.put(emoji, count) }
                    }),
            )
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    private fun List<ChatMessage>.sortedForDisplay(): List<ChatMessage> {
        return sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }

    private companion object {
        const val TAG = "PersistentMessageRepository"
        const val PREFS_NAME = "meshsenger_messages"
        const val KEY_MESSAGES = "messages_json"
        const val MAX_MESSAGES = 2_000
    }
}
