package com.obdfree.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdfree.app.core.obd.Elm327Client
import com.obdfree.app.core.transport.BluetoothClassicTransport
import com.obdfree.app.model.BluetoothAdapterItem
import com.obdfree.app.model.ConnectionState
import com.obdfree.app.model.DiagnosticUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ObdViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val transport = BluetoothClassicTransport(application)
    private val client = Elm327Client(transport)

    private val _state = MutableStateFlow(
        DiagnosticUiState(
            permissionGranted = hasBluetoothPermission(),
        ),
    )
    val state: StateFlow<DiagnosticUiState> = _state.asStateFlow()

    private var liveJob: Job? = null

    init {
        if (_state.value.permissionGranted) {
            refreshPairedAdapters()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                permissionGranted = granted,
                errorMessage = if (granted) null else "Autorisation Bluetooth requise",
            )
        }
        if (granted) refreshPairedAdapters()
    }

    fun refreshPairedAdapters() {
        if (!hasBluetoothPermission()) return

        runCatching { transport.pairedDevices() }
            .onSuccess { devices ->
                _state.update { current ->
                    val selected = current.selectedAdapter
                        ?.takeIf { old -> devices.any { it.address == old.address } }
                        ?: devices.firstOrNull { looksLikeObd(it) }
                        ?: devices.firstOrNull()

                    current.copy(
                        pairedAdapters = devices,
                        selectedAdapter = selected,
                        statusMessage = if (devices.isEmpty()) {
                            "Aucun appareil Bluetooth appairé"
                        } else {
                            "${devices.size} appareil(s) appairé(s)"
                        },
                        errorMessage = null,
                    )
                }
            }
            .onFailure(::setError)
    }

    fun selectAdapter(adapter: BluetoothAdapterItem) {
        _state.update {
            it.copy(
                selectedAdapter = adapter,
                errorMessage = null,
            )
        }
    }

    fun connect() {
        val adapter = _state.value.selectedAdapter
            ?: return setError(IllegalStateException("Choisis un adaptateur Bluetooth"))

        viewModelScope.launch {
            liveJob?.cancel()
            _state.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTING,
                    isBusy = true,
                    statusMessage = "Connexion à ${adapter.name}…",
                    errorMessage = null,
                )
            }

            try {
                transport.connect(adapter.address)

                _state.update {
                    it.copy(
                        connectionState = ConnectionState.INITIALIZING,
                        statusMessage = "Initialisation ELM327…",
                    )
                }

                val info = client.initialize()

                _state.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        adapterIdentity = info.identity,
                        protocol = info.protocol,
                        isBusy = false,
                        statusMessage = "Connecté",
                    )
                }

                scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { transport.disconnect() }
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        isBusy = false,
                        statusMessage = "Connexion impossible",
                        errorMessage = error.message ?: "Erreur Bluetooth/OBD",
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            liveJob?.cancel()
            transport.disconnect()
            _state.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    adapterIdentity = null,
                    protocol = null,
                    liveData = com.obdfree.app.model.LiveDataSnapshot(),
                    isBusy = false,
                    statusMessage = "Déconnecté",
                )
            }
        }
    }

    fun scan() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Lecture des défauts…",
                    errorMessage = null,
                )
            }

            try {
                val codes = client.readTroubleCodes()
                val live = runCatching { client.readLiveData() }
                    .getOrDefault(_state.value.liveData)

                _state.update {
                    it.copy(
                        troubleCodes = codes,
                        liveData = live,
                        isBusy = false,
                        statusMessage = if (codes.isEmpty()) {
                            "Aucun défaut OBD-II détecté"
                        } else {
                            "${codes.size} défaut(s) détecté(s)"
                        },
                    )
                }
            } catch (error: Throwable) {
                setError(error)
            }
        }
    }

    fun refreshLiveData() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return

        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            try {
                val live = client.readLiveData()
                _state.update {
                    it.copy(
                        liveData = live,
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                setError(error)
            }
        }
    }

    fun clearCodes() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Effacement des informations diagnostic…",
                    errorMessage = null,
                )
            }

            try {
                val cleared = client.clearTroubleCodes()
                if (!cleared) error("Le véhicule n'a pas confirmé l'effacement")

                _state.update {
                    it.copy(
                        troubleCodes = emptyList(),
                        isBusy = false,
                        statusMessage = "Commande d'effacement confirmée",
                    )
                }
            } catch (error: Throwable) {
                setError(error)
            }
        }
    }

    private fun setError(error: Throwable) {
        _state.update {
            it.copy(
                isBusy = false,
                errorMessage = error.message ?: "Erreur inconnue",
                statusMessage = "Une erreur est survenue",
            )
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun looksLikeObd(item: BluetoothAdapterItem): Boolean {
        val text = item.name.lowercase()
        return listOf("obd", "elm", "vgate", "vlink", "obdlink")
            .any(text::contains)
    }
}
