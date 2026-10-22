package app.locate.service

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.locate.core.ble.FakeBleSource
import app.locate.core.time.Clock
import app.locate.data.db.BtType
import app.locate.data.db.Device
import app.locate.data.db.DeviceCategory
import app.locate.data.db.LocateDatabase
import app.locate.data.db.SafeZone
import app.locate.data.db.SightingEvent
import app.locate.data.repo.AlertEpisodeRepository
import app.locate.data.repo.DeviceRepository
import app.locate.data.repo.LocalSightingRepository
import app.locate.data.repo.SafeZoneRepository
import app.locate.domain.alert.AlertConfig
import app.locate.domain.alert.EpisodeState
import app.locate.testing.FakeLocationProvider
import app.locate.testing.ORIGIN_LAT
import app.locate.testing.ORIGIN_LNG
import app.locate.testing.RecordingNotifier
import app.locate.testing.fixAt
import app.locate.testing.inMemoryDb
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * End-to-end through the real monitor, detector and Room, with fake Bluetooth and location
 * on virtual time. What a real phone does, minus the radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PresenceMonitorTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val t0 = 1_700_000_000_000L
    private val clock = Clock { t0 + dispatcher.scheduler.currentTime }

    private val db: LocateDatabase = inMemoryDb(dispatcher)
    private val ble = FakeBleSource(clock).apply { advertising.value = emptyMap() }
    private val location = FakeLocationProvider()
    private val notifier = RecordingNotifier()
    private val devices = DeviceRepository(db.deviceDao())
    private val sightings = LocalSightingRepository(db.sightingDao())
    private val zones = SafeZoneRepository(db.safeZoneDao())
    private val episodes = AlertEpisodeRepository(db.alertEpisodeDao(), clock)

    private val monitor = PresenceMonitor(
        ble, location, devices, sightings, zones, episodes, notifier, clock, AlertConfig(), MonitorConfig(),
    )

    @After
    fun tearDown() = db.close()

    private fun runMonitor(block: suspend TestScope.() -> Unit) = scope.runTest {
        monitor.start(backgroundScope)
        runCurrent()
        block()
    }

    private suspend fun addDevice(address: String, name: String): Long =
        devices.add(Device(address = address, nickname = name, category = DeviceCategory.EARBUDS, btType = BtType.CLASSIC, createdAt = t0))

    private fun TestScope.here(northM: Double) = location.moveTo(fixAt(northM = northM, time = clock.now()))

    /** Connected for 10 minutes at the origin, then disconnected. */
    private fun TestScope.connectThenDisconnect(address: String) {
        here(0.0)
        ble.simulateConnect(address)
        advanceTimeBy(10 * 60_000)
        here(0.0)
        ble.simulateDisconnect(address)
        advanceTimeBy(1_000)
    }

    /** Walk north at 1.5 m/s, one fix every 20 s. */
    private fun TestScope.walkAway(seconds: Int) {
        var walked = 0.0
        repeat(seconds / 20) {
            advanceTimeBy(20_000)
            walked += 30.0
            here(walked)
            runCurrent()
        }
    }

    @Test
    fun `walking away from a disconnected device raises one alert`() = runMonitor {
        val buds = addDevice(FakeBleSource.BUDS, "Buds")
        runCurrent()

        connectThenDisconnect(FakeBleSource.BUDS)
        assertThat(location.updatesSubscribed).isTrue()
        walkAway(seconds = 240)

        assertThat(notifier.alerts.map { it.first.id }).containsExactly(buds)
        val log = episodes.observeLog().first()
        assertThat(log.first().episode.state).isEqualTo(EpisodeState.ALERTED)
        // Location requests stop once the episode is resolved.
        assertThat(location.updatesSubscribed).isFalse()

        val events = sightings.observeRecent(buds, 10).first().map { it.event }
        assertThat(events).containsAtLeast(SightingEvent.CONNECTED, SightingEvent.DISCONNECTED)
    }

    @Test
    fun `no alert for a device left inside a safe zone`() = runMonitor {
        addDevice(FakeBleSource.BUDS, "Buds")
        zones.add(SafeZone(name = "Home", lat = ORIGIN_LAT, lng = ORIGIN_LNG, radiusM = 150.0))
        runCurrent()

        connectThenDisconnect(FakeBleSource.BUDS)
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        assertThat(episodes.observeLog().first().first().episode.reason).contains("safe zone")
    }

    @Test
    fun `reconnecting within the debounce cancels`() = runMonitor {
        addDevice(FakeBleSource.BUDS, "Buds")
        runCurrent()

        connectThenDisconnect(FakeBleSource.BUDS)
        walkAway(seconds = 40)
        ble.simulateConnect(FakeBleSource.BUDS)
        runCurrent()
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        assertThat(episodes.observeLog().first().first().episode.state).isEqualTo(EpisodeState.CANCELLED)
    }

    @Test
    fun `a device still advertising nearby cancels the pending episode`() = runMonitor {
        addDevice(FakeBleSource.SPEAKER, "Speaker")
        runCurrent()

        connectThenDisconnect(FakeBleSource.SPEAKER)
        ble.setAdvertising(FakeBleSource.SPEAKER, -60) // still right here, e.g. turned off audio but still powered
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        assertThat(episodes.observeLog().first().first().episode.reason).startsWith("Seen nearby")
    }

    @Test
    fun `alerts are not raised for devices with alerts turned off`() = runMonitor {
        val id = addDevice(FakeBleSource.BUDS, "Buds")
        devices.update(devices.get(id)!!.copy(alertsEnabled = false))
        runCurrent()

        connectThenDisconnect(FakeBleSource.BUDS)
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        // The disconnect is still recorded for the last-known location.
        assertThat(sightings.observeRecent(id, 10).first().map { it.event }).contains(SightingEvent.DISCONNECTED)
    }

    @Test
    fun `unregistered devices are ignored`() = runMonitor {
        addDevice(FakeBleSource.BUDS, "Buds")
        runCurrent()

        connectThenDisconnect(FakeBleSource.WATCH)
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        assertThat(episodes.observeLog().first()).isEmpty()
    }

    @Test
    fun `background scan records a seen sighting with the phone's location`() = runMonitor {
        val speaker = addDevice(FakeBleSource.SPEAKER, "Speaker")
        here(0.0)
        ble.setAdvertising(FakeBleSource.SPEAKER, -70)
        // The first periodic scan ran at start with no devices; the next one is 5 minutes later.
        advanceTimeBy(5 * 60_000 + 15_000)

        val seen = sightings.observeRecent(speaker, 10).first().filter { it.event == SightingEvent.SEEN }
        assertThat(seen).isNotEmpty()
        assertThat(seen.first().rssi).isEqualTo(-70)
        assertThat(seen.first().hasLocation).isTrue()
    }

    @Test
    fun `dismissing from the notification closes the episode`() = runMonitor {
        val buds = addDevice(FakeBleSource.BUDS, "Buds")
        runCurrent()
        connectThenDisconnect(FakeBleSource.BUDS)
        walkAway(seconds = 240)

        monitor.dismiss(buds)
        runCurrent()

        assertThat(notifier.cleared).contains(buds)
        assertThat(episodes.observeLog().first().first().episode.state).isEqualTo(EpisodeState.DISMISSED)
    }

    @Test
    fun `bluetooth switched off does not alert`() = runMonitor {
        addDevice(FakeBleSource.BUDS, "Buds")
        runCurrent()
        here(0.0)
        ble.simulateConnect(FakeBleSource.BUDS)
        advanceTimeBy(10 * 60_000)
        ble.simulateBluetoothOff()
        walkAway(seconds = 300)

        assertThat(notifier.alerts).isEmpty()
        assertThat(episodes.observeLog().first().first().episode.reason).isEqualTo("Bluetooth was turned off")
    }
}
