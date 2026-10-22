package app.locate.core.ble

import app.locate.data.db.BtType

/** A device paired with this phone. */
data class BtDeviceInfo(val address: String, val name: String?, val type: BtType)

/** One BLE advertisement received during a scan. */
data class ScanSighting(
    val address: String,
    val name: String?,
    val rssi: Int,
    val timestamp: Long,
    val bonded: Boolean,
    val manufacturerIds: List<Int> = emptyList(),
    val serviceUuids: List<String> = emptyList(),
)

sealed interface BleEvent {
    val at: Long

    /** An ACL link (Classic or LE) to [address] came up or went down. */
    data class Connection(val address: String, val connected: Boolean, override val at: Long) : BleEvent
    data class AdapterTurningOff(override val at: Long) : BleEvent
    data class AdapterOn(override val at: Long) : BleEvent
}
