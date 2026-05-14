package com.example.meshsenger.mesh.model

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedMessageText(
    val text: String,
    val replyToMessageId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
)

object MessageTextCodec {
    private const val REPLY_PREFIX = "⟦meshsenger_reply:"
    private const val REPLY_SUFFIX = "⟧\n"
    private const val MAX_QUOTE_LENGTH = 180

    fun encode(text: String, replyTo: ChatMessage?): String {
        val reply = replyTo ?: return text
        val payload = JSONObject()
            .put("id", reply.id)
            .put("sender", reply.from)
            .put("text", reply.text.take(MAX_QUOTE_LENGTH))
            .toString()
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "$REPLY_PREFIX$encoded$REPLY_SUFFIX$text"
    }

    fun decode(raw: String): ParsedMessageText {
        if (!raw.startsWith(REPLY_PREFIX)) return ParsedMessageText(text = raw)
        val suffixIndex = raw.indexOf(REPLY_SUFFIX, startIndex = REPLY_PREFIX.length)
        if (suffixIndex < 0) return ParsedMessageText(text = raw)

        val encoded = raw.substring(REPLY_PREFIX.length, suffixIndex)
        val body = raw.substring(suffixIndex + REPLY_SUFFIX.length)
        return runCatching {
            val jsonText = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
            val json = JSONObject(jsonText)
            ParsedMessageText(
                text = body,
                replyToMessageId = json.optString("id").takeIf { it.isNotBlank() },
                replyToSender = json.optString("sender").takeIf { it.isNotBlank() },
                replyToText = json.optString("text").takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            ParsedMessageText(text = raw)
        }
    }
}
