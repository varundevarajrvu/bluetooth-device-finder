package app.locate.service

import android.util.Log
import app.locate.core.ble.BleEvent
import app.locate.core.ble.BleSource
import app.locate.core.ble.ScanSighting
import app.locate.core.location.Fix
import app.locate.core.location.LocationProvider
import app.locate.core.time.Clock
import app.locate.data.db.Device
import app.locate.data.db.SightingEvent
import app.locate.data.repo.AlertEpisodeRepository
import app.locate.data.repo.DeviceRepository
import app.locate.data.repo.NewSighting
import app.locate.data.repo.SafeZoneRepository
import app.locate.data.repo.SightingRepository
import app.locate.domain.alert.AlertConfig
import app.locate.domain.alert.DetectorInput
import app.locate.domain.alert.DetectorOutput
import app.locate.domain.alert.LeftBehindDetector
import app.locate.notify.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Timings of the monitor's own loops; the alert thresholds live in [AlertConfig]. */
data class MonitorConfig(
    /** Background "seen" scan: [scanDurationMs] of low-power scanning every [scanIntervalMs]. */
    val scanIntervalMs: Long = 5 * 60_000,
    val scanDurationMs: Long = 10_000,
    /** While an episode is pending, scan for that device this often, in case it is still nearby. */
    val pendingScanIntervalMs: Long = 30_000,
    val tickIntervalMs: Long = 15_000,
    /** A cached fix younger than this is good enough for connect/seen sightings. */
    val cachedFixMaxAgeMs: Long = 2 * 60_000,
    /** Manifest and runtime receivers both deliver ACL events; drop repeats this close together. */
    val connectionDedupeMs: Long = 2_000,
)

/**
 * Wires Bluetooth events, location and scans into [LeftBehindDetector], and records sightings.
 * Runs inside [MonitorService]'s scope. All detector access is serialised by [mutex].
 */
@Singleton
class PresenceMonitor @Inject constructor(
    private val ble: BleSource,
    private val location: LocationProvider,
    private val devices: DeviceRepository,
    private val sightings: SightingRepository,
    private val zones: SafeZoneRepository,
    private val episodes: AlertEpisodeRepository,
    private val notifier: AlertNotifier,
    private val clock: Clock,
    private val alertConfig: AlertConfig,
    private val config: MonitorConfig,
) {
    private val detector = LeftBehindDetector(alertConfig)
    private val mutex = Mutex()
    private val activeLocation = MutableStateFlow(false)
    private val scanTargets = MutableStateFlow<Set<Long>>(emptySet())
    private val lastConnectionEvent = mutableMapOf<Pair<String, Boolean>, Long>()

    @Volatile private var byAddress: Map<String, Device> = emptyMap()
    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        job = scope.launch {
            supervisorScope {
                byAddress = devices.observeAll().first().byAddress()
                launch { devices.observeAll().collect { byAddress = it.byAddress() } }
                launch { zones.observeAreas().collect { z -> mutex.withLock { detector.zones = z } } }
                restoreAndSeed()
                launch { ble.events.collect { onBleEvent(it, this@supervisorScope) } }
                launch { ticker() }
                launch { periodicScan() }
                launch {
                    activeLocation.collectLatest { on ->
                        if (on) {
                            location.updates(alertConfig.activeFixIntervalMs, highAccuracy = true)
                                .collect { feed(DetectorInput.LocationFix(it, clock.now())) }
                        }
                    }
                }
                launch { scanTargets.collectLatest { targets -> if (targets.isNotEmpty()) pendingScanLoop(targets) } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** "It's with me" from the alert notification. */
    suspend fun dismiss(deviceId: Long) {
        notifier.clearLeftBehind(deviceId)
        if (isRunning) feed(DetectorInput.Dismissed(deviceId, clock.now())) else episodes.dismissLatest(deviceId)
    }

    private suspend fun restoreAndSeed() {
        val now = clock.now()
        mutex.withLock {
            episodes.pendingSince(now - alertConfig.activeWindowMs).forEach(detector::restore)
            publishNeeds()
        }
        val connected = runCatching { ble.connectedAddresses() }.getOrDefault(emptySet())
        for (device in connected.mapNotNull { byAddress[it.uppercase()] }) {
            feed(DetectorInput.Connected(device.id, now, seeded = true))
            record(device, SightingEvent.CONNECTED, now, bestEffortFix(), rssi = null)
        }
    }

    private suspend fun onBleEvent(event: BleEvent, scope: CoroutineScope) {
        when (event) {
            is BleEvent.AdapterTurningOff -> feed(DetectorInput.AdapterTurningOff(event.at))
            is BleEvent.AdapterOn -> Unit
            is BleEvent.Connection -> {
                val key = event.address.uppercase() to event.connected
                val last = lastConnectionEvent[key]
                if (last != null && event.at - last in 0 until config.connectionDedupeMs) return
                lastConnectionEvent[key] = event.at
                val device = byAddress[event.address.uppercase()] ?: return
                Log.i(TAG, "${device.nickname} ${if (event.connected) "connected" else "disconnected"}")

                if (event.connected) {
                    feed(DetectorInput.Connected(device.id, event.at))
                    scope.launch { record(device, SightingEvent.CONNECTED, event.at, bestEffortFix(), rssi = null) }
                } else {
                    if (device.alertsEnabled) feed(DetectorInput.Disconnected(device.id, event.at))
                    scope.launch { onDisconnectLocation(device, event.at) }
                }
            }
        }
    }

    /** Anchor the episode as precisely as we can: the cached fix now, then a fresh high-accuracy one. */
    private suspend fun onDisconnectLocation(device: Device, at: Long) {
        val cached = location.lastKnown()
        if (cached != null) feed(DetectorInput.LocationFix(cached, clock.now()))
        val fresh = location.current(highAccuracy = true)
        if (fresh != null) feed(DetectorInput.LocationFix(fresh, clock.now()))
        record(device, SightingEvent.DISCONNECTED, at, fresh ?: cached, rssi = null)
    }

    private suspend fun ticker() {
        while (true) {
            delay(config.tickIntervalMs)
            feed(DetectorInput.Tick(clock.now()))
        }
    }

    private suspend fun periodicScan() {
        while (true) {
            val addresses = byAddress.values.map { it.address }.toSet()
            if (addresses.isNotEmpty() && ble.isEnabled()) scanOnce(lowLatency = false, addresses)
            delay(config.scanIntervalMs)
        }
    }

    private suspend fun pendingScanLoop(targets: Set<Long>) {
        while (true) {
            val addresses = byAddress.values.filter { it.id in targets }.map { it.address }.toSet()
            if (addresses.isNotEmpty() && ble.isEnabled()) scanOnce(lowLatency = true, addresses)
            delay((config.pendingScanIntervalMs - config.scanDurationMs).coerceAtLeast(1_000))
        }
    }

    /** Scans for [scanDurationMs]; feeds sightings to the detector live, records the strongest per device after. */
    private suspend fun scanOnce(lowLatency: Boolean, addresses: Set<String>) {
        val strongest = mutableMapOf<String, ScanSighting>()
        withTimeoutOrNull(config.scanDurationMs) {
            ble.scan(lowLatency, addresses).collect { s ->
                val key = s.address.uppercase()
                if (s.rssi > (strongest[key]?.rssi ?: Int.MIN_VALUE)) strongest[key] = s
                val device = byAddress[key] ?: return@collect
                if (device.id in scanTargets.value) feed(DetectorInput.Seen(device.id, s.rssi, clock.now()))
            }
        }
        if (strongest.isEmpty()) return
        val fix = bestEffortFix()
        for ((key, s) in strongest) {
            val device = byAddress[key] ?: continue
            record(device, SightingEvent.SEEN, s.timestamp, fix, s.rssi)
        }
    }

    private suspend fun feed(input: DetectorInput) = mutex.withLock {
        for (output in detector.handle(input)) {
            when (output) {
                is DetectorOutput.EpisodeChanged -> {
                    Log.i(TAG, "Episode ${output.episode.deviceId}: ${output.episode.state} - ${output.reason}")
                    episodes.save(output.episode, output.reason)
                }
                is DetectorOutput.Alert -> byId(output.episode.deviceId)?.let { notifier.showLeftBehind(it, output.episode) }
                is DetectorOutput.ClearAlert -> notifier.clearLeftBehind(output.deviceId)
            }
        }
        publishNeeds()
    }

    private fun publishNeeds() {
        activeLocation.value = detector.needsActiveLocation
        scanTargets.value = detector.scanTargets
    }

    /** A fresh-enough cached fix, else a balanced-power fix, else whatever stale fix there is. */
    private suspend fun bestEffortFix(): Fix? {
        val cached = location.lastKnown()
        if (cached != null && clock.now() - cached.timestamp <= config.cachedFixMaxAgeMs) return cached
        return location.current(highAccuracy = false) ?: cached
    }

    private suspend fun record(device: Device, event: SightingEvent, at: Long, fix: Fix?, rssi: Int?) {
        sightings.record(NewSighting(device.id, at, event, fix, rssi))
    }

    private fun byId(id: Long) = byAddress.values.firstOrNull { it.id == id }

    private fun List<Device>.byAddress() = associateBy { it.address.uppercase() }


    private companion object {
        const val TAG = "Locate"
    }
}
