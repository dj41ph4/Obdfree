package com.obdfree.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdfree.app.core.ObdPrefs
import com.obdfree.app.core.ObdRuntime
import com.obdfree.app.core.obd.AstraJDpfMonitor
import com.obdfree.app.model.DpfUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DpfViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(ObdPrefs.NAME, Context.MODE_PRIVATE)
    private val transport = ObdRuntime.transport(application)
    private val client = ObdRuntime.client(application)
    private val monitor = AstraJDpfMonitor(client)

    private val _state = MutableStateFlow(
        DpfUiState(
            autoDetectionEnabled = prefs.getBoolean(ObdPrefs.DPF_AUTO_DETECT, false),
        ),
    )
    val state: StateFlow<DpfUiState> = _state.asStateFlow()

    private var autoJob: Job? = null

    init {
        if (_state.value.autoDetectionEnabled) startAutoLoop()
    }

    fun setAutoDetectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ObdPrefs.DPF_AUTO_DETECT, enabled).apply()
        _state.update {
            it.copy(
                autoDetectionEnabled = enabled,
                errorMessage = if (enabled) null else it.errorMessage,
            )
        }

        if (enabled) {
            startAutoLoop()
        } else {
            autoJob?.cancel()
            autoJob = null
        }
    }

    fun refreshNow() {
        if (_state.value.busy) return
        viewModelScope.launch { refreshFullSnapshot(showBusy = true) }
    }

    private fun startAutoLoop() {
        if (autoJob?.isActive == true) return

        autoJob = viewModelScope.launch {
            var fullRefreshAt = 0L

            while (isActive && _state.value.autoDetectionEnabled) {
                try {
                    if (prefs.getBoolean(ObdPrefs.CONTINUOUS_CLEAR, false)) {
                        _state.update {
                            it.copy(
                                busy = false,
                                errorMessage = "Détection FAP suspendue pendant l’effacement continu.",
                            )
                        }
                        delay(5_000)
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (_state.value.snapshot == null || now >= fullRefreshAt) {
                        refreshFullSnapshot(showBusy = false)
                        fullRefreshAt = now + FULL_REFRESH_INTERVAL_MS
                    } else {
                        refreshRegenerationStatus()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _state.update {
                        it.copy(
                            busy = false,
                            errorMessage = friendlyError(error),
                        )
                    }
                }

                delay(REGEN_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshFullSnapshot(showBusy: Boolean) {
        if (!hasBluetoothPermission()) {
            _state.update {
                it.copy(
                    busy = false,
                    errorMessage = "Autorisation Bluetooth requise pour lire le FAP.",
                )
            }
            return
        }

        if (prefs.getBoolean(ObdPrefs.CONTINUOUS_CLEAR, false)) {
            _state.update {
                it.copy(
                    busy = false,
                    errorMessage = "Désactive l’effacement continu avant de lire le FAP.",
                )
            }
            return
        }

        if (showBusy) {
            _state.update { it.copy(busy = true, errorMessage = null) }
        }

        try {
            val snapshot = ObdRuntime.exclusive {
                ensureConnected()
                monitor.readSnapshot()
            }

            _state.update {
                it.copy(
                    snapshot = snapshot,
                    busy = false,
                    errorMessage = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    busy = false,
                    errorMessage = friendlyError(error),
                )
            }
        }
    }

    private suspend fun refreshRegenerationStatus() {
        if (!hasBluetoothPermission()) return
        if (prefs.getBoolean(ObdPrefs.CONTINUOUS_CLEAR, false)) return

        try {
            val status = ObdRuntime.exclusive {
                ensureConnected()
                monitor.readRegenerationStatus()
            }

            _state.update { current ->
                current.copy(
                    snapshot = current.snapshot?.copy(
                        regenerationActive = status.active,
                        regenerationProgressPercent = status.progressPercent,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                    errorMessage = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update { it.copy(errorMessage = friendlyError(error)) }
        }
    }

    private suspend fun ensureConnected() {
        if (transport.isConnected) return

        val address = prefs.getString(ObdPrefs.LAST_DEVICE, null)
            ?: error("Aucun adaptateur OBD mémorisé. Connecte d’abord ton ELM327.")

        transport.connect(address)
        client.initialize()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("aucun PID FAP", ignoreCase = true) -> raw
            raw.contains("223274", ignoreCase = true) -> raw
            raw.contains("aucun calculateur", ignoreCase = true) -> "Calculateur moteur indisponible."
            raw.contains("Bluetooth", ignoreCase = true) -> "Communication Bluetooth interrompue."
            raw.isNotBlank() -> raw.lineSequence().first().take(220)
            else -> "Lecture FAP impossible."
        }
    }

    private companion object {
        const val REGEN_POLL_INTERVAL_MS = 2_000L
        const val FULL_REFRESH_INTERVAL_MS = 10_000L
    }
}
