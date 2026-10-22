package app.locate.domain.alert

/**
 * Every tunable threshold of the left-behind alert in one place. Defaults lean towards
 * fewer false alarms: a missed alert is annoying, a stream of false ones gets the app uninstalled.
 */
data class AlertConfig(
    /** Wait this long after a disconnect before an alert may fire; a reconnect or sighting inside it cancels. */
    val debounceMs: Long = 60_000,
    /** Disconnects of links shorter than this are connection flapping, not leaving. */
    val minConnectionMs: Long = 30_000,
    /** A disconnect this close in time to Bluetooth being switched off is the user's doing. */
    val adapterOffWindowMs: Long = 10_000,
    /** Give up on an episode if the phone hasn't moved away within this time. */
    val activeWindowMs: Long = 10 * 60_000,
    /** Location update interval while an episode is pending. */
    val activeFixIntervalMs: Long = 20_000,
    /** Fixes less accurate than this (indoor cell-tower fixes) are ignored. */
    val maxUsableAccuracyM: Float = 75f,
    /** The anchor (where the device was left) may come from a fix taken at most this long before the disconnect. */
    val anchorMaxAgeMs: Long = 60_000,
    /** Accuracy-adjusted distance from the anchor that counts as "moved away". */
    val minDisplacementM: Double = 100.0,
    /** Consecutive fixes that must be away, and not getting closer, before alerting. */
    val requiredAwayFixes: Int = 2,
    /** How much closer a fix may be than the previous one and still count as "moving away" (GPS noise). */
    val awayToleranceM: Double = 15.0,
    /** A scan sighting at least this strong means the device is still with the user. */
    val seenCancelRssi: Int = -80,
)
