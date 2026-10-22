package app.locate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locate.core.location.Fix
import app.locate.core.location.LocationProvider
import app.locate.data.db.SafeZone
import app.locate.data.repo.SafeZoneRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SafeZonesViewModel @Inject constructor(
    private val repo: SafeZoneRepository,
    private val location: LocationProvider,
) : ViewModel() {
    val zones: StateFlow<List<SafeZone>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    fun currentLocation(onResult: (Fix?) -> Unit) {
        viewModelScope.launch {
            _locating.value = true
            onResult(location.current(highAccuracy = true) ?: location.lastKnown())
            _locating.value = false
        }
    }

    fun add(name: String, lat: Double, lng: Double, radiusM: Double) {
        viewModelScope.launch { repo.add(SafeZone(name = name.trim(), lat = lat, lng = lng, radiusM = radiusM)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    companion object {
        const val MIN_RADIUS_M = 100f
        const val MAX_RADIUS_M = 1_000f
    }
}
