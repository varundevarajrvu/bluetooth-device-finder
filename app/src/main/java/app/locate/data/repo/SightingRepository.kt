package app.locate.data.repo

import app.locate.core.geo.Geo
import app.locate.core.location.Fix
import app.locate.data.db.Sighting
import app.locate.data.db.SightingDao
import app.locate.data.db.SightingEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

data class NewSighting(
    val deviceId: Long,
    val timestamp: Long,
    val event: SightingEvent,
    val fix: Fix?,
    val rssi: Int? = null,
)

/**
 * Where sightings go in and come out. The MVP has one implementation, on-device Room.
 * A future crowdsourced source would sit behind this same interface (see docs/ROADMAP.md).
 */
interface SightingRepository {
    /** Stores [sighting] unless the policy drops it as a duplicate or too frequent. Returns true if stored. */
    suspend fun record(sighting: NewSighting): Boolean

    fun observeLatestPerDevice(): Flow<List<Sighting>>
    fun observeRecent(deviceId: Long, limit: Int): Flow<List<Sighting>>
    fun observeLastLocated(deviceId: Long): Flow<Sighting?>

    /** Deletes sightings older than [before]; returns how many. */
    suspend fun prune(before: Long): Int
}

/** Keeps the sighting log useful without writing a row every scan. */
class SightingPolicy(
    private val dedupeWindowMs: Long = 2_000,
    private val seenMinIntervalMs: Long = 5 * 60_000,
    private val seenMinMoveM: Double = 50.0,
) {
    /** [previous] is the latest stored sighting of the same device and event type. */
    fun shouldRecord(new: NewSighting, previous: Sighting?): Boolean {
        if (previous == null) return true
        val dt = new.timestamp - previous.timestamp
        if (dt in 0 until dedupeWindowMs) return false
        // Connects and disconnects are rare and each one matters.
        if (new.event != SightingEvent.SEEN) return true
        if (dt >= seenMinIntervalMs) return true
        val fix = new.fix ?: return false
        val prevLat = previous.lat ?: return true // first located sighting is worth keeping
        val prevLng = previous.lng ?: return true
        return Geo.distanceM(fix.lat, fix.lng, prevLat, prevLng) >= seenMinMoveM
    }
}

class LocalSightingRepository @Inject constructor(
    private val dao: SightingDao,
) : SightingRepository {
    private val policy = SightingPolicy()

    override suspend fun record(sighting: NewSighting): Boolean {
        val previous = dao.latest(sighting.deviceId, sighting.event)
        if (!policy.shouldRecord(sighting, previous)) return false
        dao.insert(
            Sighting(
                deviceId = sighting.deviceId,
                timestamp = sighting.timestamp,
                event = sighting.event,
                lat = sighting.fix?.lat,
                lng = sighting.fix?.lng,
                accuracyM = sighting.fix?.accuracyM,
                fixTimestamp = sighting.fix?.timestamp,
                rssi = sighting.rssi,
            ),
        )
        return true
    }

    override fun observeLatestPerDevice() = dao.observeLatestPerDevice()
    override fun observeRecent(deviceId: Long, limit: Int) = dao.observeRecent(deviceId, limit)
    override fun observeLastLocated(deviceId: Long) = dao.observeLastLocated(deviceId)
    override suspend fun prune(before: Long) = dao.deleteOlderThan(before)
}
