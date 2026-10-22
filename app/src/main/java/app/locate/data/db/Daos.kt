package app.locate.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.locate.domain.alert.EpisodeState
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createdAt")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE id = :id")
    fun observe(id: Long): Flow<Device?>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun get(id: Long): Device?

    @Query("SELECT * FROM devices WHERE address = :address")
    suspend fun getByAddress(address: String): Device?

    @Insert
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sighting: Sighting): Long

    @Query("SELECT * FROM sightings WHERE deviceId = :deviceId AND event = :event ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(deviceId: Long, event: SightingEvent): Sighting?

    @Query("SELECT * FROM sightings WHERE id IN (SELECT MAX(id) FROM sightings GROUP BY deviceId)")
    fun observeLatestPerDevice(): Flow<List<Sighting>>

    @Query("SELECT * FROM sightings WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(deviceId: Long, limit: Int): Flow<List<Sighting>>

    @Query("SELECT * FROM sightings WHERE deviceId = :deviceId AND lat IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    fun observeLastLocated(deviceId: Long): Flow<Sighting?>

    @Query("DELETE FROM sightings WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface SafeZoneDao {
    @Query("SELECT * FROM safe_zones ORDER BY name")
    fun observeAll(): Flow<List<SafeZone>>

    @Insert
    suspend fun insert(zone: SafeZone): Long

    @Query("DELETE FROM safe_zones WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
abstract class AlertEpisodeDao {
    @Query("SELECT * FROM alert_episodes WHERE deviceId = :deviceId AND startedAt = :startedAt")
    abstract suspend fun find(deviceId: Long, startedAt: Long): AlertEpisodeEntity?

    @Insert
    abstract suspend fun insert(entity: AlertEpisodeEntity): Long

    @Update
    abstract suspend fun update(entity: AlertEpisodeEntity)

    /** Insert or update, keyed by (deviceId, startedAt). */
    @Transaction
    open suspend fun save(entity: AlertEpisodeEntity) {
        val existing = find(entity.deviceId, entity.startedAt)
        if (existing == null) insert(entity) else update(entity.copy(id = existing.id))
    }

    @Query("SELECT * FROM alert_episodes WHERE state = :state AND startedAt >= :since")
    abstract suspend fun inState(state: EpisodeState, since: Long): List<AlertEpisodeEntity>

    @Query("SELECT * FROM alert_episodes WHERE deviceId = :deviceId ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun latestFor(deviceId: Long): AlertEpisodeEntity?

    @Query(
        "SELECT e.*, d.nickname AS nickname FROM alert_episodes e JOIN devices d ON d.id = e.deviceId " +
            "ORDER BY e.startedAt DESC LIMIT :limit",
    )
    abstract fun observeLog(limit: Int): Flow<List<EpisodeWithNickname>>

    @Query("DELETE FROM alert_episodes WHERE startedAt < :before")
    abstract suspend fun deleteOlderThan(before: Long): Int
}
