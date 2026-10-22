package app.locate.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.locate.core.ble.BleEventBus
import app.locate.core.ble.BluetoothBroadcasts
import app.locate.core.permissions.PermissionChecker
import app.locate.core.time.Clock
import app.locate.data.repo.DeviceRepository
import app.locate.notify.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Locate alive in the background with a persistent notification and hosts [PresenceMonitor].
 * Stops itself when no devices are registered.
 */
@AndroidEntryPoint
class MonitorService : Service() {
    @Inject lateinit var monitor: PresenceMonitor
    @Inject lateinit var notifications: Notifications
    @Inject lateinit var permissions: PermissionChecker
    @Inject lateinit var devices: DeviceRepository
    @Inject lateinit var bus: BleEventBus
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Monitor failure", t) },
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BluetoothBroadcasts.toEvent(intent, clock.now())?.let(bus::send)
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        isRunning = true
        ContextCompat.registerReceiver(this, receiver, BluetoothBroadcasts.runtimeFilter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        monitor.start(scope)
        scope.launch {
            devices.observeAll().collect { list ->
                if (list.isEmpty()) stopSelf() else notifications.updateMonitoring(list.size)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        monitor.stop()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    /**
     * Tries connectedDevice|location first. The location type is refused when only foreground
     * location is granted and we were started from the background; fall back to connectedDevice,
     * where location still works but Android throttles it.
     */
    @SuppressLint("InlinedApi") // ServiceCompat ignores service types below API 29.
    private fun goForeground(): Boolean {
        val notification = notifications.monitoring(deviceCount = 0)
        val withLocation = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val candidates = if (permissions.hasFineLocation()) {
            listOf(withLocation, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        for (types in candidates) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_MONITORING, notification, types)
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "startForeground refused for types $types", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startForeground not allowed", e)
                return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = "Locate"

        @Volatile var isRunning = false
            private set

        /** False when Android refuses a foreground-service start from the background (API 31+). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+, background-start limits on 26+.
            Log.w(TAG, "Couldn't start monitor (SDK ${Build.VERSION.SDK_INT})", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
