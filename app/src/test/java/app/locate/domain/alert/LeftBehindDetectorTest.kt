package app.locate.domain.alert

import app.locate.core.location.Fix
import app.locate.domain.alert.DetectorInput.AdapterTurningOff
import app.locate.domain.alert.DetectorInput.Connected
import app.locate.domain.alert.DetectorInput.Disconnected
import app.locate.domain.alert.DetectorInput.Dismissed
import app.locate.domain.alert.DetectorInput.LocationFix
import app.locate.domain.alert.DetectorInput.Seen
import app.locate.domain.alert.DetectorInput.Tick
import app.locate.domain.alert.DetectorOutput.Alert
import app.locate.domain.alert.DetectorOutput.ClearAlert
import app.locate.domain.alert.DetectorOutput.EpisodeChanged
import app.locate.domain.zone.ZoneArea
import app.locate.testing.ORIGIN_LAT
import app.locate.testing.ORIGIN_LNG
import app.locate.testing.fixAt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LeftBehindDetectorTest {
    private val config = AlertConfig()
    private val detector = LeftBehindDetector(config)
    private val buds = 1L
    private val outputs = mutableListOf<DetectorOutput>()

    private val t0 = 1_000_000_000L
    private fun at(seconds: Int) = t0 + seconds * 1_000L

    private fun send(input: DetectorInput) = detector.handle(input).also { outputs += it }

    private fun fix(seconds: Int, northM: Double, accuracyM: Float = 10f) =
        send(LocationFix(fixAt(northM = northM, accuracyM = accuracyM, time = at(seconds)), at(seconds)))

    private val alerts get() = outputs.filterIsInstance<Alert>()
    private val lastChange get() = outputs.filterIsInstance<EpisodeChanged>().last()

    /** Connected long enough, then disconnected at t0, anchored at the origin. */
    private fun disconnectAtOrigin() {
        send(Connected(buds, at(-600)))
        send(Disconnected(buds, at(0)))
        fix(1, northM = 0.0)
    }

    /** Walks north at ~1.5 m/s with a fix every 20 s, from [fromS] to [toS]. */
    private fun walk(fromS: Int, toS: Int) {
        for (s in fromS..toS step 20) fix(s, northM = s * 1.5)
    }

    @Test
    fun `alerts when the phone walks away after the debounce`() {
        disconnectAtOrigin()
        walk(20, 200)

        assertThat(alerts).hasSize(1)
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.ALERTED)
        assertThat(lastChange.reason).startsWith("Phone moved")
        // 2 consecutive fixes >= 100 m accuracy-adjusted (raw >= 120 m) at 1.5 m/s: the alert fires at 100 s.
        assertThat(alerts.single().episode.lastSeparationM).isAtLeast(config.minDisplacementM)
    }

    @Test
    fun `no alert inside the debounce window even if far away`() {
        disconnectAtOrigin()
        // Driving: 500 m away at 20 s and 40 s, both inside the 60 s debounce.
        fix(20, northM = 500.0)
        fix(40, northM = 1_000.0)
        assertThat(alerts).isEmpty()
        // The first two fixes after the debounce fire it.
        fix(60, northM = 1_500.0)
        assertThat(alerts).isEmpty()
        fix(80, northM = 2_000.0)
        assertThat(alerts).hasSize(1)
    }

    @Test
    fun `reconnect within the debounce cancels`() {
        disconnectAtOrigin()
        fix(20, northM = 30.0)
        send(Connected(buds, at(30)))
        walk(40, 300)

        assertThat(alerts).isEmpty()
        assertThat(outputs.filterIsInstance<EpisodeChanged>().map { it.episode.state }).contains(EpisodeState.CANCELLED)
        assertThat(detector.needsActiveLocation).isFalse()
    }

    @Test
    fun `strong sighting cancels, weak sighting does not`() {
        disconnectAtOrigin()
        send(Seen(buds, rssi = -92, at = at(10)))
        assertThat(detector.needsActiveLocation).isTrue()

        send(Seen(buds, rssi = -65, at = at(15)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.CANCELLED)
        assertThat(lastChange.reason).contains("-65")
        walk(20, 300)
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `short connection is flapping, not leaving`() {
        send(Connected(buds, at(-10)))
        send(Disconnected(buds, at(0)))

        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.SUPPRESSED)
        assertThat(lastChange.reason).contains("only 10s")
        fix(1, northM = 0.0)
        walk(20, 300)
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `a seeded connection counts as established`() {
        send(Connected(buds, at(-5), seeded = true))
        send(Disconnected(buds, at(0)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.PENDING)
    }

    @Test
    fun `disconnect with unknown connection start is treated as established`() {
        // E.g. the process was started by the disconnect broadcast itself.
        send(Disconnected(buds, at(0)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.PENDING)
    }

    @Test
    fun `bluetooth turned off just before the disconnect suppresses`() {
        send(Connected(buds, at(-600)))
        send(AdapterTurningOff(at(-1)))
        send(Disconnected(buds, at(0)))
        assertThat(lastChange.reason).isEqualTo("Bluetooth was turned off")
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.SUPPRESSED)
    }

    @Test
    fun `bluetooth turned off just after the disconnect suppresses the pending episode`() {
        disconnectAtOrigin()
        send(AdapterTurningOff(at(2)))
        walk(20, 300)
        assertThat(alerts).isEmpty()
        assertThat(outputs.filterIsInstance<EpisodeChanged>().map { it.reason }).contains("Bluetooth was turned off")
    }

    @Test
    fun `bluetooth turned off long before is irrelevant`() {
        send(AdapterTurningOff(at(-900)))
        send(Connected(buds, at(-600)))
        send(Disconnected(buds, at(0)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.PENDING)
    }

    @Test
    fun `left inside a safe zone suppresses`() {
        detector.zones = listOf(ZoneArea(1, "Home", ORIGIN_LAT, ORIGIN_LNG, radiusM = 150.0))
        disconnectAtOrigin()
        walk(20, 600)
        assertThat(alerts).isEmpty()
        assertThat(lastChange.reason).isEqualTo("Left inside safe zone \"Home\"")
    }

    @Test
    fun `left outside a zone but walking into one still alerts`() {
        // Left at a cafe 500 m south of the office, then walked into the Work zone.
        val work = ZoneArea(1, "Work", ORIGIN_LAT + 500 / 111_320.0, ORIGIN_LNG, radiusM = 150.0)
        detector.zones = listOf(work)
        disconnectAtOrigin()
        fix(60, northM = 450.0)
        fix(80, northM = 500.0)
        assertThat(alerts).hasSize(1)
    }

    @Test
    fun `inaccurate fixes are ignored`() {
        send(Connected(buds, at(-600)))
        send(Disconnected(buds, at(0)))
        fix(1, northM = 0.0, accuracyM = 500f) // cell-tower fix: not an anchor
        assertThat(lastChange.reason).isEqualTo("Disconnected")
        fix(5, northM = 0.0)
        fix(60, northM = 400.0, accuracyM = 200f)
        fix(80, northM = 400.0, accuracyM = 200f)
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `a fix from long before the disconnect is not an anchor`() {
        send(Connected(buds, at(-600)))
        send(Disconnected(buds, at(0)))
        send(LocationFix(fixAt(northM = 0.0, time = at(-300)), at(1)))
        assertThat(lastChange.episode.anchor).isNull()
    }

    @Test
    fun `gps jitter within accuracy does not alert`() {
        disconnectAtOrigin()
        // Indoors: fixes wander 60-80 m with +-40 m accuracy. Raw distance looks like walking; adjusted it isn't.
        listOf(60 to 70.0, 80 to 80.0, 100 to 60.0, 120 to 75.0).forEach { (s, m) -> fix(s, northM = m, accuracyM = 40f) }
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `getting closer again resets the streak`() {
        disconnectAtOrigin()
        fix(60, northM = 150.0)
        fix(80, northM = 50.0) // walked back towards it
        fix(100, northM = 150.0)
        assertThat(alerts).isEmpty()
        fix(120, northM = 170.0)
        assertThat(alerts).hasSize(1)
    }

    @Test
    fun `expires when the phone never moves away`() {
        disconnectAtOrigin()
        fix(60, northM = 10.0)
        send(Tick(at(601)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.EXPIRED)
        assertThat(lastChange.reason).contains("didn't move")
        assertThat(detector.needsActiveLocation).isFalse()
    }

    @Test
    fun `expires with a clear reason when no fix ever arrives`() {
        send(Connected(buds, at(-600)))
        send(Disconnected(buds, at(0)))
        send(Tick(at(601)))
        assertThat(lastChange.reason).isEqualTo("No usable location fix")
    }

    @Test
    fun `alerts only once per episode`() {
        disconnectAtOrigin()
        walk(20, 1_000)
        assertThat(alerts).hasSize(1)
        assertThat(detector.needsActiveLocation).isFalse()
    }

    @Test
    fun `dismissing an alert closes the episode`() {
        disconnectAtOrigin()
        walk(20, 200)
        send(Dismissed(buds, at(210)))
        assertThat(lastChange.episode.state).isEqualTo(EpisodeState.DISMISSED)
    }

    @Test
    fun `reconnecting after an alert clears the notification`() {
        disconnectAtOrigin()
        walk(20, 200)
        val result = send(Connected(buds, at(300)))
        assertThat(result).containsExactly(ClearAlert(buds))
    }

    @Test
    fun `duplicate disconnect broadcasts do not restart the episode`() {
        disconnectAtOrigin()
        val result = send(Disconnected(buds, at(1)))
        assertThat(result).isEmpty()
        assertThat(lastChange.episode.anchor).isNotNull()
    }

    @Test
    fun `scan targets track pending episodes`() {
        assertThat(detector.scanTargets).isEmpty()
        disconnectAtOrigin()
        assertThat(detector.scanTargets).containsExactly(buds)
        send(Connected(buds, at(10)))
        assertThat(detector.scanTargets).isEmpty()
    }

    @Test
    fun `devices are tracked independently`() {
        val speaker = 2L
        send(Connected(buds, at(-600)))
        send(Connected(speaker, at(-600)))
        send(Disconnected(buds, at(0)))
        send(Disconnected(speaker, at(0)))
        fix(1, northM = 0.0)
        send(Connected(speaker, at(10))) // speaker came back, buds didn't
        walk(20, 200)
        assertThat(alerts.map { it.episode.deviceId }).containsExactly(buds)
    }

    @Test
    fun `restored pending episode carries on after a restart`() {
        val restored = Episode(deviceId = buds, startedAt = at(0), anchor = Fix(ORIGIN_LAT, ORIGIN_LNG, 10f, at(1)))
        detector.restore(restored)
        walk(60, 200)
        assertThat(alerts).hasSize(1)
    }

    @Test
    fun `restoring a resolved episode is a no-op`() {
        detector.restore(Episode(deviceId = buds, startedAt = at(0), state = EpisodeState.CANCELLED))
        assertThat(detector.needsActiveLocation).isFalse()
    }
}
