package com.obdfree.app.model

data class BluetoothAdapterItem(
    val name: String,
    val address: String,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    CONNECTED,
    ERROR,
}

data class TroubleCode(
    val code: String,
    val category: String,
    val description: String? = null,
)

data class LiveDataSnapshot(
    val rpm: Int? = null,
    val speedKph: Int? = null,
    val coolantCelsius: Int? = null,
    val throttlePercent: Float? = null,
)

data class DiagnosticUiState(
    val permissionGranted: Boolean = false,
    val bluetoothAvailable: Boolean = true,
    val pairedAdapters: List<BluetoothAdapterItem> = emptyList(),
    val selectedAdapter: BluetoothAdapterItem? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val adapterIdentity: String? = null,
    val protocol: String? = null,
    val troubleCodes: List<TroubleCode> = emptyList(),
    val liveData: LiveDataSnapshot = LiveDataSnapshot(),
    val isBusy: Boolean = false,
    val statusMessage: String = "Prêt",
    val errorMessage: String? = null,
)
