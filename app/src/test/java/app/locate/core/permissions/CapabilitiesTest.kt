package app.locate.core.permissions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilitiesTest {
    private fun snapshot(
        sdk: Int = 34,
        granted: Set<PermissionStepId> = PermissionStepId.entries.toSet(),
        precise: Boolean = true,
        battery: Boolean = true,
        btOn: Boolean = true,
    ) = PermissionSnapshot(sdk, granted, precise, battery, btOn)

    private fun issueIds(s: PermissionSnapshot) = Capabilities.issues(s).map { it.id }

    @Test
    fun `everything granted means no issues`() {
        assertThat(Capabilities.issues(snapshot())).isEmpty()
        assertThat(Capabilities.alertsPossible(snapshot())).isTrue()
    }

    @Test
    fun `missing notifications blocks alerts`() {
        val s = snapshot(granted = PermissionStepId.entries.toSet() - PermissionStepId.NOTIFICATIONS)
        assertThat(issueIds(s)).containsExactly(IssueId.NOTIFICATIONS)
        assertThat(Capabilities.alertsPossible(s)).isFalse()
    }

    @Test
    fun `approximate location blocks alerts`() {
        val s = snapshot(precise = false)
        assertThat(issueIds(s)).containsExactly(IssueId.PRECISE_LOCATION)
        assertThat(Capabilities.alertsPossible(s)).isFalse()
    }

    @Test
    fun `missing background location and battery exemption are warnings, not blockers`() {
        val s = snapshot(granted = PermissionStepId.entries.toSet() - PermissionStepId.BACKGROUND_LOCATION, battery = false)
        assertThat(issueIds(s)).containsExactly(IssueId.BACKGROUND_LOCATION, IssueId.BATTERY_OPTIMIZATION)
        assertThat(Capabilities.issues(s).map { it.severity }.toSet()).containsExactly(Severity.WARNING)
        assertThat(Capabilities.alertsPossible(s)).isTrue()
    }

    @Test
    fun `permissions that do not exist on an api level are never reported missing`() {
        // API 28: no Bluetooth runtime permissions, no background location, no notification permission.
        val s = snapshot(sdk = 28, granted = setOf(PermissionStepId.LOCATION))
        assertThat(Capabilities.issues(s)).isEmpty()
    }

    @Test
    fun `nothing granted on api 34 reports every blocker`() {
        val s = snapshot(granted = emptySet(), precise = false)
        assertThat(issueIds(s)).containsAtLeast(IssueId.BLUETOOTH_PERMISSION, IssueId.LOCATION_PERMISSION, IssueId.NOTIFICATIONS)
        // Precise is only reported once location itself is granted.
        assertThat(issueIds(s)).doesNotContain(IssueId.PRECISE_LOCATION)
    }

    @Test
    fun `bluetooth off is a warning`() {
        assertThat(Capabilities.issues(snapshot(btOn = false)).single().severity).isEqualTo(Severity.WARNING)
    }
}
