package app.locate.core.permissions

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionSpecTest {
    private fun ids(sdk: Int) = PermissionSpec.steps(sdk).map { it.id }
    private fun all(sdk: Int) = PermissionSpec.steps(sdk).flatMap { it.permissions }

    @Test
    fun `api 26 needs only location`() {
        assertThat(ids(26)).containsExactly(PermissionStepId.LOCATION)
        assertThat(all(26)).doesNotContain(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    @Test
    fun `api 29 adds background location as its own step`() {
        assertThat(ids(29)).containsExactly(PermissionStepId.LOCATION, PermissionStepId.BACKGROUND_LOCATION).inOrder()
    }

    @Test
    fun `api 31 adds the nearby-devices permissions first`() {
        assertThat(ids(31)).containsExactly(
            PermissionStepId.BLUETOOTH, PermissionStepId.LOCATION, PermissionStepId.BACKGROUND_LOCATION,
        ).inOrder()
        assertThat(all(31)).containsAtLeast(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun `api 33 and later add notifications last`() {
        for (sdk in listOf(33, 34, 37)) {
            assertThat(ids(sdk).last()).isEqualTo(PermissionStepId.NOTIFICATIONS)
            assertThat(ids(sdk)).hasSize(4)
        }
    }

    @Test
    fun `location step asks for fine and coarse together`() {
        val location = PermissionSpec.steps(34).single { it.id == PermissionStepId.LOCATION }
        assertThat(location.permissions).containsExactly(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Test
    fun `background location goes through settings from api 30`() {
        assertThat(PermissionSpec.backgroundLocationViaSettings(29)).isFalse()
        assertThat(PermissionSpec.backgroundLocationViaSettings(30)).isTrue()
    }
}
