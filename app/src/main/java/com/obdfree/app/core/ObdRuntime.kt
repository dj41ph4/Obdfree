package com.obdfree.app.core

import android.content.Context
import com.obdfree.app.core.obd.Elm327Client
import com.obdfree.app.core.transport.BluetoothClassicTransport

object ObdRuntime {
    @Volatile
    private var transportInstance: BluetoothClassicTransport? = null

    @Volatile
    private var clientInstance: Elm327Client? = null

    fun transport(context: Context): BluetoothClassicTransport =
        transportInstance ?: synchronized(this) {
            transportInstance ?: BluetoothClassicTransport(context.applicationContext)
                .also { transportInstance = it }
        }

    fun client(context: Context): Elm327Client =
        clientInstance ?: synchronized(this) {
            clientInstance ?: Elm327Client(transport(context))
                .also { clientInstance = it }
        }
}

object ObdPrefs {
    const val NAME = "obdfree"
    const val LAST_DEVICE = "last_device_address"
    const val CONTINUOUS_CLEAR = "continuous_clear_enabled"
    const val CONTINUOUS_LAST_RUN = "continuous_clear_last_run"
    const val CONTINUOUS_LAST_ERROR = "continuous_clear_last_error"
    const val CONTINUOUS_WARNING_ACCEPTED = "continuous_clear_warning_accepted"
}
