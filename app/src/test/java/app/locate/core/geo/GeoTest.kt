package app.locate.core.geo

import app.locate.testing.fixAt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {
    @Test
    fun `distance matches a known pair of points`() {
        // Bengaluru MG Road to Cubbon Park metro, about 1.1 km.
        val d = Geo.distanceM(12.9756, 77.6066, 12.9810, 77.5970)
        assertThat(d).isWithin(50.0).of(1_200.0)
    }

    @Test
    fun `distance between the same point is zero`() {
        assertThat(Geo.distanceM(12.0, 77.0, 12.0, 77.0)).isEqualTo(0.0)
    }

    @Test
    fun `test offsets produce the expected distance`() {
        assertThat(Geo.distanceM(fixAt(time = 0), fixAt(northM = 300.0, time = 0))).isWithin(1.0).of(300.0)
        assertThat(Geo.distanceM(fixAt(time = 0), fixAt(eastM = 300.0, time = 0))).isWithin(1.0).of(300.0)
    }

    @Test
    fun `conservative separation subtracts both accuracy radii`() {
        val a = fixAt(accuracyM = 20f, time = 0)
        val b = fixAt(northM = 200.0, accuracyM = 30f, time = 0)
        assertThat(Geo.conservativeSeparationM(a, b)).isWithin(1.0).of(150.0)
    }

    @Test
    fun `conservative separation never goes negative`() {
        val a = fixAt(accuracyM = 50f, time = 0)
        val b = fixAt(northM = 20.0, accuracyM = 50f, time = 0)
        assertThat(Geo.conservativeSeparationM(a, b)).isEqualTo(0.0)
    }
}
