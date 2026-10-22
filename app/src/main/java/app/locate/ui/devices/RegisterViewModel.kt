package app.locate.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locate.core.ble.BleSource
import app.locate.core.ble.BtDeviceInfo
import app.locate.core.ble.ScanSighting
import app.locate.core.permissions.PermissionChecker
import app.locate.core.prefs.AppPrefs
import app.locate.core.time.Clock
import app.locate.data.db.Device
import app.locate.data.db.DeviceCategory
import app.locate.data.repo.DeviceRepository
import app.locate.service.ServiceStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Candidate(val info: BtDeviceInfo, val live: ScanSighting?)

data class RegisterState(
    val hasPermission: Boolean,
    val bluetoothOn: Boolean,
    /** Paired and not yet added: the only devices that can be registered. */
    val candidates: List<Candidate>,
    /** Advertising nearby but not paired; listed only so the user knows why they can't be added. */
    val unpairedNearby: List<ScanSighting>,
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val ble: BleSource,
    private val devices: DeviceRepository,
    private val permissions: PermissionChecker,
    private val prefs: AppPrefs,
    private val serviceStarter: ServiceStarter,
    private val clock: Clock,
) : ViewModel() {

    private val bonded = MutableStateFlow(ble.bondedDevices())

    /** Unfiltered low-latency scan, only while this screen is visible. */
    private val live = (if (permissions.hasBluetoothPermissions()) ble.scan(lowLatency = true, addresses = null) else emptyFlow())
        .runningFold(emptyMap<String, ScanSighting>()) { acc, s -> acc + (s.address.uppercase() to s) }

    val state: StateFlow<RegisterState> = combine(bonded, devices.observeAll(), live) { bondedList, registered, seen ->
        val taken = registered.map { it.address.uppercase() }.toSet()
        val bondedAddresses = bondedList.map { it.address.uppercase() }.toSet()
        RegisterState(
            hasPermission = permissions.hasBluetoothPermissions(),
            bluetoothOn = ble.isEnabled(),
            candidates = bondedList.filter { it.address.uppercase() !in taken }.map { Candidate(it, seen[it.address.uppercase()]) },
            unpairedNearby = seen.values.filter { !it.bonded && it.address.uppercase() !in bondedAddresses && it.name != null }
                .sortedByDescending { it.rssi },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegisterState(permissions.hasBluetoothPermissions(), ble.isEnabled(), emptyList(), emptyList()))

    fun refreshBonded() {
        bonded.value = ble.bondedDevices()
    }

    fun register(candidate: Candidate, nickname: String, category: DeviceCategory, onDone: () -> Unit) {
        viewModelScope.launch {
            val live = candidate.live
            devices.add(
                Device(
                    address = candidate.info.address,
                    nickname = nickname.trim().ifEmpty { candidate.info.name ?: "My device" },
                    category = category,
                    btType = candidate.info.type,
                    advName = live?.name,
                    manufacturerId = live?.manufacturerIds?.firstOrNull(),
                    serviceUuids = live?.serviceUuids?.takeIf { it.isNotEmpty() }?.joinToString(","),
                    createdAt = clock.now(),
                ),
            )
            // Adding a device is a clear signal the user wants monitoring on.
            prefs.monitoringEnabled = true
            serviceStarter.ensureRunning()
            onDone()
        }
    }
}
