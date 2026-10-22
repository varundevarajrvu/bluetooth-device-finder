package app.locate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Device::class, Sighting::class, SafeZone::class, AlertEpisodeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LocateDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun sightingDao(): SightingDao
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun alertEpisodeDao(): AlertEpisodeDao
}
