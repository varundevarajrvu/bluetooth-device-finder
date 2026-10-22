package app.locate.core.location

/** A phone location fix. [accuracyM] is the 68% confidence radius reported by the provider. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timestamp: Long,
)
