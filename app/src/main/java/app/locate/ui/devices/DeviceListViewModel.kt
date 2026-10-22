package app.locate.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locate.core.permissions.Capabilities
import app.locate.core.permissions.CapabilityIssue
import app.locate.core.permissions.PermissionChecker
import app.locate.core.prefs.AppPrefs
import app.locate.data.db.Device
import app.locate.data.db.Sighting
import app.locate.data.repo.DeviceRepository
import app.locate.data.repo.SightingRepository
import app.locate.service.ServiceStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DeviceRow(val device: Device, val latest: Sighting?)

@HiltViewModel
class DeviceListViewModel @Inject constructor(
    devices: DeviceRepository,
    sightings: SightingRepository,
    private val permissions: PermissionChecker,
    private val prefs: AppPrefs,
    private val serviceStarter: ServiceStarter,
) : ViewModel() {

    val rows: StateFlow<List<DeviceRow>?> = combine(devices.observeAll(), sightings.observeLatestPerDevice()) { list, latest ->
        val byDevice = latest.associateBy { it.deviceId }
        list.map { DeviceRow(it, byDevice[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _issues = MutableStateFlow<List<CapabilityIssue>>(emptyList())
    val issues: StateFlow<List<CapabilityIssue>> = _issues.asStateFlow()

    private val _monitoring = MutableStateFlow(prefs.monitoringEnabled)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()

    /** Permissions can change in Settings at any time, so the screen re-checks on every resume. */
    fun refresh() {
        _issues.value = Capabilities.issues(permissions.snapshot())
        _monitoring.value = prefs.monitoringEnabled
    }

    fun setMonitoring(enabled: Boolean) {
        prefs.monitoringEnabled = enabled
        _monitoring.value = enabled
        if (enabled) serviceStarter.ensureRunning() else serviceStarter.stop()
    }
}
