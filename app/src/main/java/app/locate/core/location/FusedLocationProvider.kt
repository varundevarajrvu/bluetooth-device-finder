package app.locate.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import app.locate.core.permissions.PermissionChecker
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Every call is guarded by [PermissionChecker.hasFineLocation]. */
@SuppressLint("MissingPermission")
class FusedLocationProvider(
    context: Context,
    private val permissions: PermissionChecker,
) : LocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun lastKnown(): Fix? {
        if (!permissions.hasFineLocation()) return null
        return runCatching { client.lastLocation.await()?.toFix() }.getOrNull()
    }

    override suspend fun current(highAccuracy: Boolean): Fix? {
        if (!permissions.hasFineLocation()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority(highAccuracy))
            .setMaxUpdateAgeMillis(if (highAccuracy) 10_000 else 60_000)
            .setDurationMillis(CURRENT_TIMEOUT_MS)
            .build()
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(CURRENT_TIMEOUT_MS + 2_000) {
                runCatching { client.getCurrentLocation(request, cancel.token).await()?.toFix() }.getOrNull()
            }
        } finally {
            cancel.cancel()
        }
    }

    override fun updates(intervalMs: Long, highAccuracy: Boolean): Flow<Fix> {
        if (!permissions.hasFineLocation()) return emptyFlow()
        return callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { trySend(it.toFix()) }
                }
            }
            val request = LocationRequest.Builder(priority(highAccuracy), intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                close() // permission revoked mid-flight; the monitor just gets no fixes
            }
            awaitClose { client.removeLocationUpdates(callback) }
        }
    }

    private fun priority(high: Boolean) = if (high) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    private fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else UNKNOWN_ACCURACY_M, time)

    private companion object {
        const val CURRENT_TIMEOUT_MS = 20_000L
        const val UNKNOWN_ACCURACY_M = 9_999f
    }
}
