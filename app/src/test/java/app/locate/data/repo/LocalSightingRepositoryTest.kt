package app.locate.data.repo

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.locate.data.db.BtType
import app.locate.data.db.Device
import app.locate.data.db.DeviceCategory
import app.locate.data.db.LocateDatabase
import app.locate.data.db.SightingEvent
import app.locate.testing.fixAt
import app.locate.testing.inMemoryDb
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocalSightingRepositoryTest {
    private lateinit var db: LocateDatabase
    private lateinit var repo: LocalSightingRepository
    private var buds = 0L
    private var speaker = 0L
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDb()
        repo = LocalSightingRepository(db.sightingDao())
        buds = db.deviceDao().insert(device("AA:AA:AA:AA:AA:01", "Buds"))
        speaker = db.deviceDao().insert(device("AA:AA:AA:AA:AA:02", "Speaker"))
    }

    @After
    fun tearDown() = db.close()

    private fun device(address: String, name: String) =
        Device(address = address, nickname = name, category = DeviceCategory.EARBUDS, btType = BtType.CLASSIC, createdAt = t0)

    private suspend fun record(id: Long, event: SightingEvent, time: Long, northM: Double? = 0.0, rssi: Int? = null) =
        repo.record(NewSighting(id, time, event, northM?.let { fixAt(northM = it, time = time) }, rssi))

    @Test
    fun `stores location, accuracy and fix time`() = runBlocking {
        assertThat(record(buds, SightingEvent.DISCONNECTED, t0)).isTrue()
        val s = repo.observeRecent(buds, 10).first().single()
        assertThat(s.event).isEqualTo(SightingEvent.DISCONNECTED)
        assertThat(s.accuracyM).isEqualTo(10f)
        assertThat(s.fixTimestamp).isEqualTo(t0)
        assertThat(s.hasLocation).isTrue()
    }

    @Test
    fun `stores events without a location`() = runBlocking {
        assertThat(record(buds, SightingEvent.CONNECTED, t0, northM = null)).isTrue()
        assertThat(repo.observeRecent(buds, 10).first().single().hasLocation).isFalse()
    }

    @Test
    fun `drops duplicate broadcasts`() = runBlocking {
        record(buds, SightingEvent.DISCONNECTED, t0)
        assertThat(record(buds, SightingEvent.DISCONNECTED, t0 + 500)).isFalse()
        assertThat(repo.observeRecent(buds, 10).first()).hasSize(1)
    }

    @Test
    fun `throttles seen per device, not across devices`() = runBlocking {
        record(buds, SightingEvent.SEEN, t0, rssi = -60)
        assertThat(record(buds, SightingEvent.SEEN, t0 + 30_000, rssi = -61)).isFalse()
        assertThat(record(speaker, SightingEvent.SEEN, t0 + 30_000, rssi = -70)).isTrue()
    }

    @Test
    fun `seen throttling does not hide a disconnect`() = runBlocking {
        record(buds, SightingEvent.SEEN, t0)
        assertThat(record(buds, SightingEvent.DISCONNECTED, t0 + 10_000)).isTrue()
    }

    @Test
    fun `latest per device and last located`() = runBlocking {
        record(buds, SightingEvent.CONNECTED, t0)
        record(buds, SightingEvent.DISCONNECTED, t0 + 60_000, northM = null)
        record(speaker, SightingEvent.SEEN, t0 + 1_000)

        val latest = repo.observeLatestPerDevice().first().associateBy { it.deviceId }
        assertThat(latest[buds]!!.event).isEqualTo(SightingEvent.DISCONNECTED)
        assertThat(latest[speaker]!!.event).isEqualTo(SightingEvent.SEEN)
        // The disconnect had no fix, so the last *located* sighting is the connect.
        assertThat(repo.observeLastLocated(buds).first()!!.event).isEqualTo(SightingEvent.CONNECTED)
    }

    @Test
    fun `recent is newest first and limited`() = runBlocking {
        for (i in 0 until 5) record(buds, SightingEvent.CONNECTED, t0 + i * 10_000L)
        val recent = repo.observeRecent(buds, limit = 3).first()
        assertThat(recent.map { it.timestamp }).containsExactly(t0 + 40_000, t0 + 30_000, t0 + 20_000).inOrder()
    }

    @Test
    fun `prune deletes only old sightings`() = runBlocking {
        record(buds, SightingEvent.CONNECTED, t0)
        record(buds, SightingEvent.CONNECTED, t0 + 100_000)
        assertThat(repo.prune(before = t0 + 50_000)).isEqualTo(1)
        assertThat(repo.observeRecent(buds, 10).first()).hasSize(1)
    }

    @Test
    fun `deleting a device deletes its sightings`() = runBlocking {
        record(buds, SightingEvent.CONNECTED, t0)
        db.deviceDao().delete(buds)
        assertThat(repo.observeRecent(buds, 10).first()).isEmpty()
    }
}
