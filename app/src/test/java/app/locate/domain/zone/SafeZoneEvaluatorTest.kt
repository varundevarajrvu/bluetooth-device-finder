package app.locate.domain.zone

import app.locate.testing.ORIGIN_LAT
import app.locate.testing.ORIGIN_LNG
import app.locate.testing.fixAt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafeZoneEvaluatorTest {
    private val home = ZoneArea(1, "Home", ORIGIN_LAT, ORIGIN_LNG, radiusM = 150.0)
    private val zones = listOf(home)

    @Test
    fun `fix at the centre is inside`() {
        assertThat(SafeZoneEvaluator.zoneContaining(fixAt(time = 0), zones)).isEqualTo(home)
    }

    @Test
    fun `fix well outside is outside`() {
        assertThat(SafeZoneEvaluator.zoneContaining(fixAt(northM = 400.0, accuracyM = 20f, time = 0), zones)).isNull()
    }

    @Test
    fun `fix whose accuracy circle reaches the zone counts as inside`() {
        // 190 m from centre, 150 m radius: outside by 40 m, but ±50 m accuracy reaches in.
        assertThat(SafeZoneEvaluator.zoneContaining(fixAt(northM = 190.0, accuracyM = 50f, time = 0), zones)).isEqualTo(home)
    }

    @Test
    fun `just beyond the accuracy-adjusted edge is outside`() {
        assertThat(SafeZoneEvaluator.zoneContaining(fixAt(northM = 210.0, accuracyM = 50f, time = 0), zones)).isNull()
    }

    @Test
    fun `no zones means never inside`() {
        assertThat(SafeZoneEvaluator.zoneContaining(fixAt(time = 0), emptyList())).isNull()
    }

    @Test
    fun `returns the matching zone among several`() {
        val work = ZoneArea(2, "Work", ORIGIN_LAT + 0.05, ORIGIN_LNG, radiusM = 200.0)
        val atWork = fixAt(northM = 0.05 * 111_320.0, time = 0)
        assertThat(SafeZoneEvaluator.zoneContaining(atWork, listOf(home, work))).isEqualTo(work)
    }
}
