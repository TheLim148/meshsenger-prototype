package com.example.meshsenger.mesh.bluetooth

interface BluetoothTransportListener {
    fun onPeerFound(peerId: String, name: String?)
    fun onPeerConnected(peerId: String)
    fun onPeerDisconnected(peerId: String)
    fun onBytesReceived(peerId: String, bytes: ByteArray)
    fun onError(message: String)
}
