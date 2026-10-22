package app.locate.domain.alert

import app.locate.core.location.Fix

enum class EpisodeState { PENDING, ALERTED, CANCELLED, SUPPRESSED, EXPIRED, DISMISSED }

/** One disconnect of one device, from the disconnect until it is resolved one way or another. */
data class Episode(
    val deviceId: Long,
    val startedAt: Long,
    val state: EpisodeState = EpisodeState.PENDING,
    /** The phone's location when the device disconnected: our best guess of where it was left. */
    val anchor: Fix? = null,
    val awayStreak: Int = 0,
    val lastSeparationM: Double = 0.0,
    val maxSeparationM: Double = 0.0,
)

sealed interface DetectorInput {
    val at: Long

    /** [seeded]: already connected when monitoring started, so how long it has been connected is unknown. */
    data class Connected(val deviceId: Long, override val at: Long, val seeded: Boolean = false) : DetectorInput
    data class Disconnected(val deviceId: Long, override val at: Long) : DetectorInput
    data class Seen(val deviceId: Long, val rssi: Int, override val at: Long) : DetectorInput
    data class LocationFix(val fix: Fix, override val at: Long) : DetectorInput
    data class AdapterTurningOff(override val at: Long) : DetectorInput
    data class Dismissed(val deviceId: Long, override val at: Long) : DetectorInput
    data class Tick(override val at: Long) : DetectorInput
}

sealed interface DetectorOutput {
    /** Persist this episode (keyed by deviceId + startedAt) with a human-readable reason for the log. */
    data class EpisodeChanged(val episode: Episode, val reason: String) : DetectorOutput

    /** Show "you may have left it behind". */
    data class Alert(val episode: Episode) : DetectorOutput

    /** The device is back; remove any alert notification for it. */
    data class ClearAlert(val deviceId: Long) : DetectorOutput
}
