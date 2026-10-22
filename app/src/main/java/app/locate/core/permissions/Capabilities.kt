package app.locate.core.permissions

/** Everything that decides whether alerts can work, captured at one moment. */
data class PermissionSnapshot(
    val sdk: Int,
    val granted: Set<PermissionStepId>,
    /** False when the user picked "Approximate" location on API 31+. */
    val preciseLocation: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val bluetoothEnabled: Boolean,
)

enum class Severity { BLOCKING, WARNING }

enum class IssueId { BLUETOOTH_PERMISSION, LOCATION_PERMISSION, PRECISE_LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY_OPTIMIZATION, BLUETOOTH_OFF }

data class CapabilityIssue(val id: IssueId, val severity: Severity, val title: String, val detail: String)

/** Turns a [PermissionSnapshot] into the plain-language problems shown on the home screen. */
object Capabilities {
    fun issues(s: PermissionSnapshot): List<CapabilityIssue> = buildList {
        val steps = PermissionSpec.steps(s.sdk).map { it.id }.toSet()
        fun missing(id: PermissionStepId) = id in steps && id !in s.granted

        if (missing(PermissionStepId.BLUETOOTH)) {
            add(CapabilityIssue(IssueId.BLUETOOTH_PERMISSION, Severity.BLOCKING, "Nearby devices permission is off",
                "Locate can't tell when your devices connect or disconnect."))
        }
        if (missing(PermissionStepId.LOCATION)) {
            add(CapabilityIssue(IssueId.LOCATION_PERMISSION, Severity.BLOCKING, "Location permission is off",
                "Alerts need your phone's location to know you've moved away from a device."))
        } else if (!s.preciseLocation) {
            add(CapabilityIssue(IssueId.PRECISE_LOCATION, Severity.BLOCKING, "Precise location is off",
                "Approximate location is off by up to a few kilometres, which is too coarse for alerts."))
        }
        if (missing(PermissionStepId.NOTIFICATIONS)) {
            add(CapabilityIssue(IssueId.NOTIFICATIONS, Severity.BLOCKING, "Notifications are off",
                "You won't see \"left behind\" alerts."))
        }
        if (missing(PermissionStepId.BACKGROUND_LOCATION)) {
            add(CapabilityIssue(IssueId.BACKGROUND_LOCATION, Severity.WARNING, "Location isn't allowed all the time",
                "Alerts stop working after a restart, or when Android moves Locate to the background."))
        }
        if (!s.batteryOptimizationIgnored) {
            add(CapabilityIssue(IssueId.BATTERY_OPTIMIZATION, Severity.WARNING, "Battery optimisation is on",
                "Android may stop Locate in the background, and then alerts stop."))
        }
        if (!s.bluetoothEnabled) {
            add(CapabilityIssue(IssueId.BLUETOOTH_OFF, Severity.WARNING, "Bluetooth is off", "Turn it on to watch your devices."))
        }
    }

    fun alertsPossible(s: PermissionSnapshot) = issues(s).none { it.severity == Severity.BLOCKING }
}
