package app.locate.domain.alert

import app.locate.core.geo.Geo
import app.locate.domain.alert.DetectorInput.AdapterTurningOff
import app.locate.domain.alert.DetectorInput.Connected
import app.locate.domain.alert.DetectorInput.Disconnected
import app.locate.domain.alert.DetectorInput.Dismissed
import app.locate.domain.alert.DetectorInput.LocationFix
import app.locate.domain.alert.DetectorInput.Seen
import app.locate.domain.alert.DetectorInput.Tick
import app.locate.domain.alert.DetectorOutput.Alert
import app.locate.domain.alert.DetectorOutput.ClearAlert
import app.locate.domain.alert.DetectorOutput.EpisodeChanged
import app.locate.domain.alert.EpisodeState.ALERTED
import app.locate.domain.alert.EpisodeState.CANCELLED
import app.locate.domain.alert.EpisodeState.DISMISSED
import app.locate.domain.alert.EpisodeState.EXPIRED
import app.locate.domain.alert.EpisodeState.PENDING
import app.locate.domain.alert.EpisodeState.SUPPRESSED
import app.locate.domain.zone.SafeZoneEvaluator
import app.locate.domain.zone.ZoneArea
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decides whether a disconnect means "left behind". Pure Kotlin, no Android, no clock of its own:
 * every input carries its time. Not thread-safe; the caller serialises calls.
 *
 * An alert fires only when all of these hold:
 *  1. the link had been up for at least [AlertConfig.minConnectionMs];
 *  2. Bluetooth wasn't switched off around the disconnect;
 *  3. no reconnect or strong sighting within the debounce window (or later);
 *  4. the anchor (phone's position at disconnect) is outside every safe zone;
 *  5. [AlertConfig.requiredAwayFixes] consecutive usable fixes are at least
 *     [AlertConfig.minDisplacementM] from the anchor, accuracy-adjusted, and not getting closer.
 */
class LeftBehindDetector(private val config: AlertConfig = AlertConfig()) {

    var zones: List<ZoneArea> = emptyList()

    private val connectedSince = mutableMapOf<Long, Long>()
    private val episodes = mutableMapOf<Long, Episode>()
    private var lastAdapterOffAt: Long? = null

    /** True while some episode needs location updates to be resolved. */
    val needsActiveLocation: Boolean get() = episodes.values.any { it.state == PENDING }

    /** Devices worth scanning for right now: a sighting would cancel their pending episode. */
    val scanTargets: Set<Long> get() = episodes.values.filter { it.state == PENDING }.map { it.deviceId }.toSet()

    /** Re-load an unresolved episode after a process restart. */
    fun restore(episode: Episode) {
        if (episode.state == PENDING || episode.state == ALERTED) episodes[episode.deviceId] = episode
    }

    fun handle(input: DetectorInput): List<DetectorOutput> = when (input) {
        is Connected -> onConnected(input)
        is Disconnected -> onDisconnected(input)
        is Seen -> onSeen(input)
        is LocationFix -> onFix(input)
        is AdapterTurningOff -> onAdapterOff(input)
        is Dismissed -> onDismissed(input)
        is Tick -> onTick(input)
    }

    private fun onConnected(e: Connected): List<DetectorOutput> {
        connectedSince[e.deviceId] = if (e.seeded) e.at - config.minConnectionMs else e.at
        val ep = episodes.remove(e.deviceId) ?: return emptyList()
        return when (ep.state) {
            PENDING -> listOf(EpisodeChanged(ep.copy(state = CANCELLED), "Reconnected after ${secs(e.at - ep.startedAt)}"))
            else -> listOf(ClearAlert(e.deviceId))
        }
    }

    private fun onDisconnected(e: Disconnected): List<DetectorOutput> {
        if (episodes[e.deviceId]?.state == PENDING) return emptyList() // duplicate broadcast
        episodes.remove(e.deviceId)
        val since = connectedSince.remove(e.deviceId)
        val ep = Episode(deviceId = e.deviceId, startedAt = e.at)

        if (since != null && e.at - since < config.minConnectionMs) {
            return listOf(EpisodeChanged(ep.copy(state = SUPPRESSED), "Connection lasted only ${secs(e.at - since)}"))
        }
        val offAt = lastAdapterOffAt
        if (offAt != null && abs(e.at - offAt) <= config.adapterOffWindowMs) {
            return listOf(EpisodeChanged(ep.copy(state = SUPPRESSED), "Bluetooth was turned off"))
        }
        episodes[e.deviceId] = ep
        return listOf(EpisodeChanged(ep, "Disconnected"))
    }

    private fun onSeen(e: Seen): List<DetectorOutput> {
        val ep = episodes[e.deviceId] ?: return emptyList()
        if (e.rssi < config.seenCancelRssi || e.at < ep.startedAt) return emptyList()
        episodes.remove(e.deviceId)
        return when (ep.state) {
            PENDING -> listOf(EpisodeChanged(ep.copy(state = CANCELLED), "Seen nearby (${e.rssi} dBm)"))
            else -> listOf(ClearAlert(e.deviceId))
        }
    }

    private fun onFix(e: LocationFix): List<DetectorOutput> {
        val fix = e.fix
        if (fix.accuracyM > config.maxUsableAccuracyM) return emptyList()
        val out = mutableListOf<DetectorOutput>()

        for (ep in episodes.values.filter { it.state == PENDING }) {
            val anchor = ep.anchor
            if (anchor == null) {
                if (fix.timestamp < ep.startedAt - config.anchorMaxAgeMs) continue // too old to say where it was left
                val zone = SafeZoneEvaluator.zoneContaining(fix, zones)
                if (zone != null) {
                    episodes.remove(ep.deviceId)
                    out += EpisodeChanged(ep.copy(state = SUPPRESSED, anchor = fix), "Left inside safe zone \"${zone.name}\"")
                } else {
                    val anchored = ep.copy(anchor = fix)
                    episodes[ep.deviceId] = anchored
                    out += EpisodeChanged(anchored, "Anchored (±${fix.accuracyM.roundToInt()} m)")
                }
                continue
            }
            if (fix.timestamp <= anchor.timestamp) continue

            val sep = Geo.conservativeSeparationM(fix, anchor)
            val pastDebounce = e.at - ep.startedAt >= config.debounceMs
            val away = sep >= config.minDisplacementM && sep >= ep.lastSeparationM - config.awayToleranceM
            val streak = if (pastDebounce && away) ep.awayStreak + 1 else 0
            val updated = ep.copy(awayStreak = streak, lastSeparationM = sep, maxSeparationM = max(ep.maxSeparationM, sep))

            if (streak >= config.requiredAwayFixes) {
                val alerted = updated.copy(state = ALERTED)
                episodes[ep.deviceId] = alerted
                out += Alert(alerted)
                out += EpisodeChanged(alerted, "Phone moved ${sep.roundToInt()} m away")
            } else {
                episodes[ep.deviceId] = updated
            }
        }
        return out
    }

    private fun onAdapterOff(e: AdapterTurningOff): List<DetectorOutput> {
        lastAdapterOffAt = e.at
        connectedSince.clear()
        val hit = episodes.values.filter { it.state == PENDING && abs(it.startedAt - e.at) <= config.adapterOffWindowMs }
        return hit.map { ep ->
            episodes.remove(ep.deviceId)
            EpisodeChanged(ep.copy(state = SUPPRESSED), "Bluetooth was turned off")
        }
    }

    private fun onDismissed(e: Dismissed): List<DetectorOutput> {
        val ep = episodes.remove(e.deviceId) ?: return emptyList()
        return listOf(EpisodeChanged(ep.copy(state = DISMISSED), "You said it's with you"))
    }

    private fun onTick(e: Tick): List<DetectorOutput> {
        val expired = episodes.values.filter { it.state == PENDING && e.at - it.startedAt > config.activeWindowMs }
        return expired.map { ep ->
            episodes.remove(ep.deviceId)
            val reason = if (ep.anchor == null) {
                "No usable location fix"
            } else {
                "Phone didn't move ${config.minDisplacementM.roundToInt()} m away (max ${ep.maxSeparationM.roundToInt()} m)"
            }
            EpisodeChanged(ep.copy(state = EXPIRED), reason)
        }
    }

    private fun secs(ms: Long) = "${ms / 1000}s"
}
