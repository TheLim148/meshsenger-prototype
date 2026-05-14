package com.example.meshsenger.ui.chat

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.meshsenger.mesh.model.ChatMessage
import com.example.meshsenger.mesh.model.ChatPreviewUiModel
import com.example.meshsenger.mesh.model.MessageDeliveryStatus
import com.example.meshsenger.mesh.model.PeerUiModel
import com.example.meshsenger.mesh.logging.CloudLogUploader
import com.example.meshsenger.mesh.logging.DebugLogLevel
import com.example.meshsenger.mesh.logging.MeshLogger
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@Composable
fun MeshAppRoute(
    viewModel: MeshViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val bluetoothPermissions = remember { requiredBluetoothPermissions() }
    var hasBluetoothPermissions by remember {
        mutableStateOf(hasAllPermissions(context, bluetoothPermissions))
    }
    var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasBluetoothPermissions = bluetoothPermissions.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        val action = pendingBluetoothAction
        pendingBluetoothAction = null

        if (hasBluetoothPermissions) {
            viewModel.onPermissionsReady()
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                "Без разрешений Bluetooth поиск и подключение не работают",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* уведомления не блокируют Bluetooth-работу приложения */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun runWithBluetoothPermissions(action: () -> Unit) {
        hasBluetoothPermissions = hasAllPermissions(context, bluetoothPermissions)
        if (hasBluetoothPermissions) {
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(bluetoothPermissions)
        }
    }

    BackHandler(
        enabled = uiState.appScreen != AppScreen.Chats && uiState.appScreen != AppScreen.Onboarding,
    ) {
        viewModel.onBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val effectiveScreen = if (
            uiState.appScreen != AppScreen.Onboarding &&
            uiState.appScreen != AppScreen.Permissions &&
            !hasBluetoothPermissions
        ) {
            AppScreen.Permissions
        } else {
            uiState.appScreen
        }

        when (val screen = effectiveScreen) {
            AppScreen.Onboarding -> OnboardingScreen(
                uiState = uiState,
                onDisplayNameChange = viewModel::onDisplayNameChange,
                onContinueClick = viewModel::onCompleteOnboarding,
            )

            AppScreen.Permissions -> PermissionsScreen(
                uiState = uiState,
                hasPermissions = hasBluetoothPermissions,
                onRequestPermissionsClick = {
                    pendingBluetoothAction = null
                    bluetoothPermissionLauncher.launch(bluetoothPermissions)
                },
                onContinueClick = {
                    hasBluetoothPermissions = hasAllPermissions(context, bluetoothPermissions)
                    if (hasBluetoothPermissions) {
                        viewModel.onPermissionsReady()
                    } else {
                        bluetoothPermissionLauncher.launch(bluetoothPermissions)
                    }
                },
            )

            AppScreen.Chats -> ChatListScreen(
                uiState = uiState,
                onOpenChat = viewModel::onOpenChat,
                onOpenContacts = viewModel::onOpenContacts,
                onOpenNearby = viewModel::onOpenNearby,
                onOpenSettings = viewModel::onOpenSettings,
            )

            AppScreen.Contacts -> ContactsScreen(
                uiState = uiState,
                onOpenChat = viewModel::onOpenChat,
                onOpenContactCode = viewModel::onOpenContactCode,
                onOpenImportContact = viewModel::onOpenImportContact,
                onOpenChats = viewModel::onOpenChats,
                onOpenContacts = viewModel::onOpenContacts,
                onOpenNearby = viewModel::onOpenNearby,
                onOpenSettings = viewModel::onOpenSettings,
            )

            AppScreen.Nearby -> NearbyPeersScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onOpenChats = viewModel::onOpenChats,
                onOpenContacts = viewModel::onOpenContacts,
                onOpenNearby = viewModel::onOpenNearby,
                onOpenSettings = viewModel::onOpenSettings,
                onStartScanClick = { runWithBluetoothPermissions { viewModel.onStartScanClick() } },
                onStartAdvertisingClick = { runWithBluetoothPermissions { viewModel.onStartAdvertisingClick() } },
                onClearStaleConnections = viewModel::onClearStaleConnections,
                onConnectPeerClick = viewModel::onConnectPeerClick,
                onDisconnectPeerClick = viewModel::onDisconnectPeerClick,
                onOpenChat = viewModel::onOpenChat,
            )

            AppScreen.Settings -> SettingsScreen(
                uiState = uiState,
                onOpenChats = viewModel::onOpenChats,
                onOpenContacts = viewModel::onOpenContacts,
                onOpenNearby = viewModel::onOpenNearby,
                onOpenSettings = viewModel::onOpenSettings,
                onOpenProfile = viewModel::onOpenProfile,
                onOpenDebug = viewModel::onOpenDebug,
                onOpenAbout = viewModel::onOpenAbout,
                onOpenContactCode = viewModel::onOpenContactCode,
                onOpenImportContact = viewModel::onOpenImportContact,
                onClearAllMessages = viewModel::onClearAllMessages,
            )

            AppScreen.Profile -> ProfileScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onDisplayNameChange = viewModel::onDisplayNameChange,
                onAvatarEmojiChange = viewModel::onAvatarEmojiChange,
                onOpenContactCode = viewModel::onOpenContactCode,
            )

            AppScreen.ContactCode -> ContactCodeScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
            )

            AppScreen.ImportContact -> ImportContactScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onContactCodeInputChange = viewModel::onContactCodeInputChange,
                onScannedContactCode = viewModel::onScannedContactCode,
                onImportClick = viewModel::onImportContactClick,
            )

            AppScreen.Debug -> DebugScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onStartScanClick = { runWithBluetoothPermissions { viewModel.onStartScanClick() } },
                onStartAdvertisingClick = { runWithBluetoothPermissions { viewModel.onStartAdvertisingClick() } },
                onClearDebugLogs = viewModel::onClearDebugLogs,
            )

            AppScreen.About -> AboutScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
            )

            is AppScreen.Chat -> ChatScreen(
                uiState = uiState,
                chatId = screen.chatId,
                onBack = viewModel::onBack,
                onOpenChatInfo = viewModel::onOpenChatInfo,
                onMessageTextChange = viewModel::onMessageTextChange,
                onSendClick = viewModel::onSendCurrentChatClick,
                onReplyToMessage = viewModel::onReplyToMessage,
                onCancelReply = viewModel::onCancelReply,
                onReactToMessage = viewModel::onReactToMessage,
                onChatVisible = viewModel::onCurrentChatVisible,
            )

            is AppScreen.ChatInfo -> ChatInfoScreen(
                uiState = uiState,
                chatId = screen.chatId,
                onBack = viewModel::onBack,
                onClearChat = viewModel::onClearCurrentChat,
            )
        }
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun OnboardingScreen(
    uiState: MeshUiState,
    onDisplayNameChange: (String) -> Unit,
    onContinueClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Meshsenger",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Bluetooth mesh-мессенджер без сервера и интернета.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Имя в сети") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Это имя будут видеть другие участники рядом.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinueClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            ButtonText("Продолжить")
        }
    }
}

@Composable
private fun PermissionsScreen(
    uiState: MeshUiState,
    hasPermissions: Boolean,
    onRequestPermissionsClick: () -> Unit,
    onContinueClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Разрешения Bluetooth",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Приложению нужны разрешения Bluetooth, чтобы искать устройства рядом, принимать подключения и отправлять сообщения без интернета.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        InfoCard(
            title = "Как это работает",
            text = "Телефоны рядом обмениваются сообщениями напрямую по Bluetooth. Интернет и сервер не нужны.",
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRequestPermissionsClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            ButtonText(if (hasPermissions) "Разрешения выданы" else "Выдать разрешения")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onContinueClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = hasPermissions,
        ) {
            ButtonText("Открыть приложение")
        }
    }
}

@Composable
private fun ChatListScreen(
    uiState: MeshUiState,
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    MainScaffold(
        title = "Meshsenger",
        subtitle = "Сообщения без интернета",
        selectedTab = MainTab.Chats,
        onOpenChats = {},
        onOpenContacts = onOpenContacts,
        onOpenNearby = onOpenNearby,
        onOpenSettings = onOpenSettings,
    ) { modifier ->
        Column(
            modifier = modifier.padding(horizontal = 16.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenNearby,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                ButtonText("Найти устройства рядом")
            }
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(uiState.chatPreviews) { chat ->
                    ChatPreviewItem(
                        chat = chat,
                        onClick = { onOpenChat(chat.chatId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    uiState: MeshUiState,
    onOpenChat: (String) -> Unit,
    onOpenContactCode: () -> Unit,
    onOpenImportContact: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    MainScaffold(
        title = "Контакты",
        subtitle = "Контакты и QR-добавление",
        selectedTab = MainTab.Contacts,
        onOpenChats = onOpenChats,
        onOpenContacts = onOpenContacts,
        onOpenNearby = onOpenNearby,
        onOpenSettings = onOpenSettings,
    ) { modifier ->
        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenImportContact,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { ButtonText("Скан QR") }
                OutlinedButton(
                    onClick = onOpenContactCode,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { ButtonText("Мой QR") }
            }

            if (uiState.contacts.none { it.nodeId.startsWith("node_") }) {
                EmptyState(
                    title = "Контактов пока нет",
                    text = "Добавьте друга по QR или включите поиск рядом на обоих телефонах.",
                )
            } else {
                uiState.contacts
                    .filter { it.nodeId.startsWith("node_") }
                    .forEach { contact ->
                        val directPeer = uiState.peers.firstOrNull { peer -> peer.nodeId == contact.nodeId && peer.isConnected && peer.isWritable }
                        val hasMeshNeighbor = uiState.peers.any { it.isConnected && it.isWritable }
                        val stateText = when {
                            directPeer != null || contact.isDirectlyConnected -> "в сети • напрямую"
                            hasMeshNeighbor && isContactRecentlySeen(contact.lastSeenMillis) -> "в сети • через mesh"
                            else -> "не в сети"
                        }
                        ContactListItem(
                            title = contact.bestName,
                            avatarEmoji = contact.avatarEmoji,
                            nodeId = contact.nodeId,
                            status = stateText,
                            endpointsCount = contact.endpoints.size,
                            isOnline = directPeer != null || contact.isDirectlyConnected || (hasMeshNeighbor && isContactRecentlySeen(contact.lastSeenMillis)),
                            onClick = { onOpenChat(contact.nodeId) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    uiState: MeshUiState,
    chatId: String,
    onBack: () -> Unit,
    onOpenChatInfo: (String) -> Unit,
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onReplyToMessage: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onReactToMessage: (ChatMessage, String) -> Unit,
    onChatVisible: () -> Unit,
) {
    val activeChatId = uiState.currentChatId ?: chatId
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val messagesCount = uiState.currentChatMessages.size
    val showScrollToBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    LaunchedEffect(activeChatId, messagesCount) {
        onChatVisible()
        if (messagesCount > 0) {
            listState.animateScrollToItem(messagesCount + 1)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.width(96.dp).height(40.dp),
                    ) {
                        ButtonText("‹ Назад")
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenChatInfo(activeChatId) },
                    ) {
                        Text(
                            text = uiState.currentChatTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = chatSubtitle(chatId = activeChatId, uiState = uiState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onOpenChatInfo(activeChatId) },
                        modifier = Modifier.size(42.dp),
                    ) {
                        ButtonText("⋮")
                    }
                }
                Divider()
            }
        },
        bottomBar = {
            MessageInputBar(
                text = uiState.messageText,
                canSend = uiState.canSendMessage,
                replyToMessage = uiState.replyToMessage,
                replySenderName = uiState.replyToMessage?.from?.let { displayNameForNode(it, uiState) },
                onCancelReply = onCancelReply,
                onTextChange = onMessageTextChange,
                onSendClick = onSendClick,
            )
        },
    ) { innerPadding ->
        if (uiState.currentChatMessages.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                title = "Пока нет сообщений",
                text = if (activeChatId == CHAT_ID_BROADCAST) {
                    "Напишите сюда, чтобы отправить broadcast всем подключённым узлам."
                } else {
                    "Напишите первое сообщение или дождитесь входящего."
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    items(uiState.currentChatMessages) { message ->
                        MessageBubble(
                            message = message,
                            senderName = displayNameForNode(message.from, uiState),
                            replySenderName = message.replyToSender?.let { displayNameForNode(it, uiState) },
                            onReply = { onReplyToMessage(message) },
                            onReact = { emoji -> onReactToMessage(message, emoji) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(76.dp)) }
                }

                if (showScrollToBottom) {
                    FloatingActionButton(
                        onClick = {
                            val lastIndex = uiState.currentChatMessages.size + 1
                            coroutineScope.launch {
                                listState.animateScrollToItem(lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 18.dp, bottom = 18.dp),
                    ) {
                        Text("↓")
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyPeersScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartScanClick: () -> Unit,
    onStartAdvertisingClick: () -> Unit,
    onClearStaleConnections: () -> Unit,
    onConnectPeerClick: (String) -> Unit,
    onDisconnectPeerClick: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    MainScaffold(
        title = "Устройства рядом",
        subtitle = "Подключение по Bluetooth",
        selectedTab = MainTab.Nearby,
        onOpenChats = onOpenChats,
        onOpenContacts = onOpenContacts,
        onOpenNearby = onOpenNearby,
        onOpenSettings = onOpenSettings,
    ) { modifier ->
        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartScanClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    ButtonText(if (uiState.isScanning) "Стоп" else "Сканировать")
                }
                OutlinedButton(
                    onClick = onClearStaleConnections,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    ButtonText("Очистить")
                }
            }

            InfoCard(
                title = "Мини-инструкция",
                text = "Включите этот экран на телефонах рядом. Когда устройство появится в списке, подключитесь к нему или откройте чат.",
            )

            Text(
                text = "Устройства рядом",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (uiState.peers.isEmpty()) {
                EmptyState(
                    title = "Устройств пока нет",
                    text = "Включите поиск на соседних телефонах и держите их рядом.",
                )
            } else {
                uiState.peers.forEach { peer ->
                    PeerItem(
                        peer = peer,
                        onConnectPeerClick = onConnectPeerClick,
                        onDisconnectPeerClick = onDisconnectPeerClick,
                        onOpenChat = onOpenChat,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onAvatarEmojiChange: (String) -> Unit,
    onOpenContactCode: () -> Unit,
) {
    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "Мой профиль",
                subtitle = "Имя и аватар",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        text = uiState.displayName.avatarText(),
                        emoji = uiState.avatarEmoji,
                        seed = uiState.localNodeId,
                        isOnline = true,
                        size = 86.dp,
                    )
                    Text(
                        text = "${uiState.avatarEmoji} ${uiState.displayName}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SettingsCard(title = "Настройки профиля") {
                OutlinedTextField(
                    value = uiState.displayName,
                    onValueChange = onDisplayNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя в приложении") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.avatarEmoji,
                    onValueChange = onAvatarEmojiChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Emoji-аватар") },
                    singleLine = true,
                )
                Text(
                    text = "Имя и emoji будут показываться в чатах и списке контактов.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SettingsCard(title = "Быстрый выбор emoji") {
                val variants = listOf("🌿", "🍃", "🌱", "🌵", "🦊", "🐱", "🐉", "🍄", "🌙", "⭐", "🔥", "💧")
                variants.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { emoji ->
                            OutlinedButton(
                                onClick = { onAvatarEmojiChange(emoji) },
                                modifier = Modifier.weight(1f).height(48.dp),
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onOpenContactCode,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                ButtonText("Показать мой QR")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    uiState: MeshUiState,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenContactCode: () -> Unit,
    onOpenImportContact: () -> Unit,
    onClearAllMessages: () -> Unit,
) {
    var showClearAllDialog by remember { mutableStateOf(false) }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Удалить историю сообщений?") },
            text = {
                Text(
                    "Будут удалены все сообщения на этом телефоне. " +
                        "Профиль, контакты и подключение к mesh-сети останутся. " +
                        "На других устройствах сообщения не удалятся.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        onClearAllMessages()
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    MainScaffold(
        title = "Настройки",
        subtitle = "Профиль и приложение",
        selectedTab = MainTab.Settings,
        onOpenChats = onOpenChats,
        onOpenContacts = onOpenContacts,
        onOpenNearby = onOpenNearby,
        onOpenSettings = onOpenSettings,
    ) { modifier ->
        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(title = "Профиль") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(text = uiState.displayName.avatarText(), emoji = uiState.avatarEmoji, seed = uiState.localNodeId, isOnline = true)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${uiState.avatarEmoji} ${uiState.displayName}", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Профиль виден другим участникам рядом",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            SettingsAction(
                title = "Мой профиль",
                subtitle = "Имя и аватар, которые видят другие участники.",
                onClick = onOpenProfile,
            )
            SettingsAction(
                title = "Мой QR/код контакта",
                subtitle = "Показать QR-код, чтобы другой телефон добавил вас в контакты.",
                onClick = onOpenContactCode,
            )

            SettingsAction(
                title = "Добавить контакт по коду",
                subtitle = "Сканировать QR или вставить код друга вручную.",
                onClick = onOpenImportContact,
            )

            SettingsAction(
                title = "О приложении",
                subtitle = "Краткая инструкция по использованию.",
                onClick = onOpenAbout,
            )

            OutlinedButton(
                onClick = { showClearAllDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                ButtonText("Удалить историю сообщений")
            }
        }
    }
}

@Composable
private fun ChatInfoScreen(
    uiState: MeshUiState,
    chatId: String,
    onBack: () -> Unit,
    onClearChat: () -> Unit,
) {
    var showClearChatDialog by remember { mutableStateOf(false) }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text("Удалить сообщения в этом чате?") },
            text = {
                Text(
                    "История будет очищена только на этом телефоне. " +
                        "Контакт, профиль и сообщения на других устройствах останутся.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearChatDialog = false
                        onClearChat()
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "Информация о чате",
                subtitle = uiState.currentChatTitle,
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val peer = uiState.peers.firstOrNull { it.peerId == chatId }
            val connectedCount = uiState.peers.count { it.isConnected }
            SettingsCard(title = "Чат") {
                Text("Название: ${uiState.currentChatTitle}")
                Text("Сообщений: ${uiState.currentChatMessages.size}")
                Text(
                    "Статус: " + if (chatId == CHAT_ID_BROADCAST) {
                        "участников рядом: $connectedCount"
                    } else if (peer?.isConnected == true) {
                        "в сети"
                    } else {
                        "не в сети"
                    },
                )
            }

            OutlinedButton(
                onClick = { showClearChatDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                ButtonText("Очистить этот чат")
            }
        }
    }
}

@Composable
private fun ContactCodeScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val contactCode = uiState.myContactCode
    val qrBitmap = remember(contactCode) { createQrBitmap(contactCode) }

    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "Мой QR-контакт",
                subtitle = "Покажи QR другу",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InfoCard(
                title = "Как добавить контакт",
                text = "Друг сканирует этот QR-код, после чего у него появится чат с вами.",
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Avatar(text = uiState.displayName.avatarText(), emoji = uiState.avatarEmoji, seed = uiState.localNodeId, isOnline = true)
                    Text(
                        text = "${uiState.avatarEmoji} ${uiState.displayName}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR-код контакта Meshsenger",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(contactCode))
                                Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { ButtonText("Копировать") }
                        Button(
                            onClick = { shareContactCode(context, contactCode) },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { ButtonText("Поделиться") }
                    }
                }
            }

            SettingsCard(title = "Ручной код") {
                SelectionContainer {
                    Text(
                        text = contactCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportContactScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
    onContactCodeInputChange: (String) -> Unit,
    onScannedContactCode: (String) -> Unit,
    onImportClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
    ) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            onScannedContactCode(contents)
        } else {
            Toast.makeText(context, "QR не прочитан", Toast.LENGTH_SHORT).show()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(contactScanOptions())
        } else {
            Toast.makeText(context, "Нужно разрешение камеры для сканирования QR", Toast.LENGTH_LONG).show()
        }
    }

    fun startQrScan() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanLauncher.launch(contactScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "Добавить контакт",
                subtitle = "QR или ручной код",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                title = "Мини-инструкция",
                text = "Наведите камеру на QR-код друга или вставьте полученный код вручную.",
            )
            Button(
                onClick = { startQrScan() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                ButtonText("Сканировать QR")
            }
            OutlinedTextField(
                value = uiState.contactCodeInput,
                onValueChange = onContactCodeInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Код контакта или результат QR") },
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        onContactCodeInputChange(text)
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    ButtonText("Вставить")
                }
                Button(
                    onClick = onImportClick,
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = uiState.contactCodeInput.isNotBlank(),
                ) {
                    ButtonText("Добавить")
                }
            }
        }
    }
}

@Composable
private fun DebugScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
    onStartScanClick: () -> Unit,
    onStartAdvertisingClick: () -> Unit,
    onClearDebugLogs: () -> Unit,
) {
    val context = LocalContext.current
    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(buildDebugLogsText(uiState))
            } ?: error("Не удалось открыть файл для записи")
        }.onSuccess {
            Toast.makeText(context, "Логи сохранены в TXT", Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(
                context,
                "Не удалось сохранить логи: ${error.localizedMessage ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val uploadPrefs = remember {
        context.getSharedPreferences(LOG_UPLOAD_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var logUploadUrl by remember {
        mutableStateOf(uploadPrefs.getString(KEY_LOG_UPLOAD_URL, DEFAULT_LOG_UPLOAD_URL) ?: DEFAULT_LOG_UPLOAD_URL)
    }
    var logUploadToken by remember {
        mutableStateOf(uploadPrefs.getString(KEY_LOG_UPLOAD_TOKEN, DEFAULT_LOG_UPLOAD_TOKEN) ?: DEFAULT_LOG_UPLOAD_TOKEN)
    }
    var isUploadingLogs by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val cloudLogUploader = remember(context) { CloudLogUploader(context.applicationContext) }

    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "Debug",
                subtitle = "QR или ручной код",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartScanClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    ButtonText(if (uiState.isScanning) "Стоп" else "Скан")
                }
                OutlinedButton(
                    onClick = onStartAdvertisingClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    ButtonText(if (uiState.isAdvertising) "Стоп приём" else "Принимать")
                }
            }

            OutlinedButton(
                onClick = onClearDebugLogs,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                ButtonText("Очистить логи")
            }

            OutlinedButton(
                onClick = { exportLogsLauncher.launch("meshsenger-logs.txt") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                ButtonText("Логи в TXT")
            }

            SettingsCard(title = "Облачная отправка логов") {
                Text(
                    text = "Отправляет текущий debug TXT в Google Apps Script. URL и token сохраняются на этом телефоне.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = logUploadUrl,
                    onValueChange = { value ->
                        logUploadUrl = value
                        uploadPrefs.edit().putString(KEY_LOG_UPLOAD_URL, value).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Web App URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = logUploadToken,
                    onValueChange = { value ->
                        logUploadToken = value
                        uploadPrefs.edit().putString(KEY_LOG_UPLOAD_TOKEN, value).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (isUploadingLogs) return@Button

                        val logsText = buildDebugLogsText(uiState)
                        isUploadingLogs = true
                        MeshLogger.info(
                            tag = "CloudLogUploader",
                            message = "Начата отправка логов в облако. logsLength=${logsText.length}",
                        )

                        coroutineScope.launch {
                            val result = cloudLogUploader.uploadLogs(
                                endpointUrl = logUploadUrl,
                                secretToken = logUploadToken,
                                nodeId = uiState.localNodeId,
                                displayName = uiState.displayName,
                                appVersion = APP_VERSION_LABEL,
                                status = uiState.status,
                                logs = logsText,
                            )

                            isUploadingLogs = false

                            result.onSuccess { uploadResult ->
                                MeshLogger.info(
                                    tag = "CloudLogUploader",
                                    message = "Логи отправлены: ${uploadResult.fileName}, length=${uploadResult.logsLength}",
                                )
                                Toast.makeText(
                                    context,
                                    "Логи отправлены: ${uploadResult.fileName}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure { error ->
                                MeshLogger.error(
                                    tag = "CloudLogUploader",
                                    message = "Не удалось отправить логи: ${error.localizedMessage ?: error.javaClass.simpleName}",
                                )
                                Toast.makeText(
                                    context,
                                    "Ошибка отправки: ${error.localizedMessage ?: error.javaClass.simpleName}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isUploadingLogs,
                ) {
                    ButtonText(if (isUploadingLogs) "Отправляем..." else "Отправить в облако")
                }
            }

            SettingsCard(title = "Состояние") {
                Text("Node id: ${uiState.localNodeId}")
                Text("BLE-поиск: ${uiState.isScanning}")
                Text("BLE-приём: ${uiState.isAdvertising}")
                Text("Узлов найдено: ${uiState.peers.size}")
                Text("Сообщений: ${uiState.messages.size}")
                Text("Логов: ${uiState.debugLogs.size}")
            }

            SettingsCard(title = "Peers") {
                if (uiState.peers.isEmpty()) {
                    Text("Пока нет узлов")
                } else {
                    uiState.peers.forEach { peer ->
                        Text("${peer.name ?: "без имени"} — ${peer.nodeId ?: peer.peerId} — ${if (peer.isWritable) "writable" else peer.connectionState}")
                    }
                }
            }

            Text(
                text = "Логи приложения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (uiState.debugLogs.isEmpty()) {
                EmptyState(
                    title = "Логов пока нет",
                    text = "Запустите BLE-поиск, приём или подключение, чтобы увидеть события.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.debugLogs.reversed(), key = { it.id }) { log ->
                        DebugLogItem(
                            timeText = log.timeText,
                            level = log.level,
                            tag = log.tag,
                            message = log.message,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugLogItem(
    timeText: String,
    level: DebugLogLevel,
    tag: String,
    message: String,
) {
    val levelText = when (level) {
        DebugLogLevel.Info -> "INFO"
        DebugLogLevel.Warning -> "WARN"
        DebugLogLevel.Error -> "ERROR"
    }
    val levelColor = when (level) {
        DebugLogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        DebugLogLevel.Warning -> MaterialTheme.colorScheme.tertiary
        DebugLogLevel.Error -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$timeText  $levelText  $tag",
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


private fun contactScanOptions(): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Наведите камеру на QR-контакт Meshsenger")
    setBeepEnabled(false)
    setOrientationLocked(false)
}

private fun String.avatarText(): String {
    val value = trim()
    return if (value.isBlank()) "?" else value.first().uppercase()
}

private fun createQrBitmap(content: String, size: Int = 768): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun shareContactCode(context: Context, code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Meshsenger контакт")
        putExtra(Intent.EXTRA_TEXT, code)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться контактом"))
}

private fun buildDebugLogsText(uiState: MeshUiState): String = buildString {
    appendLine("Meshsenger debug logs")
    appendLine("======================")
    appendLine("localNodeId: ${uiState.localNodeId}")
    appendLine("displayName: ${uiState.displayName}")
    appendLine("status: ${uiState.status}")
    appendLine("isScanning: ${uiState.isScanning}")
    appendLine("isAdvertising: ${uiState.isAdvertising}")
    appendLine("peersCount: ${uiState.peers.size}")
    appendLine("contactsCount: ${uiState.contacts.size}")
    appendLine("messagesCount: ${uiState.messages.size}")
    appendLine("logsCount: ${uiState.debugLogs.size}")
    appendLine()
    appendLine("Peers:")
    if (uiState.peers.isEmpty()) {
        appendLine("- empty")
    } else {
        uiState.peers.forEach { peer ->
            appendLine("- ${peer.peerId} | node=${peer.nodeId ?: "null"} | name=${peer.name ?: "null"} | connected=${peer.isConnected} | writable=${peer.isWritable} | state=${peer.connectionState}")
        }
    }
    appendLine()
    appendLine("Contacts:")
    if (uiState.contacts.isEmpty()) {
        appendLine("- empty")
    } else {
        uiState.contacts.forEach { contact ->
            appendLine("- ${contact.nodeId} | name=${contact.displayName ?: "null"} | connected=${contact.isDirectlyConnected} | via=${contact.discoveredVia} | endpoints=${contact.endpoints.joinToString { it.peerId + ":" + it.isWritable }}")
        }
    }
    appendLine()
    appendLine("Logs:")
    if (uiState.debugLogs.isEmpty()) {
        appendLine("- empty")
    } else {
        uiState.debugLogs.forEach { log ->
            appendLine("[${log.id}] ${log.timeText} ${log.level} ${log.tag}")
            appendLine(log.message)
            appendLine()
        }
    }
}

@Composable
private fun AboutScreen(
    uiState: MeshUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            SimpleTopBar(
                title = "О приложении",
                subtitle = "Коротко о работе приложения",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                title = "Как пользоваться",
                text = "1. Откройте «Рядом» на телефонах участников.\n2. Подключитесь к найденному устройству.\n3. Откройте чат и отправьте сообщение.\n4. Если прямой связи нет, оставьте рядом телефон-посредник — сообщения смогут пройти через него.",
            )
            InfoCard(
                title = "Важно",
                text = "Приложение работает по Bluetooth. Для стабильной связи держите телефоны недалеко друг от друга и не выключайте Bluetooth.",
            )
        }
    }
}

@Composable
private fun MainScaffold(
    title: String,
    subtitle: String,
    selectedTab: MainTab,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        bottomBar = {
            BottomTabs(
                selectedTab = selectedTab,
                onOpenChats = onOpenChats,
                onOpenContacts = onOpenContacts,
                onOpenNearby = onOpenNearby,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { innerPadding ->
        content(Modifier.padding(innerPadding))
    }
}

@Composable
private fun BottomTabs(
    selectedTab: MainTab,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BottomTabButton("💬", "Чаты", selectedTab == MainTab.Chats, onOpenChats, Modifier.weight(1f))
            BottomTabButton("👥", "Контакты", selectedTab == MainTab.Contacts, onOpenContacts, Modifier.weight(1f))
            BottomTabButton("📡", "Рядом", selectedTab == MainTab.Nearby, onOpenNearby, Modifier.weight(1f))
            BottomTabButton("⚙", "Настройки", selectedTab == MainTab.Settings, onOpenSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BottomTabButton(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(50.dp),
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(50.dp),
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ContactListItem(
    title: String,
    avatarEmoji: String?,
    nodeId: String,
    status: String,
    endpointsCount: Int,
    isOnline: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(text = title.avatarText(), emoji = avatarEmoji, seed = nodeId, isOnline = isOnline)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatPreviewItem(
    chat: ChatPreviewUiModel,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(text = chat.avatarText, emoji = chat.avatarEmoji, seed = chat.chatId, isOnline = chat.isOnline)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chat.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = chat.lastMessageTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chat.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = chat.unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(
    text: String,
    isOnline: Boolean,
    emoji: String? = null,
    seed: String = text,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    val background = remember(seed) { avatarColorFor(seed) }
    val content = emoji?.takeIf { it.isNotBlank() } ?: text.take(2).ifBlank { "?" }
    Box {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = content,
                style = if (size > 60.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(if (size > 60.dp) 20.dp else 14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    senderName: String,
    replySenderName: String?,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember { mutableStateOf(false) }
    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.80f)
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (message.isOutgoing) 18.dp else 4.dp,
                            bottomEnd = if (message.isOutgoing) 4.dp else 18.dp,
                        ),
                    )
                    .background(bubbleColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true },
                    )
                    .padding(12.dp),
            ) {
                Text(
                    text = if (message.isOutgoing) "Вы" else senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!message.replyToText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ReplyPreview(
                        senderName = replySenderName ?: message.replyToSender ?: "сообщение",
                        text = message.replyToText,
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        message.reactions.entries.sortedBy { it.key }.forEach { (emoji, count) ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                            ) {
                                Text(
                                    text = if (count > 1) "$emoji $count" else emoji,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.isOutgoing) {
                        Text(
                            text = message.status.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Ответить") },
                    onClick = {
                        menuExpanded = false
                        onReply()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    onClick = {
                        menuExpanded = false
                        clipboard.setText(AnnotatedString(message.text))
                    },
                )
                Divider()
                listOf("👍", "❤️", "😂", "😮", "😢", "🔥").forEach { emoji ->
                    DropdownMenuItem(
                        text = { Text("$emoji  Реакция") },
                        onClick = {
                            menuExpanded = false
                            onReact(emoji)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyPreview(senderName: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = senderName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun MessageInputBar(
    text: String,
    canSend: Boolean,
    replyToMessage: ChatMessage?,
    replySenderName: String?,
    onCancelReply: () -> Unit,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (replyToMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ответ на ${replySenderName ?: replyToMessage.replyToSender ?: "сообщение"}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = replyToMessage.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onCancelReply) {
                        Text("✕")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                Button(
                    onClick = onSendClick,
                    enabled = canSend,
                    modifier = Modifier.size(52.dp),
                ) {
                    ButtonText("➤")
                }
            }
        }
    }
}


@Composable
private fun PeerItem(
    peer: PeerUiModel,
    onConnectPeerClick: (String) -> Unit,
    onDisconnectPeerClick: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    text = (peer.name ?: peer.peerId).firstOrNull()?.uppercase() ?: "?",
                    seed = peer.nodeId ?: peer.peerId,
                    isOnline = peer.isConnected,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.friendlyPeerName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (peer.isWritable) "готово к чату" else peer.connectionState.userConnectionState(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (peer.isConnected) {
                    OutlinedButton(
                        onClick = { onDisconnectPeerClick(peer.peerId) },
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        ButtonText("Отключить")
                    }
                } else {
                    Button(
                        onClick = { onConnectPeerClick(peer.peerId) },
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        ButtonText("Подключить")
                    }
                }
                FilledTonalButton(
                    onClick = { onOpenChat(peer.nodeId ?: peer.peerId) },
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    ButtonText("Чат")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}


@Composable
private fun SettingsAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    text: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(text = text)
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SimpleTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.width(96.dp).height(40.dp),
            ) {
                ButtonText("‹ Назад")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Divider()
    }
}

@Composable
private fun ButtonText(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val LOG_UPLOAD_PREFS_NAME = "meshsenger_log_upload_preferences"
private const val KEY_LOG_UPLOAD_URL = "log_upload_url"
private const val KEY_LOG_UPLOAD_TOKEN = "log_upload_token"
private const val DEFAULT_LOG_UPLOAD_URL = "https://script.google.com/macros/s/AKfycbxGpSGqEEj4DJO9AhkIS1KS4T9EHVmd5xVuiGFigkgcGBvDSMoAsl4jWuRbrN4DVP-9/exec"
private const val DEFAULT_LOG_UPLOAD_TOKEN = "DrakoshKApISKA"
private const val APP_VERSION_LABEL = "1.0-debug"

private fun MessageDeliveryStatus.label(): String {
    return when (this) {
        MessageDeliveryStatus.Pending -> "ожидает"
        MessageDeliveryStatus.Relayed -> "у посредника"
        MessageDeliveryStatus.Sent -> "отправлено"
        MessageDeliveryStatus.Delivered, MessageDeliveryStatus.Received -> "доставлено"
        MessageDeliveryStatus.Read -> "прочитано"
        MessageDeliveryStatus.Failed -> "ошибка"
    }
}

private fun formatMessageTime(timestamp: Long): String {
    return DateFormat.format("HH:mm", timestamp).toString()
}

private fun chatSubtitle(chatId: String, uiState: MeshUiState): String {
    if (chatId == CHAT_ID_BROADCAST) {
        val connectedCount = uiState.peers.count { it.isConnected && it.isWritable }
        return if (connectedCount > 0) {
            "общий чат • рядом: $connectedCount"
        } else {
            "общий чат • рядом никого нет"
        }
    }
    val peer = uiState.peers.firstOrNull { it.peerId == chatId || it.nodeId == chatId }
    val contact = uiState.contacts.firstOrNull { it.nodeId == chatId }
    val hasMeshNeighbor = uiState.peers.any { it.isConnected && it.isWritable }
    return when {
        peer?.isConnected == true && peer.isWritable -> "в сети • напрямую"
        contact?.isDirectlyConnected == true -> "в сети • напрямую"
        hasMeshNeighbor && contact != null && isContactRecentlySeen(contact.lastSeenMillis) -> "в сети • через mesh"
        peer != null -> "не в сети • найден ранее"
        contact != null -> "не в сети"
        else -> "пока не найден рядом"
    }
}

private fun avatarColorFor(seed: String): Color {
    val palette = listOf(
        Color(0xFFB9F6CA),
        Color(0xFFA7FFEB),
        Color(0xFFC8E6C9),
        Color(0xFFFFF9C4),
        Color(0xFFFFCCBC),
        Color(0xFFD1C4E9),
        Color(0xFFB3E5FC),
        Color(0xFFFFF59D),
    )
    val index = kotlin.math.abs(seed.hashCode()) % palette.size
    return palette[index]
}

private fun displayNameForNode(nodeId: String, uiState: MeshUiState): String {
    if (nodeId == uiState.localNodeId) return uiState.displayName.ifBlank { "Вы" }
    return uiState.contacts.firstOrNull { it.nodeId == nodeId }?.displayName?.takeIf { it.isNotBlank() }
        ?: uiState.peers.firstOrNull { it.nodeId == nodeId || it.peerId == nodeId }?.friendlyPeerName()
        ?: "Новый контакт"
}


private fun PeerUiModel.friendlyPeerName(): String {
    val rawName = name?.trim().orEmpty()
    return rawName
        .takeUnless { it.isBlank() || it.startsWith("BLE узел") }
        ?: nodeId?.let { "Участник рядом" }
        ?: "Устройство рядом"
}

private fun String.userConnectionState(): String {
    return when {
        contains("отключ", ignoreCase = true) -> "не подключено"
        contains("найден", ignoreCase = true) -> "найдено рядом"
        contains("подключ", ignoreCase = true) -> "подключается"
        else -> this
    }
}

private fun isContactRecentlySeen(lastSeenMillis: Long): Boolean {
    if (lastSeenMillis <= 0L) return false
    return System.currentTimeMillis() - lastSeenMillis <= ONLINE_WINDOW_MS
}

private const val ONLINE_WINDOW_MS = 2 * 60 * 1000L

private enum class MainTab {
    Chats,
    Contacts,
    Nearby,
    Settings,
}

private const val CHAT_ID_BROADCAST = "broadcast"
