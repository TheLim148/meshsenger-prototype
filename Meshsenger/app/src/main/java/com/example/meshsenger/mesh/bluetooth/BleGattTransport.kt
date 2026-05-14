package com.example.meshsenger.mesh.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.meshsenger.mesh.logging.MeshLogger
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Полностью BLE-вариант транспорта для Meshsenger.
 *
 * Схема:
 * - startAdvertising() поднимает BLE GATT Server и BLE Advertising с Meshsenger service UUID;
 * - startScan() ищет только устройства с этим service UUID;
 * - connect(peerId) подключается по BLE GATT;
 * - send(peerId, bytes) режет ByteArray на чанки и отправляет через GATT write/notify;
 * - входящие чанки собираются обратно и отдаются в MeshService как исходный ByteArray.
 */
class BleGattTransport(
    context: Context,
) : BluetoothTransport {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var listener: BluetoothTransportListener? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val connections = ConcurrentHashMap<String, BleConnection>()
    private val incomingFrames = ConcurrentHashMap<String, ConcurrentHashMap<Int, IncomingFrame>>()
    private val reportedPeers = ConcurrentHashMap.newKeySet<String>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScanning = false
    private var isAdvertising = false

    override fun setListener(listener: BluetoothTransportListener) {
        MeshLogger.info(TAG, "setListener()")
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        MeshLogger.info(TAG, "BLE startScan() called")

        val adapter = bluetoothAdapter ?: run {
            reportWarning("Bluetooth не поддерживается на устройстве")
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            reportWarning("Нет разрешений Bluetooth Scan/Connect")
            return
        }

        if (!adapter.isEnabled) {
            reportWarning("Bluetooth выключен")
            return
        }

        val bluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            reportWarning("BLE Scanner недоступен на этом устройстве")
            return
        }

        runCatching {
            stopScan()
            scanner = bluetoothLeScanner
            reportedPeers.clear()

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()

            bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            MeshLogger.info(TAG, "BLE scan started. Filter service=$SERVICE_UUID")
        }.onFailure { error ->
            isScanning = false
            MeshLogger.error(TAG, "Ошибка запуска BLE scan", error)
            reportError("Ошибка BLE-сканирования: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        MeshLogger.info(TAG, "BLE stopScan() called")
        runCatching {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                scanner?.stopScan(scanCallback)
            }
        }.onFailure { error ->
            MeshLogger.error(TAG, "Ошибка остановки BLE scan", error)
        }
        isScanning = false
        scanner = null
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising() {
        MeshLogger.info(TAG, "BLE startAdvertising() called")

        val adapter = bluetoothAdapter ?: run {
            reportWarning("Bluetooth не поддерживается на устройстве")
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            reportWarning("Нет разрешений Bluetooth Advertise/Connect")
            return
        }

        if (!adapter.isEnabled) {
            reportWarning("Bluetooth выключен")
            return
        }

        val bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            reportWarning("BLE Advertising недоступен на этом устройстве")
            return
        }

        runCatching {
            stopAdvertising()
            setupGattServer()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            advertiser = bluetoothLeAdvertiser
            bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            MeshLogger.info(TAG, "BLE advertising started. Service=$SERVICE_UUID")
        }.onFailure { error ->
            isAdvertising = false
            MeshLogger.error(TAG, "Ошибка запуска BLE advertising", error)
            reportError("Ошибка запуска BLE-приёма: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopAdvertising() {
        MeshLogger.info(TAG, "BLE stopAdvertising() called")

        runCatching {
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        }.onFailure { error ->
            MeshLogger.error(TAG, "Ошибка остановки BLE advertising", error)
        }

        runCatching {
            gattServer?.close()
        }.onFailure { error ->
            MeshLogger.error(TAG, "Ошибка закрытия GATT server", error)
        }

        advertiser = null
        gattServer = null
        isAdvertising = false
    }

    @SuppressLint("MissingPermission")
    override fun connect(peerId: String) {
        MeshLogger.info(TAG, "BLE connect(peerId=$peerId) called")

        val adapter = bluetoothAdapter ?: run {
            reportWarning("Bluetooth не поддерживается на устройстве")
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            reportWarning("Нет разрешения Bluetooth Connect")
            return
        }

        if (!adapter.isEnabled) {
            reportWarning("Bluetooth выключен")
            return
        }

        val existingConnection = connections[peerId]
        if (existingConnection?.isReadyForSend() == true) {
            MeshLogger.info(TAG, "BLE already connected to $peerId")
            listener?.onPeerConnected(peerId)
            return
        }

        if (!connectingPeers.add(peerId)) {
            reportWarning("BLE-подключение к $peerId уже выполняется. Подождите")
            return
        }

        runCatching {
            // На части телефонов BLE scan мешает стабильному GATT connect.
            // Поэтому перед активным подключением останавливаем только поиск,
            // advertising/GATT server при этом остаётся жить.
            stopScan()

            val device = adapter.getRemoteDevice(peerId)
            val connection = connectionFor(device)
            connection.clientConnecting = true
            connection.clientPhase = "connecting"
            connection.clientReadyReported = false

            MeshLogger.info(TAG, "connectGatt($peerId, TRANSPORT_LE)")
            val gatt = device.connectGatt(
                appContext,
                false,
                clientGattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            connection.clientGatt = gatt
            scheduleClientTimeout(connection, "connectGatt")
        }.onFailure { error ->
            connectingPeers.remove(peerId)
            MeshLogger.error(TAG, "Ошибка BLE connect($peerId)", error)
            reportError("Ошибка BLE-подключения к $peerId: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect(peerId: String) {
        MeshLogger.info(TAG, "BLE disconnect(peerId=$peerId) called")
        connectingPeers.remove(peerId)

        val connection = connections.remove(peerId)
        hardResetConnection(connection, reason = "manual disconnect")
        listener?.onPeerDisconnected(peerId)
    }

    override fun send(peerId: String, bytes: ByteArray) {
        MeshLogger.info(TAG, "BLE send(peerId=$peerId, bytes=${bytes.size})")

        val connection = connections[peerId] ?: run {
            reportWarning("BLE-узел $peerId не подключён")
            return
        }

        val packets = createPackets(bytes = bytes, mtu = connection.mtu)
        MeshLogger.info(TAG, "BLE send split: ${bytes.size} bytes -> ${packets.size} packet(s), mtu=${connection.mtu}")

        synchronized(connection) {
            when {
                connection.isClientReady -> {
                    packets.forEach { connection.clientWriteQueue.add(it) }
                    writeNextClientPacket(connection)
                }

                connection.isServerNotifyReady -> {
                    packets.forEach { connection.serverNotifyQueue.add(it) }
                    notifyNextServerPacket(connection)
                }

                connection.clientConnecting || connection.clientGatt != null -> {
                    // Это исходящее подключение. Кладём только в client-очередь,
                    // чтобы не получить дубли при появлении server notify-канала.
                    packets.forEach { connection.clientWriteQueue.add(it) }
                    MeshLogger.warning(TAG, "BLE peer $peerId ещё не готов для client write, packet(s) queued")
                }

                connection.isServerConnected -> {
                    // Это входящее подключение. Ждём, пока клиент подпишется на notify.
                    packets.forEach { connection.serverNotifyQueue.add(it) }
                    MeshLogger.warning(TAG, "BLE peer $peerId ещё не включил notify, packet(s) queued")
                }

                else -> {
                    reportWarning("BLE-узел $peerId не готов для отправки")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        val manager = bluetoothManager ?: error("BluetoothManager недоступен")
        val server = manager.openGattServer(appContext, serverCallback)
            ?: error("Не удалось открыть BLE GATT server")

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeCharacteristic = BluetoothGattCharacteristic(
            WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val notifyCharacteristic = BluetoothGattCharacteristic(
            NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val cccd = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        notifyCharacteristic.addDescriptor(cccd)

        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)

        if (!server.addService(service)) {
            server.close()
            error("Не удалось добавить BLE GATT service")
        }

        gattServer = server
        MeshLogger.info(TAG, "BLE GATT server ready. service=$SERVICE_UUID")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val peerId = device.address ?: return
            if (!reportedPeers.add(peerId)) return

            val name = safeDeviceName(device, result)
            MeshLogger.info(TAG, "BLE ACTION_FOUND peerId=$peerId, name=${name ?: "null"}, rssi=${result.rssi}")
            connectionFor(device)
            listener?.onPeerFound(peerId, name ?: "BLE узел ${peerId.takeLast(5)}")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val message = "BLE scan failed: errorCode=$errorCode"
            MeshLogger.warning(TAG, message)
            listener?.onError(message)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            MeshLogger.info(TAG, "BLE advertise onStartSuccess")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val message = "BLE advertise failed: errorCode=$errorCode"
            MeshLogger.warning(TAG, message)
            listener?.onError(message)
        }
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val peerId = gatt.device.address
            MeshLogger.info(TAG, "BLE client onConnectionStateChange peer=$peerId status=$status newState=$newState")
            val connection = connectionFor(gatt.device)
            connection.clientGatt = gatt

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connection.clientConnecting = true
                connection.clientPhase = "gatt_connected"
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                mainHandler.postDelayed({
                    if (connections[peerId] === connection && connection.clientConnecting && !connection.isClientReady) {
                        MeshLogger.info(TAG, "BLE discoverServices() start peer=$peerId")
                        connection.clientPhase = "discover_services"
                        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        MeshLogger.info(TAG, "BLE discoverServices() peer=$peerId started=$started")
                        if (!started) {
                            failClientConnection(connection, "discoverServices returned false")
                        }
                    }
                }, DISCOVER_SERVICES_DELAY_MS)
                scheduleClientTimeout(connection, "services")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectingPeers.remove(peerId)
                connection.clientConnecting = false
                connection.isClientReady = false
                runCatching { gatt.close() }
                if (!connection.isServerNotifyReady) {
                    connections.remove(peerId)
                    listener?.onPeerDisconnected(peerId)
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("BLE client connection error for $peerId: status=$status")
                failClientConnection(connection, "connection status=$status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val peerId = gatt.device.address
            val connection = connectionFor(gatt.device)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connection.mtu = mtu
                MeshLogger.info(TAG, "BLE client MTU changed peer=$peerId mtu=$mtu")
            } else {
                MeshLogger.warning(TAG, "BLE client MTU change failed peer=$peerId status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val peerId = gatt.device.address
            MeshLogger.info(TAG, "BLE services discovered peer=$peerId status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("BLE services discovery failed for $peerId: status=$status")
                failClientConnection(connectionFor(gatt.device), "services discovery status=$status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                reportError("Устройство $peerId не содержит Meshsenger BLE service")
                failClientConnection(connectionFor(gatt.device), "service missing")
                return
            }

            val writeCharacteristic = service.getCharacteristic(WRITE_CHARACTERISTIC_UUID)
            val notifyCharacteristic = service.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID)
            if (writeCharacteristic == null || notifyCharacteristic == null) {
                reportError("Устройство $peerId не содержит нужные BLE characteristics")
                failClientConnection(connectionFor(gatt.device), "characteristics missing")
                return
            }

            val connection = connectionFor(gatt.device)
            connection.clientPhase = "services_discovered"
            connection.clientWriteCharacteristic = writeCharacteristic
            connection.clientNotifyCharacteristic = notifyCharacteristic

            val notificationsEnabled = gatt.setCharacteristicNotification(notifyCharacteristic, true)
            MeshLogger.info(TAG, "BLE setCharacteristicNotification peer=$peerId enabled=$notificationsEnabled")
            val descriptor = notifyCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                connection.clientPhase = "write_cccd"
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val descriptorStarted = gatt.writeDescriptor(descriptor)
                MeshLogger.info(TAG, "BLE write CCCD peer=$peerId started=$descriptorStarted")
                if (!descriptorStarted) {
                    failClientConnection(connection, "writeDescriptor returned false")
                }
            } else {
                MeshLogger.warning(TAG, "BLE CCCD descriptor missing peer=$peerId, mark client ready without descriptor")
                markClientReady(connection)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val peerId = gatt.device.address
            MeshLogger.info(TAG, "BLE descriptor write peer=$peerId status=$status uuid=${descriptor.uuid}")
            val connection = connectionFor(gatt.device)
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                markClientReady(connection)
            } else if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                failClientConnection(connection, "descriptor write status=$status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val peerId = gatt.device.address
            val connection = connectionFor(gatt.device)
            MeshLogger.info(TAG, "BLE client write complete peer=$peerId status=$status")
            synchronized(connection) {
                connection.clientWriteInProgress = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    MeshLogger.warning(TAG, "BLE write failed peer=$peerId status=$status")
                    failClientConnection(connection, "write status=$status")
                    return
                }
                writeNextClientPacket(connection)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != NOTIFY_CHARACTERISTIC_UUID) return
            val peerId = gatt.device.address
            val value = characteristic.value ?: return
            MeshLogger.info(TAG, "BLE notification from $peerId size=${value.size}")
            handleIncomingPacket(peerId = peerId, packet = value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != NOTIFY_CHARACTERISTIC_UUID) return
            val peerId = gatt.device.address
            MeshLogger.info(TAG, "BLE notification from $peerId size=${value.size}")
            handleIncomingPacket(peerId = peerId, packet = value)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val peerId = device.address
            MeshLogger.info(TAG, "BLE server onConnectionStateChange peer=$peerId status=$status newState=$newState")
            val connection = connectionFor(device)

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connection.isServerConnected = true
                connection.serverPhase = "connected_wait_cccd"
                MeshLogger.info(TAG, "BLE server connected peer=$peerId; waiting for CCCD before writable")
                listener?.onPeerFound(peerId, safeDeviceName(device, null) ?: "BLE узел ${peerId.takeLast(5)}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connection.isServerConnected = false
                connection.isServerNotifyReady = false
                if (!connection.isClientReady) {
                    connections.remove(peerId)
                    listener?.onPeerDisconnected(peerId)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val connection = connectionFor(device)
            connection.mtu = mtu
            MeshLogger.info(TAG, "BLE server MTU changed peer=${device.address} mtu=$mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val peerId = device.address
            MeshLogger.info(TAG, "BLE server write request peer=$peerId size=${value.size} uuid=${characteristic.uuid}")

            if (characteristic.uuid == WRITE_CHARACTERISTIC_UUID) {
                handleIncomingPacket(peerId = peerId, packet = value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val peerId = device.address
            MeshLogger.info(TAG, "BLE descriptor write request peer=$peerId uuid=${descriptor.uuid}")
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                val connection = connectionFor(device)
                connection.isServerNotifyReady = true
                connection.serverPhase = "notify_ready"
                notifyPeerConnectedOnce(connection)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                synchronized(connection) {
                    notifyNextServerPacket(connection)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val connection = connectionFor(device)
            MeshLogger.info(TAG, "BLE notification sent peer=${device.address} status=$status")
            synchronized(connection) {
                connection.serverNotifyInProgress = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    MeshLogger.warning(TAG, "BLE notification failed peer=${device.address} status=$status")
                    failServerConnection(connection, "notification status=$status")
                    return
                }
                notifyNextServerPacket(connection)
            }
        }
    }

    private fun scheduleClientTimeout(connection: BleConnection, phase: String) {
        val peerId = connection.peerId
        mainHandler.postDelayed({
            val current = connections[peerId]
            if (current === connection && current.clientConnecting && !current.isClientReady) {
                MeshLogger.warning(TAG, "BLE client timeout peer=$peerId phase=$phase currentPhase=${current.clientPhase}")
                failClientConnection(current, "timeout at ${current.clientPhase}")
                listener?.onError("BLE-подключение к $peerId зависло на этапе ${current.clientPhase}. Попробуйте подключиться ещё раз")
            }
        }, CLIENT_CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun markClientReady(connection: BleConnection) {
        connection.isClientReady = true
        connection.clientConnecting = false
        connection.clientPhase = "ready"
        connection.clientReadyReported = true
        connectingPeers.remove(connection.peerId)
        MeshLogger.info(TAG, "BLE client ready peer=${connection.peerId}")
        runCatching { connection.clientGatt?.requestMtu(DESIRED_MTU) }
        notifyPeerConnectedOnce(connection)
        synchronized(connection) {
            writeNextClientPacket(connection)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyPeerConnectedOnce(connection: BleConnection) {
        if (connection.reportedConnected || !connection.isReadyForSend()) return
        connection.reportedConnected = true
        val name = safeDeviceName(connection.device, null)
        listener?.onPeerFound(connection.peerId, name ?: "BLE узел ${connection.peerId.takeLast(5)}")
        listener?.onPeerConnected(connection.peerId)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextClientPacket(connection: BleConnection) {
        if (!connection.isClientReady || connection.clientWriteInProgress) return

        val gatt = connection.clientGatt ?: return
        val characteristic = connection.clientWriteCharacteristic ?: return
        val packet = connection.clientWriteQueue.poll() ?: return

        connection.clientWriteInProgress = true
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = packet
        val started = gatt.writeCharacteristic(characteristic)
        MeshLogger.info(TAG, "BLE client writeNext peer=${connection.peerId} size=${packet.size} started=$started")
        if (!started) {
            connection.clientWriteInProgress = false
            reportWarning("BLE write не стартовал для ${connection.peerId}")
            failClientConnection(connection, "writeCharacteristic returned false")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyNextServerPacket(connection: BleConnection) {
        if (!connection.isServerNotifyReady || connection.serverNotifyInProgress) return

        val server = gattServer ?: return
        val characteristic = server.getService(SERVICE_UUID)
            ?.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID)
            ?: return
        val device = connection.device ?: return
        val packet = connection.serverNotifyQueue.poll() ?: return

        connection.serverNotifyInProgress = true
        characteristic.value = packet
        val started = server.notifyCharacteristicChanged(device, characteristic, false)
        MeshLogger.info(TAG, "BLE server notifyNext peer=${connection.peerId} size=${packet.size} started=$started")
        if (!started) {
            connection.serverNotifyInProgress = false
            reportWarning("BLE notification не стартовал для ${connection.peerId}")
            failServerConnection(connection, "notifyCharacteristicChanged returned false")
        }
    }


    @SuppressLint("MissingPermission")
    private fun failClientConnection(connection: BleConnection, reason: String) {
        MeshLogger.warning(TAG, "BLE client connection failed peer=${connection.peerId}: $reason")
        connectingPeers.remove(connection.peerId)
        connection.clientPhase = "failed: $reason"
        hardResetConnection(connection, reason = "client failed: $reason")
        connections.remove(connection.peerId)
        listener?.onPeerDisconnected(connection.peerId)
    }

    private fun failServerConnection(connection: BleConnection, reason: String) {
        MeshLogger.warning(TAG, "BLE server notification path failed peer=${connection.peerId}: $reason")
        connection.serverPhase = "failed: $reason"
        hardResetConnection(connection, reason = "server failed: $reason")
        connections.remove(connection.peerId)
        listener?.onPeerDisconnected(connection.peerId)
    }

    @SuppressLint("MissingPermission")
    private fun hardResetConnection(connection: BleConnection?, reason: String) {
        if (connection == null) return
        MeshLogger.warning(TAG, "Hard reset BLE peer=${connection.peerId}: $reason")
        connectingPeers.remove(connection.peerId)
        incomingFrames.remove(connection.peerId)
        reportedPeers.remove(connection.peerId)

        connection.clientConnecting = false
        connection.clientPhase = "reset: $reason"
        connection.isClientReady = false
        connection.clientReadyReported = false
        connection.clientWriteInProgress = false
        connection.clientWriteQueue.clear()
        val gatt = connection.clientGatt
        connection.clientGatt = null
        connection.clientWriteCharacteristic = null
        connection.clientNotifyCharacteristic = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }

        connection.isServerConnected = false
        connection.isServerNotifyReady = false
        connection.serverNotifyInProgress = false
        connection.serverNotifyQueue.clear()
        runCatching { connection.device?.let { device -> gattServer?.cancelConnection(device) } }
        connection.reportedConnected = false
    }

    private fun handleIncomingPacket(peerId: String, packet: ByteArray) {
        val completeBytes = runCatching { acceptPacket(peerId = peerId, packet = packet) }
            .onFailure { error -> MeshLogger.error(TAG, "Ошибка сборки BLE packet от $peerId", error) }
            .getOrNull()
            ?: return

        MeshLogger.info(TAG, "BLE complete frame from $peerId size=${completeBytes.size}")
        listener?.onBytesReceived(peerId, completeBytes)
    }

    private fun createPackets(bytes: ByteArray, mtu: Int): List<ByteArray> {
        val payloadSize = max(1, min(MAX_PAYLOAD_SIZE, mtu - BLE_ATT_OVERHEAD - PACKET_HEADER_SIZE))
        val total = max(1, (bytes.size + payloadSize - 1) / payloadSize)
        val messageId = Random.nextInt()

        return List(total) { index ->
            val from = index * payloadSize
            val to = min(bytes.size, from + payloadSize)
            val payload = bytes.copyOfRange(from, to)
            ByteBuffer.allocate(PACKET_HEADER_SIZE + payload.size)
                .putInt(PACKET_MAGIC)
                .putInt(messageId)
                .putShort(index.toShort())
                .putShort(total.toShort())
                .putInt(bytes.size)
                .put(payload)
                .array()
        }
    }

    private fun acceptPacket(peerId: String, packet: ByteArray): ByteArray? {
        if (packet.size < PACKET_HEADER_SIZE) {
            MeshLogger.warning(TAG, "BLE packet too small from $peerId size=${packet.size}")
            return null
        }

        val buffer = ByteBuffer.wrap(packet)
        val magic = buffer.int
        if (magic != PACKET_MAGIC) {
            MeshLogger.warning(TAG, "BLE packet bad magic from $peerId magic=$magic")
            return null
        }

        val messageId = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        val originalLength = buffer.int
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        if (total <= 0 || index >= total || originalLength < 0) {
            MeshLogger.warning(TAG, "BLE packet invalid header from $peerId index=$index total=$total original=$originalLength")
            return null
        }

        if (total == 1) return payload.copyOf(originalLength)

        val peerFrames = incomingFrames.computeIfAbsent(peerId) { ConcurrentHashMap() }
        val frame = peerFrames.computeIfAbsent(messageId) {
            IncomingFrame(total = total, originalLength = originalLength)
        }

        synchronized(frame) {
            if (frame.parts[index] == null) {
                frame.parts[index] = payload
                frame.received += 1
            }

            if (frame.received != frame.total) return null

            peerFrames.remove(messageId)
            val result = ByteArray(frame.originalLength)
            var offset = 0
            for (partIndex in 0 until frame.total) {
                val part = frame.parts[partIndex] ?: return null
                val copySize = min(part.size, result.size - offset)
                System.arraycopy(part, 0, result, offset, copySize)
                offset += copySize
            }
            return result
        }
    }

    private fun connectionFor(device: BluetoothDevice): BleConnection {
        val peerId = device.address
        return connections.compute(peerId) { _, existing ->
            if (existing == null) {
                BleConnection(peerId = peerId, device = device)
            } else {
                existing.device = device
                existing
            }
        } ?: error("Не удалось создать BLE connection")
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?, result: ScanResult?): String? {
        return runCatching {
            result?.scanRecord?.deviceName
                ?: device?.name
        }.getOrNull()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun reportWarning(message: String) {
        MeshLogger.warning(TAG, message)
        listener?.onError(message)
    }

    private fun reportError(message: String) {
        MeshLogger.error(TAG, message)
        listener?.onError(message)
    }

    private class BleConnection(
        val peerId: String,
        var device: BluetoothDevice?,
    ) {
        var clientGatt: BluetoothGatt? = null
        var clientWriteCharacteristic: BluetoothGattCharacteristic? = null
        var clientNotifyCharacteristic: BluetoothGattCharacteristic? = null
        var clientConnecting: Boolean = false
        var isClientReady: Boolean = false
        var clientReadyReported: Boolean = false
        var clientPhase: String = "idle"

        var isServerConnected: Boolean = false
        var isServerNotifyReady: Boolean = false
        var serverPhase: String = "idle"

        var reportedConnected: Boolean = false
        var mtu: Int = DEFAULT_MTU

        val clientWriteQueue: ArrayDeque<ByteArray> = ArrayDeque()
        var clientWriteInProgress: Boolean = false

        val serverNotifyQueue: ArrayDeque<ByteArray> = ArrayDeque()
        var serverNotifyInProgress: Boolean = false

        fun isReadyForSend(): Boolean = isClientReady || isServerNotifyReady
    }

    private class IncomingFrame(
        val total: Int,
        val originalLength: Int,
    ) {
        val parts: Array<ByteArray?> = arrayOfNulls(total)
        var received: Int = 0
    }

    private companion object {
        const val TAG = "BleGattTransport"

        val SERVICE_UUID: UUID = UUID.fromString("7f6a3d96-8b6d-4a35-9e20-8f2b122d4c91")
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("7f6a3d96-8b6d-4a35-9e20-8f2b122d4c92")
        val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("7f6a3d96-8b6d-4a35-9e20-8f2b122d4c93")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DESIRED_MTU = 512
        const val DISCOVER_SERVICES_DELAY_MS = 600L
        const val CLIENT_CONNECT_TIMEOUT_MS = 20000L
        const val DEFAULT_MTU = 23
        const val BLE_ATT_OVERHEAD = 3
        const val PACKET_HEADER_SIZE = 16
        const val MAX_PAYLOAD_SIZE = 180
        const val PACKET_MAGIC = 0x4D534731 // "MSG1"
    }
}
