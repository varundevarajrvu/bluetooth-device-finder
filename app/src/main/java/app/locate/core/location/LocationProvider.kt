package app.locate.core.location

import kotlinx.coroutines.flow.Flow

interface LocationProvider {
    /** The cached fix, if any. Free: does not turn on GPS. */
    suspend fun lastKnown(): Fix?

    /** A fresh fix, or null if none arrives in time or location is unavailable. */
    suspend fun current(highAccuracy: Boolean): Fix?

    /** Continuous updates while collected. */
    fun updates(intervalMs: Long, highAccuracy: Boolean): Flow<Fix>
}
