package com.obdfree.app.core.transport

interface ObdTransport {
    val isConnected: Boolean

    suspend fun connect(address: String)
    suspend fun disconnect()
    suspend fun write(data: ByteArray)
    suspend fun readAvailable(): ByteArray
}
