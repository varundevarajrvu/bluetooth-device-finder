package app.locate.ui.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locate.core.ble.BleSource
import app.locate.core.ble.FakeBleSource
import app.locate.data.db.Device
import app.locate.data.db.Sighting
import app.locate.data.repo.DeviceRepository
import app.locate.data.repo.SightingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val devices: DeviceRepository,
    sightings: SightingRepository,
    ble: BleSource,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState["id"])

    val device: StateFlow<Device?> = devices.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recent: StateFlow<List<Sighting>> =
        sightings.observeRecent(id, limit = 30).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastLocated: StateFlow<Sighting?> =
        sightings.observeLastLocated(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Non-null only in -Plocate.fakeBle=true builds: drives the simulate buttons. */
    val fake: FakeBleSource? = ble as? FakeBleSource

    fun rename(name: String) = update { it.copy(nickname = name.trim().ifEmpty { it.nickname }) }

    fun setAlertsEnabled(enabled: Boolean) = update { it.copy(alertsEnabled = enabled) }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            devices.delete(id)
            onDone()
        }
    }

    private fun update(change: (Device) -> Device) {
        viewModelScope.launch { devices.get(id)?.let { devices.update(change(it)) } }
    }
}
