package app.locate.core.ble

import kotlinx.coroutines.flow.Flow

/**
 * Everything Locate needs from Bluetooth, behind an interface: emulators have no Bluetooth,
 * so UI work and tests run against [FakeBleSource].
 */
interface BleSource {
    val isFake: Boolean

    fun isSupported(): Boolean
    fun isEnabled(): Boolean

    /** Devices paired with this phone. Empty without the Bluetooth permission. */
    fun bondedDevices(): List<BtDeviceInfo>

    /** Addresses currently connected (audio profiles + LE GATT), used to seed state at start-up. */
    suspend fun connectedAddresses(): Set<String>

    /**
     * Scans while collected. [addresses] limits results to those devices, which Android requires
     * for scans to keep running with the screen off; null means unfiltered (foreground only).
     */
    fun scan(lowLatency: Boolean, addresses: Set<String>?): Flow<ScanSighting>

    /** Connect/disconnect and adapter events. Single consumer. */
    val events: Flow<BleEvent>
}
