package app.locate.data.repo

import app.locate.core.location.Fix
import app.locate.core.time.Clock
import app.locate.data.db.AlertEpisodeDao
import app.locate.data.db.AlertEpisodeEntity
import app.locate.data.db.Device
import app.locate.data.db.DeviceDao
import app.locate.data.db.EpisodeWithNickname
import app.locate.data.db.SafeZone
import app.locate.data.db.SafeZoneDao
import app.locate.domain.alert.Episode
import app.locate.domain.alert.EpisodeState
import app.locate.domain.zone.ZoneArea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(private val dao: DeviceDao) {
    fun observeAll(): Flow<List<Device>> = dao.observeAll()
    fun observe(id: Long): Flow<Device?> = dao.observe(id)
    suspend fun get(id: Long) = dao.get(id)
    suspend fun getByAddress(address: String) = dao.getByAddress(address)
    suspend fun add(device: Device): Long = dao.insert(device)
    suspend fun update(device: Device) = dao.update(device)
    suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class SafeZoneRepository @Inject constructor(private val dao: SafeZoneDao) {
    fun observeAll(): Flow<List<SafeZone>> = dao.observeAll()
    fun observeAreas(): Flow<List<ZoneArea>> =
        dao.observeAll().map { zones -> zones.map { ZoneArea(it.id, it.name, it.lat, it.lng, it.radiusM) } }

    suspend fun add(zone: SafeZone) = dao.insert(zone)
    suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class AlertEpisodeRepository @Inject constructor(
    private val dao: AlertEpisodeDao,
    private val clock: Clock,
) {
    suspend fun save(episode: Episode, reason: String) = dao.save(
        AlertEpisodeEntity(
            deviceId = episode.deviceId,
            startedAt = episode.startedAt,
            state = episode.state,
            anchorLat = episode.anchor?.lat,
            anchorLng = episode.anchor?.lng,
            anchorAccuracyM = episode.anchor?.accuracyM,
            anchorTimestamp = episode.anchor?.timestamp,
            maxSeparationM = episode.maxSeparationM,
            reason = reason,
            updatedAt = clock.now(),
        ),
    )

    /** Episodes still pending since [since], to hand back to the detector after a restart. */
    suspend fun pendingSince(since: Long): List<Episode> =
        dao.inState(EpisodeState.PENDING, since).map { it.toEpisode() }

    /** Marks the device's latest episode dismissed, for when the detector isn't running. */
    suspend fun dismissLatest(deviceId: Long) {
        val latest = dao.latestFor(deviceId) ?: return
        if (latest.state == EpisodeState.PENDING || latest.state == EpisodeState.ALERTED) {
            dao.update(latest.copy(state = EpisodeState.DISMISSED, reason = "You said it's with you", updatedAt = clock.now()))
        }
    }

    fun observeLog(limit: Int = 100): Flow<List<EpisodeWithNickname>> = dao.observeLog(limit)

    suspend fun prune(before: Long) = dao.deleteOlderThan(before)

    private fun AlertEpisodeEntity.toEpisode(): Episode {
        val anchor = if (anchorLat != null && anchorLng != null && anchorAccuracyM != null && anchorTimestamp != null) {
            Fix(anchorLat, anchorLng, anchorAccuracyM, anchorTimestamp)
        } else {
            null
        }
        return Episode(deviceId = deviceId, startedAt = startedAt, state = state, anchor = anchor, maxSeparationM = maxSeparationM)
    }
}
