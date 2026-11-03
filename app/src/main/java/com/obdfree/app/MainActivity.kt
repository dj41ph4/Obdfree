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
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.obdfree.app.service.ContinuousClearService
import com.obdfree.app.ui.DpfOverlay
import com.obdfree.app.ui.ObdFreeApp
import com.obdfree.app.ui.theme.ObdTheme
import com.obdfree.app.update.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()
    private val dpfViewModel: DpfViewModel by viewModels()
    private val updater by lazy { AppUpdater(this) }
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
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ObdTheme {
                val state by viewModel.state.collectAsState()
                val dpfState by dpfViewModel.state.collectAsState()

                Box {
                    ObdFreeApp(
                        state = state,
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

                    DpfOverlay(
                        state = dpfState,
                        continuousClearEnabled = state.continuousClearEnabled,
                        onAutoDetectionChanged = dpfViewModel::setAutoDetectionEnabled,
                        onRefresh = dpfViewModel::refreshNow,
                        onProbeServiceControl = dpfViewModel::probeServiceControl,
                    )
                }
            }
        }

        if (viewModel.state.value.continuousClearEnabled) {
            runCatching { ContinuousClearService.start(this) }
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val release = runCatching { updater.checkForUpdate() }.getOrNull() ?: return@launch
            val apk = runCatching { updater.downloadAndVerify(release) }.getOrNull() ?: return@launch
            pendingUpdateApk = apk

            if (updater.canRequestPackageInstalls()) {
                pendingUpdateApk = null
                runCatching { updater.install(this@MainActivity, apk) }
            } else {
                runCatching { unknownSourcesLauncher.launch(updater.unknownSourcesIntent()) }
            }
        }
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
