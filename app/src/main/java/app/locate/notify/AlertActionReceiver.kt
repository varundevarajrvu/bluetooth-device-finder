package app.locate.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.locate.di.ApplicationScope
import app.locate.service.PresenceMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles "It's with me" on a left-behind alert. */
@AndroidEntryPoint
class AlertActionReceiver : BroadcastReceiver() {
    @Inject lateinit var monitor: PresenceMonitor
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        if (intent.action != ACTION_DISMISS || deviceId < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                monitor.dismiss(deviceId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_DISMISS = "app.locate.action.DISMISS_ALERT"
        private const val EXTRA_DEVICE_ID = "deviceId"

        fun dismissIntent(context: Context, deviceId: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            deviceId.toInt(),
            Intent(context, AlertActionReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_DEVICE_ID, deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
