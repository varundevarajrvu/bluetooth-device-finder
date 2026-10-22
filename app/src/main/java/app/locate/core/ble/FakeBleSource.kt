package app.locate.core.ble

import app.locate.core.time.Clock
import app.locate.data.db.BtType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.random.Random

/**
 * Scriptable Bluetooth for unit tests and for running the UI on an emulator
 * (build with -Plocate.fakeBle=true). Device detail shows simulate buttons when this is active.
 */
class FakeBleSource(
    private val clock: Clock,
    private val rssiJitterDb: Int = 0,
) : BleSource {
    override val isFake = true

    var enabled = true

    val bonded = mutableListOf(
        BtDeviceInfo(BUDS, "Fake Buds", BtType.CLASSIC),
        BtDeviceInfo(SPEAKER, "Fake Speaker", BtType.DUAL),
        BtDeviceInfo(WATCH, "Fake Watch", BtType.LE),
    )

    /** Address -> RSSI of devices currently advertising. Includes one unpaired stranger. */
    val advertising = MutableStateFlow(mapOf(SPEAKER to -62, WATCH to -74, STRANGER to -70))

    private val connected = mutableSetOf<String>()
    private val channel = Channel<BleEvent>(Channel.UNLIMITED)
    override val events: Flow<BleEvent> = channel.receiveAsFlow()

    override fun isSupported() = true
    override fun isEnabled() = enabled
    override fun bondedDevices() = bonded.toList()
    override suspend fun connectedAddresses() = connected.toSet()

    override fun scan(lowLatency: Boolean, addresses: Set<String>?): Flow<ScanSighting> = flow {
        while (true) {
            if (enabled) {
                advertising.value.filterKeys { addresses == null || it in addresses }.forEach { (address, rssi) ->
                    val jitter = if (rssiJitterDb > 0) Random.nextInt(-rssiJitterDb, rssiJitterDb + 1) else 0
                    emit(
                        ScanSighting(
                            address = address,
                            name = bonded.firstOrNull { it.address == address }?.name ?: "Someone's Buds",
                            rssi = rssi + jitter,
                            timestamp = clock.now(),
                            bonded = bonded.any { it.address == address },
                        ),
                    )
                }
            }
            delay(if (lowLatency) 300 else 1_000)
        }
    }

    fun simulateConnect(address: String) {
        connected += address
        channel.trySend(BleEvent.Connection(address, connected = true, at = clock.now()))
    }

    fun simulateDisconnect(address: String) {
        connected -= address
        channel.trySend(BleEvent.Connection(address, connected = false, at = clock.now()))
    }

    fun setAdvertising(address: String, rssi: Int?) {
        advertising.value = if (rssi == null) advertising.value - address else advertising.value + (address to rssi)
    }

    fun simulateBluetoothOff() {
        enabled = false
        channel.trySend(BleEvent.AdapterTurningOff(clock.now()))
        connected.toList().forEach(::simulateDisconnect)
    }

    fun simulateBluetoothOn() {
        enabled = true
        channel.trySend(BleEvent.AdapterOn(clock.now()))
    }

    companion object {
        const val BUDS = "00:11:22:33:44:01"
        const val SPEAKER = "00:11:22:33:44:02"
        const val WATCH = "00:11:22:33:44:03"
        const val STRANGER = "00:11:22:33:44:99"
    }
}
