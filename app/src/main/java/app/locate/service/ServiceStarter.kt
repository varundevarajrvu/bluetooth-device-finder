package app.locate.service

import android.content.Context
import app.locate.core.permissions.PermissionChecker
import app.locate.core.prefs.AppPrefs
import app.locate.notify.Notifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides whether to (re)start [MonitorService]. Called from the UI, boot,
 * the ACL receiver and the watchdog. If Android refuses a background start, it tells the user
 * instead of failing silently.
 */
@Singleton
class ServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
    private val permissions: PermissionChecker,
    private val notifications: Notifications,
) {
    fun ensureRunning(): Boolean {
        if (!prefs.monitoringEnabled || !permissions.hasBluetoothPermissions()) return false
        if (MonitorService.isRunning) return true
        val started = MonitorService.start(context)
        if (started) notifications.clearServiceStopped() else notifications.showServiceStopped()
        return started
    }

    fun stop() = MonitorService.stop(context)
}
