package com.obdfree.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdfree.app.core.obd.DtcKnowledgeService
import com.obdfree.app.core.obd.Elm327Client
import com.obdfree.app.core.transport.BluetoothClassicTransport
import com.obdfree.app.model.BluetoothAdapterItem
import com.obdfree.app.model.ConnectionState
import com.obdfree.app.model.DiagnosticUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class ObdViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val transport = BluetoothClassicTransport(application)
    private val client = Elm327Client(transport)
    private val knowledge = DtcKnowledgeService(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        DiagnosticUiState(permissionGranted = hasBluetoothPermission()),
    )
    val state: StateFlow<DiagnosticUiState> = _state.asStateFlow()

    init {
        if (_state.value.permissionGranted) refreshPairedAdapters(autoReconnect = true)
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                permissionGranted = granted,
                errorMessage = if (granted) null else "Autorisation Bluetooth requise",
            )
        }
        if (granted) refreshPairedAdapters(autoReconnect = true)
    }

    fun refreshPairedAdapters(autoReconnect: Boolean = false) {
        if (!hasBluetoothPermission()) return

        runCatching { transport.pairedDevices() }
            .onSuccess { devices ->
                val savedAddress = prefs.getString(KEY_LAST_DEVICE, null)
                val selected = devices.firstOrNull { it.address == savedAddress }
                    ?: _state.value.selectedAdapter?.takeIf { old -> devices.any { it.address == old.address } }
                    ?: devices.firstOrNull { looksLikeObd(it) }
                    ?: devices.firstOrNull()

                _state.update {
                    it.copy(
                        pairedAdapters = devices,
                        selectedAdapter = selected,
                        statusMessage = when {
                            devices.isEmpty() -> "Aucun appareil Bluetooth appairé"
                            selected?.address == savedAddress -> "Adaptateur mémorisé"
                            else -> "Prêt à se connecter"
                        },
                        errorMessage = null,
                    )
                }

                if (
                    autoReconnect &&
                    savedAddress != null &&
                    selected?.address == savedAddress &&
                    _state.value.connectionState == ConnectionState.DISCONNECTED
                ) {
                    connect()
                }
            }
            .onFailure { setError(it, "Bluetooth") }
    }

    fun selectAdapter(adapter: BluetoothAdapterItem) {
        prefs.edit().putString(KEY_LAST_DEVICE, adapter.address).apply()
        _state.update {
            it.copy(
                selectedAdapter = adapter,
                errorMessage = null,
                statusMessage = "${adapter.name} mémorisé",
            )
        }
        connect()
    }

    fun connect() {
        val adapter = _state.value.selectedAdapter
            ?: return setError(IllegalStateException("Choisis un adaptateur Bluetooth"), "Connexion")

        if (_state.value.connectionState == ConnectionState.CONNECTING ||
            _state.value.connectionState == ConnectionState.INITIALIZING
        ) return

        viewModelScope.launch {
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
                        statusMessage = "Initialisation OBD-II…",
                    )
                }

                val info = client.initialize()
                prefs.edit().putString(KEY_LAST_DEVICE, adapter.address).apply()

                _state.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        adapterIdentity = info.identity,
                        protocol = info.protocol,
                        isBusy = false,
                        statusMessage = "Connecté à ${adapter.name}",
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { transport.disconnect() }
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        isBusy = false,
                        statusMessage = "Connexion impossible",
                        errorMessage = friendlyError(error, adapter.name),
                    )
                }
            }
        }
    }

    fun scan() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Lecture des codes défaut…",
                    errorMessage = null,
                )
            }

            try {
                val rawCodes = client.readTroubleCodes()
                val codes = knowledge.enrich(rawCodes)

                _state.update {
                    it.copy(
                        troubleCodes = codes,
                        isBusy = false,
                        statusMessage = if (codes.isEmpty()) {
                            "Aucun défaut OBD-II détecté"
                        } else {
                            "${codes.size} défaut(s) détecté(s)"
                        },
                    )
                }
            } catch (error: Throwable) {
                setError(error, "Lecture impossible")
            }
        }
    }

    fun clearCodes() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Effacement des défauts…",
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
                        statusMessage = "Défauts effacés",
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                setError(error, "Effacement impossible")
            }
        }
    }

    private fun setError(error: Throwable, fallback: String) {
        _state.update {
            it.copy(
                isBusy = false,
                errorMessage = friendlyError(error, fallback),
                statusMessage = "Une erreur est survenue",
            )
        }
    }

    private fun friendlyError(error: Throwable, context: String): String = when (error) {
        is SecurityException -> "Autorisation Bluetooth manquante. Vérifie l’accès aux appareils à proximité."
        is IOException -> "Impossible de communiquer avec $context. Vérifie que le dongle OBD est alimenté et à portée."
        else -> error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(180)
            ?.takeIf { it.isNotBlank() }
            ?: context
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun looksLikeObd(item: BluetoothAdapterItem): Boolean {
        val text = item.name.lowercase()
        return listOf("obd", "elm", "vgate", "vlink", "obdlink").any(text::contains)
    }

    private companion object {
        const val PREFS_NAME = "obdfree"
        const val KEY_LAST_DEVICE = "last_device_address"
    }
}
