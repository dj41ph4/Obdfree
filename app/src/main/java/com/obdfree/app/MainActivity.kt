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
import androidx.core.content.ContextCompat
import com.obdfree.app.service.ContinuousClearService
import com.obdfree.app.ui.ObdFreeApp
import com.obdfree.app.ui.theme.ObdTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ObdTheme {
                val state by viewModel.state.collectAsState()

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
            }
        }

        if (viewModel.state.value.continuousClearEnabled) {
            runCatching { ContinuousClearService.start(this) }
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
