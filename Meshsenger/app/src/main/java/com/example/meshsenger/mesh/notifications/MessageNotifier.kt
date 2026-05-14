package com.example.meshsenger.mesh.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.meshsenger.MainActivity
import com.example.meshsenger.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

class MessageNotifier(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val inboxByChatId = ConcurrentHashMap<String, ArrayDeque<NotificationLine>>()

    init {
        ensureChannel()
    }

    @SuppressLint("MissingPermission")
    fun notifyIncomingMessage(
        chatId: String,
        senderName: String,
        text: String,
        isBroadcast: Boolean,
        messageId: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notificationId = notificationIdForChat(chatId)
        val lineText = text.ifBlank { "Новое сообщение" }
        val lines = inboxByChatId.getOrPut(chatId) { ArrayDeque() }
        lines.addLast(NotificationLine(senderName = senderName, text = lineText, isBroadcast = isBroadcast))
        while (lines.size > MAX_LINES_PER_CHAT) {
            lines.removeFirst()
        }

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (isBroadcast) {
            "Общий чат"
        } else {
            senderName
        }

        val snapshot = lines.toList()
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText(if (snapshot.size == 1) "Meshsenger" else "${snapshot.size} новых сообщений")

        snapshot.forEach { line ->
            val renderedLine = if (line.isBroadcast) {
                "${line.senderName}: ${line.text}"
            } else {
                line.text
            }
            style.addLine(renderedLine)
        }

        val contentText = if (snapshot.size == 1) {
            if (isBroadcast) "$senderName: $lineText" else lineText
        } else {
            "${snapshot.size} новых сообщений"
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(snapshot.size > 1)
            .setNumber(snapshot.size)
            .setContentIntent(pendingIntent)
            .build()

        // Используем стабильный id на чат. Поэтому новые сообщения от одного человека
        // не создают стопку уведомлений, а обновляют одно уведомление как в мессенджерах.
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
    }

    fun clearChatNotification(chatId: String) {
        inboxByChatId.remove(chatId)
        NotificationManagerCompat.from(appContext).cancel(notificationIdForChat(chatId))
    }

    private fun notificationIdForChat(chatId: String): Int {
        return (NOTIFICATION_BASE_ID + chatId.hashCode()).absoluteValue
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_MESSAGES)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Сообщения Meshsenger",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления о новых Bluetooth mesh-сообщениях"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private data class NotificationLine(
        val senderName: String,
        val text: String,
        val isBroadcast: Boolean,
    )

    companion object {
        const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val CHANNEL_MESSAGES = "meshsenger_messages"
        private const val NOTIFICATION_BASE_ID = 20_000
        private const val MAX_LINES_PER_CHAT = 6
    }
}
