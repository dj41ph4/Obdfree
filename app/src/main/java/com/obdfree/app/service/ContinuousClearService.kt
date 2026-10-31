package com.obdfree.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.obdfree.app.MainActivity
import com.obdfree.app.R
import com.obdfree.app.core.ObdPrefs
import com.obdfree.app.core.ObdRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ContinuousClearService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val prefs by lazy {
        getSharedPreferences(ObdPrefs.NAME, Context.MODE_PRIVATE)
    }

    private val transport by lazy { ObdRuntime.transport(this) }
    private val client by lazy { ObdRuntime.client(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(ObdPrefs.CONTINUOUS_CLEAR, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.edit().putBoolean(ObdPrefs.CONTINUOUS_CLEAR, true).apply()
        startAsForeground("Connexion au dongle OBD…")

        if (loopJob?.isActive != true) {
            loopJob = scope.launch { clearLoop() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { transport.disconnect() }
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun clearLoop() {
        while (scope.isActive) {
            try {
                val address = prefs.getString(ObdPrefs.LAST_DEVICE, null)
                    ?: error("Aucun adaptateur OBD mémorisé")

                if (!transport.isConnected) {
                    updateNotification("Reconnexion au dongle OBD…")
                    transport.connect(address)
                    client.initialize()
                }

                val cleared = client.clearTroubleCodes()
                if (!cleared) error("Le véhicule n’a pas confirmé l’effacement")

                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong(ObdPrefs.CONTINUOUS_LAST_RUN, now)
                    .remove(ObdPrefs.CONTINUOUS_LAST_ERROR)
                    .apply()

                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
                updateNotification("Actif • dernier effacement à $time")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = friendlyError(error)
                prefs.edit()
                    .putString(ObdPrefs.CONTINUOUS_LAST_ERROR, message)
                    .apply()

                updateNotification("Reconnexion automatique • $message")
                runCatching { transport.disconnect() }
            }

            delay(INTERVAL_MS)
        }
    }

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obdfree)
            .setContentTitle("OBDfree • Effacement continu")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nEffacement OBD-II automatique toutes les 30 secondes."))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "Arrêter",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, ContinuousClearService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Effacement OBD continu",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintient la connexion OBDfree active en arrière-plan."
            setSound(null, null)
            enableVibration(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:continuous-clear",
        ).apply {
            setReferenceCounted(false)
            if (!isHeld) acquire()
        }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("aucun calculateur", ignoreCase = true) -> "calculateur indisponible"
            raw.contains("UNABLE TO CONNECT", ignoreCase = true) -> "véhicule non joignable"
            raw.contains("Bluetooth", ignoreCase = true) -> "Bluetooth indisponible"
            raw.isNotBlank() -> raw.lineSequence().first().take(80)
            else -> "communication OBD interrompue"
        }
    }

    companion object {
        private const val CHANNEL_ID = "continuous_clear"
        private const val NOTIFICATION_ID = 42
        private const val INTERVAL_MS = 30_000L
        private const val ACTION_START = "com.obdfree.app.action.START_CONTINUOUS_CLEAR"
        private const val ACTION_STOP = "com.obdfree.app.action.STOP_CONTINUOUS_CLEAR"

        fun start(context: Context) {
            val intent = Intent(context, ContinuousClearService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContinuousClearService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
