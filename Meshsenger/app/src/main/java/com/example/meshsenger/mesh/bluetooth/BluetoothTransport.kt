package com.example.meshsenger.mesh.bluetooth

interface BluetoothTransport {
    fun startScan()
    fun stopScan()

    fun startAdvertising()
    fun stopAdvertising()

    fun connect(peerId: String)
    fun disconnect(peerId: String)

    fun send(peerId: String, bytes: ByteArray)

    fun setListener(listener: BluetoothTransportListener)
}
