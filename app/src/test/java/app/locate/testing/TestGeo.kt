package app.locate.testing

import app.locate.core.location.Fix
import kotlin.math.cos

/** Bengaluru-ish origin; offsets are in metres. */
const val ORIGIN_LAT = 12.9716
const val ORIGIN_LNG = 77.5946

fun fixAt(northM: Double = 0.0, eastM: Double = 0.0, accuracyM: Float = 10f, time: Long): Fix = Fix(
    lat = ORIGIN_LAT + northM / 111_320.0,
    lng = ORIGIN_LNG + eastM / (111_320.0 * cos(Math.toRadians(ORIGIN_LAT))),
    accuracyM = accuracyM,
    timestamp = time,
)
