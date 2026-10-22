package app.locate.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.locate.domain.alert.EpisodeState

enum class DeviceCategory(val label: String) {
    EARBUDS("Earbuds"), HEADPHONES("Headphones"), SPEAKER("Speaker"), WATCH("Watch"), OTHER("Other")
}

/** How the device talks Bluetooth. Classic-only devices can't be seen by BLE scans. */
enum class BtType { CLASSIC, LE, DUAL, UNKNOWN }

enum class SightingEvent { SEEN, CONNECTED, DISCONNECTED }

/** LOCAL: this phone. REMOTE is reserved for a future crowdsourced source (see docs/ROADMAP.md). */
enum class SightingSource { LOCAL, REMOTE }

@Entity(tableName = "devices", indices = [Index(value = ["address"], unique = true)])
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identity address. Stable for bonded devices: Classic doesn't rotate, LE is resolved via the bond's IRK. */
    val address: String,
    val nickname: String,
    val category: DeviceCategory,
    val btType: BtType,
    /** Fingerprint captured at registration, for matching advertisements whose address differs (M3). */
    val advName: String? = null,
    val manufacturerId: Int? = null,
    val serviceUuids: String? = null,
    /** null = not probed yet. */
    val supportsImmediateAlert: Boolean? = null,
    val alertsEnabled: Boolean = true,
    val createdAt: Long,
)

@Entity(
    tableName = "sightings",
    foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["deviceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["deviceId", "timestamp"])],
)
data class Sighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val timestamp: Long,
    val event: SightingEvent,
    /** The phone's location, not the device's: Bluetooth devices have no GPS. Null if no fix was available. */
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    /** When the location fix was taken; can be earlier than [timestamp] if a cached fix was used. */
    val fixTimestamp: Long? = null,
    val rssi: Int? = null,
    val source: SightingSource = SightingSource.LOCAL,
) {
    val hasLocation: Boolean get() = lat != null && lng != null
}

@Entity(tableName = "safe_zones")
data class SafeZone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
)

@Entity(
    tableName = "alert_episodes",
    foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["deviceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["deviceId", "startedAt"], unique = true)],
)
data class AlertEpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val startedAt: Long,
    val state: EpisodeState,
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val anchorAccuracyM: Float? = null,
    val anchorTimestamp: Long? = null,
    val maxSeparationM: Double = 0.0,
    val reason: String,
    val updatedAt: Long,
)

data class EpisodeWithNickname(
    @Embedded val episode: AlertEpisodeEntity,
    @ColumnInfo(name = "nickname") val nickname: String,
)
