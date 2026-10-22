package app.locate.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.locate.core.location.Fix
import app.locate.core.location.LocationProvider
import app.locate.data.db.Device
import app.locate.data.db.LocateDatabase
import app.locate.domain.alert.Episode
import app.locate.notify.AlertNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.CoroutineContext

class FakeLocationProvider : LocationProvider {
    var last: Fix? = null
    var fresh: Fix? = null
    private val stream = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    val updatesSubscribed: Boolean get() = stream.subscriptionCount.value > 0

    override suspend fun lastKnown() = last
    override suspend fun current(highAccuracy: Boolean) = fresh ?: last
    override fun updates(intervalMs: Long, highAccuracy: Boolean): Flow<Fix> = stream

    /** A new position: becomes the cached fix and is pushed to anyone receiving updates. */
    fun moveTo(fix: Fix) {
        last = fix
        stream.tryEmit(fix)
    }
}

class RecordingNotifier : AlertNotifier {
    val alerts = mutableListOf<Pair<Device, Episode>>()
    val cleared = mutableListOf<Long>()
    override fun showLeftBehind(device: Device, episode: Episode) {
        alerts += device to episode
    }
    override fun clearLeftBehind(deviceId: Long) {
        cleared += deviceId
    }
}

fun inMemoryDb(queryContext: CoroutineContext? = null): LocateDatabase {
    val builder = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LocateDatabase::class.java)
        .allowMainThreadQueries()
    if (queryContext != null) builder.setQueryCoroutineContext(queryContext)
    return builder.build()
}
