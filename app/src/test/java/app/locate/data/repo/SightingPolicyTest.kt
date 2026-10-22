package app.locate.data.repo

import app.locate.data.db.Sighting
import app.locate.data.db.SightingEvent
import app.locate.testing.fixAt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SightingPolicyTest {
    private val policy = SightingPolicy()
    private val t0 = 1_000_000L

    private fun stored(event: SightingEvent, time: Long, northM: Double? = 0.0): Sighting {
        val f = northM?.let { fixAt(northM = it, time = time) }
        return Sighting(id = 1, deviceId = 1, timestamp = time, event = event, lat = f?.lat, lng = f?.lng, accuracyM = f?.accuracyM)
    }

    private fun new(event: SightingEvent, time: Long, northM: Double? = 0.0) =
        NewSighting(1, time, event, northM?.let { fixAt(northM = it, time = time) })

    @Test
    fun `first sighting is always recorded`() {
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0), previous = null)).isTrue()
    }

    @Test
    fun `duplicate events within two seconds are dropped`() {
        assertThat(policy.shouldRecord(new(SightingEvent.DISCONNECTED, t0 + 1_500), stored(SightingEvent.DISCONNECTED, t0))).isFalse()
    }

    @Test
    fun `connect and disconnect are kept even when frequent`() {
        assertThat(policy.shouldRecord(new(SightingEvent.CONNECTED, t0 + 10_000), stored(SightingEvent.CONNECTED, t0))).isTrue()
    }

    @Test
    fun `seen is throttled to once per five minutes in one place`() {
        val prev = stored(SightingEvent.SEEN, t0)
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 60_000), prev)).isFalse()
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 5 * 60_000), prev)).isTrue()
    }

    @Test
    fun `seen is recorded early when the phone has moved`() {
        val prev = stored(SightingEvent.SEEN, t0)
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 60_000, northM = 80.0), prev)).isTrue()
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 60_000, northM = 30.0), prev)).isFalse()
    }

    @Test
    fun `seen without location is throttled`() {
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 60_000, northM = null), stored(SightingEvent.SEEN, t0))).isFalse()
    }

    @Test
    fun `first located seen after unlocated ones is kept`() {
        assertThat(policy.shouldRecord(new(SightingEvent.SEEN, t0 + 60_000), stored(SightingEvent.SEEN, t0, northM = null))).isTrue()
    }
}
