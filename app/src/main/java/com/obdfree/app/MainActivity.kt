package com.obdfree.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.obdfree.app.service.ContinuousClearService
import com.obdfree.app.ui.ObdFreeApp
import com.obdfree.app.ui.theme.ObdTheme
import com.obdfree.app.update.AppUpdateState
import com.obdfree.app.update.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()
    private val updater by lazy { AppUpdater(this) }

    private var updateState by mutableStateOf<AppUpdateState>(AppUpdateState.Checking)
    private var pendingUpdateApk: File? = null

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted)
        }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingUpdateApk ?: return@registerForActivityResult
            if (updater.canRequestPackageInstalls()) {
                pendingUpdateApk = null
                runCatching { updater.install(this, apk) }
                    .onFailure { updateState = AppUpdateState.Error("Installation impossible") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ObdTheme {
                val state by viewModel.state.collectAsState()

                ObdFreeApp(
                    state = state,
                    updateState = updateState,
                    onInstallUpdate = ::installAvailableUpdate,
                    onRequestBluetoothPermission = ::requestBluetoothPermission,
                    onRefreshAdapters = { viewModel.refreshPairedAdapters() },
                    onSelectAdapter = viewModel::selectAdapter,
                    onConnect = viewModel::connect,
                    onScan = viewModel::scan,
                    onClearCodes = viewModel::clearCodes,
                    onContinuousClearChanged = ::setContinuousClear,
                    continuousWarningAccepted = viewModel::continuousWarningAccepted,
                    onContinuousWarningAccepted = viewModel::markContinuousWarningAccepted,
                )
            }
        }

        if (viewModel.state.value.continuousClearEnabled) {
            runCatching { ContinuousClearService.start(this) }
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            updateState = AppUpdateState.Checking

            try {
                val release = updater.checkForUpdate()
                if (release == null) {
                    updateState = AppUpdateState.UpToDate
                    return@launch
                }

                updateState = AppUpdateState.Downloading(release.version)
                val apk = updater.downloadAndVerify(release)
                updateState = AppUpdateState.Ready(release, apk)
            } catch (error: Throwable) {
                updateState = AppUpdateState.Error(
                    error.message?.lineSequence()?.firstOrNull()?.take(120)
                        ?: "Vérification des mises à jour impossible",
                )
            }
        }
    }

    private fun installAvailableUpdate() {
        val ready = updateState as? AppUpdateState.Ready ?: return

        if (!updater.canRequestPackageInstalls()) {
            pendingUpdateApk = ready.apk
            runCatching { unknownSourcesLauncher.launch(updater.unknownSourcesIntent()) }
                .onFailure { updateState = AppUpdateState.Error("Autorisation d’installation requise") }
            return
        }

        runCatching { updater.install(this, ready.apk) }
            .onFailure { updateState = AppUpdateState.Error("Installation impossible") }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            viewModel.onPermissionResult(true)
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.onPermissionResult(true)
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun setContinuousClear(enabled: Boolean) {
        if (enabled) {
            requestBatteryOptimizationExemptionIfNeeded()
            viewModel.setContinuousClearEnabled(true)
            ContinuousClearService.start(this)
        } else {
            viewModel.setContinuousClearEnabled(false)
            ContinuousClearService.stop(this)
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        runCatching { startActivity(directRequest) }
            .onFailure { runCatching { startActivity(fallback) } }
    }
}
