package com.example.meshsenger.mesh.service

import android.content.Context
import android.provider.Settings
import com.example.meshsenger.mesh.bluetooth.BleGattTransport
import com.example.meshsenger.mesh.bridge.RustMeshCoreBridge
import com.example.meshsenger.mesh.persistence.AppPreferences
import com.example.meshsenger.mesh.repository.PersistentMessageRepository
import com.example.meshsenger.mesh.notifications.MessageNotifier

object MeshServiceFactory {
    fun create(context: Context): MeshService {
        val localNodeId = createLocalNodeId(context)
        val preferences = AppPreferences(context)
        val repository = PersistentMessageRepository(context)
        val bridge = RustMeshCoreBridge(localNodeId = localNodeId)
        val transport = BleGattTransport(context)
        val notifier = MessageNotifier(context)

        return MeshService(
            localNodeId = localNodeId,
            initialDisplayName = preferences.getDisplayName(),
            initialAvatarEmoji = preferences.getAvatarEmoji(),
            onboardingDone = preferences.isOnboardingDone(),
            autoMeshEnabled = preferences.isAutoMeshEnabled(),
            initialContacts = preferences.loadContacts(),
            initialPendingPackets = preferences.loadPendingPackets(),
            onDisplayNameSaved = preferences::setDisplayName,
            onAvatarEmojiSaved = preferences::setAvatarEmoji,
            onAutoMeshEnabledSaved = preferences::setAutoMeshEnabled,
            onContactsSaved = preferences::saveContacts,
            onPendingPacketsSaved = preferences::savePendingPackets,
            bluetoothTransport = transport,
            meshCoreBridge = bridge,
            messageRepository = repository,
            messageNotifier = notifier,
        )
    }

    private fun createLocalNodeId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )

        val suffix = androidId
            ?.takeIf { it.isNotBlank() }
            ?.takeLast(8)
            ?: "local"

        return "node_$suffix"
    }
}
