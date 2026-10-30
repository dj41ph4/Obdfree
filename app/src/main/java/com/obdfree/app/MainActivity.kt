package com.obdfree.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
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
                    onRefreshAdapters = viewModel::refreshPairedAdapters,
                    onSelectAdapter = viewModel::selectAdapter,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onScan = viewModel::scan,
                    onRefreshLive = viewModel::refreshLiveData,
                    onClearCodes = viewModel::clearCodes,
                )
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
}
