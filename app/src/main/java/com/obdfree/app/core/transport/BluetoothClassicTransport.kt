package com.obdfree.app.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.obdfree.app.model.BluetoothAdapterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothClassicTransport(
    context: Context,
) : ObdTransport {

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter
        get() = bluetoothManager?.adapter

    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothAdapterItem> =
        bluetoothAdapter
            ?.bondedDevices
            ?.sortedWith(compareBy({ it.name ?: "" }, { it.address }))
            ?.map {
                BluetoothAdapterItem(
                    name = it.name?.takeIf(String::isNotBlank) ?: "Adaptateur OBD",
                    address = it.address,
                )
            }
            .orEmpty()

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        disconnect()

        val adapter = bluetoothAdapter
            ?: error("Bluetooth indisponible sur cet appareil")

        check(adapter.isEnabled) { "Le Bluetooth est désactivé" }

        val device = adapter.getRemoteDevice(address)

        // Prefer the authenticated RFCOMM link. Some legacy serial boards and cheap
        // ELM327 clones cannot complete an authenticated SPP connection correctly;
        // Android explicitly provides the insecure RFCOMM variant for that case.
        val secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            secureSocket.connect()
            socket = secureSocket
        } catch (secureError: IOException) {
            runCatching { secureSocket.close() }

            val insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                insecureSocket.connect()
                socket = insecureSocket
            } catch (insecureError: IOException) {
                runCatching { insecureSocket.close() }
                insecureError.addSuppressed(secureError)
                throw insecureError
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val activeSocket = socket?.takeIf { it.isConnected }
            ?: error("Adaptateur OBD non connecté")

        activeSocket.outputStream.write(data)
        activeSocket.outputStream.flush()
    }

    override suspend fun readAvailable(): ByteArray = withContext(Dispatchers.IO) {
        val activeSocket = socket?.takeIf { it.isConnected }
            ?: error("Adaptateur OBD non connecté")

        val input = activeSocket.inputStream
        val available = input.available()
        if (available <= 0) return@withContext ByteArray(0)

        ByteArray(available).also { input.read(it) }
    }

    private companion object {
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
