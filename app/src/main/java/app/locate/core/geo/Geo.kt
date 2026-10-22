package app.locate.core.geo

import app.locate.core.location.Fix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: Fix, b: Fix): Double = distanceM(a.lat, a.lng, b.lat, b.lng)

    /**
     * The smallest separation consistent with both fixes' accuracy circles: the raw distance
     * minus both radii, floored at zero. Using this for "has the phone moved away" means GPS
     * jitter alone can't trigger an alert.
     */
    fun conservativeSeparationM(a: Fix, b: Fix): Double =
        max(0.0, distanceM(a, b) - a.accuracyM - b.accuracyM)
}
