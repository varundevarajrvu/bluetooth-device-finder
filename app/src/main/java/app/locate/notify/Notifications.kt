package app.locate.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.locate.MainActivity
import app.locate.R
import app.locate.data.db.Device
import app.locate.domain.alert.Episode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** What the monitor needs to tell the user. An interface so tests can record alerts. */
interface AlertNotifier {
    fun showLeftBehind(device: Device, episode: Episode)
    fun clearLeftBehind(deviceId: Long)
}

@Singleton
class Notifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertNotifier {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MONITORING, "Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The always-on notification shown while Locate watches your devices"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, "Left-behind alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "\"You may have left it behind\" alerts"
                },
                NotificationChannel(CHANNEL_SERVICE, "Service problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shown when Android stopped Locate in the background"
                },
            ),
        )
    }

    fun monitoring(deviceCount: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_locate)
            .setContentTitle(if (deviceCount == 1) "Watching 1 device" else "Watching $deviceCount devices")
            .setContentText("You'll get an alert if you seem to leave one behind")
            .setContentIntent(openApp(REQUEST_OPEN, deviceId = null))
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateMonitoring(deviceCount: Int) = notify(ID_MONITORING, monitoring(deviceCount))

    override fun showLeftBehind(device: Device, episode: Episode) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(episode.startedAt))
        val text = "Disconnected at $time. You're now at least ${episode.lastSeparationM.roundToInt()} m from where it was."
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_locate)
            .setContentTitle("You may have left ${device.nickname} behind")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text Location shown is where your phone was, not live tracking."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(device.id.toInt(), device.id))
            .addAction(0, "It's with me", AlertActionReceiver.dismissIntent(context, device.id))
        episode.anchor?.let { anchor ->
            val geo = "geo:${anchor.lat},${anchor.lng}?q=${anchor.lat},${anchor.lng}(${Uri.encode(device.nickname)})".toUri()
            val view = PendingIntent.getActivity(
                context, device.id.toInt(), Intent(Intent.ACTION_VIEW, geo),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Show where", view)
        }
        notify(alertId(device.id), builder.build())
    }

    override fun clearLeftBehind(deviceId: Long) = manager.cancel(alertId(deviceId))

    fun showServiceStopped() = notify(
        ID_SERVICE_STOPPED,
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_locate)
            .setContentTitle("Locate stopped watching your devices")
            .setContentText("Tap to resume. Allow Locate to run in the background to stop this happening.")
            .setContentIntent(openApp(REQUEST_RESUME, deviceId = null))
            .setAutoCancel(true)
            .build(),
    )

    fun clearServiceStopped() = manager.cancel(ID_SERVICE_STOPPED)

    private fun openApp(requestCode: Int, deviceId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (deviceId != null) intent.putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Posting without POST_NOTIFICATIONS is a no-op, which the capability banner already explains. */
    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    private fun alertId(deviceId: Long) = ID_ALERT_BASE + deviceId.toInt()

    companion object {
        const val CHANNEL_MONITORING = "monitoring"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_SERVICE = "service"
        const val ID_MONITORING = 1
        const val ID_SERVICE_STOPPED = 2
        private const val ID_ALERT_BASE = 10_000
        private const val REQUEST_OPEN = -1
        private const val REQUEST_RESUME = -2
    }
}
