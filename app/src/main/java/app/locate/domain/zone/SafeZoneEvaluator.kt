package app.locate.domain.zone

import app.locate.core.geo.Geo
import app.locate.core.location.Fix

data class ZoneArea(val id: Long, val name: String, val lat: Double, val lng: Double, val radiusM: Double)

object SafeZoneEvaluator {
    /**
     * Returns the zone that [fix] might be inside, or null. Deliberately conservative: if the
     * fix's accuracy circle reaches into a zone, we treat it as inside, because the cost of a
     * wrong "inside" is a missed alert at home, while a wrong "outside" is a false alarm.
     */
    fun zoneContaining(fix: Fix, zones: List<ZoneArea>): ZoneArea? =
        zones.firstOrNull { zone ->
            Geo.distanceM(fix.lat, fix.lng, zone.lat, zone.lng) - fix.accuracyM <= zone.radiusM
        }
}
