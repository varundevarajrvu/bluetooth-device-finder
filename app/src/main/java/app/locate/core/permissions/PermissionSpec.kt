package app.locate.core.permissions

import android.Manifest
import android.annotation.SuppressLint

enum class PermissionStepId { BLUETOOTH, LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS }

/** One runtime-permission request, shown behind its own rationale screen. */
data class PermissionStep(val id: PermissionStepId, val permissions: List<String>)

/** Which runtime permissions Locate needs on a given API level. Constants are inlined strings, so safe on older APIs. */
@SuppressLint("InlinedApi")
object PermissionSpec {
    fun steps(sdk: Int): List<PermissionStep> = buildList {
        // API <= 30: BLUETOOTH / BLUETOOTH_ADMIN are install-time; scanning is gated by location instead.
        if (sdk >= 31) {
            add(PermissionStep(PermissionStepId.BLUETOOTH, listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)))
        }
        // COARSE is requested alongside FINE so API 31+ shows the precise/approximate choice.
        add(PermissionStep(PermissionStepId.LOCATION, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        // API 26-28: foreground location also covers background.
        if (sdk >= 29) {
            add(PermissionStep(PermissionStepId.BACKGROUND_LOCATION, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
        }
        if (sdk >= 33) {
            add(PermissionStep(PermissionStepId.NOTIFICATIONS, listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
    }

    /** API 30+ never shows "Allow all the time" in the dialog; the request sends the user to Settings. */
    fun backgroundLocationViaSettings(sdk: Int) = sdk >= 30
}
