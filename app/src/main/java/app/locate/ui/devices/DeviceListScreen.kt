package app.locate.ui.devices

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locate.core.permissions.CapabilityIssue
import app.locate.core.permissions.IssueId
import app.locate.core.permissions.Severity
import app.locate.data.db.Sighting
import app.locate.data.db.SightingEvent
import app.locate.ui.common.relativeTime
import app.locate.ui.common.safeStart
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onAddDevice: () -> Unit,
    onOpenDevice: (Long) -> Unit,
    onOpenZones: () -> Unit,
    onOpenAlertLog: () -> Unit,
    onFixPermissions: () -> Unit,
    vm: DeviceListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val monitoring by vm.monitoring.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locate") },
                actions = {
                    TextButton(onClick = onOpenZones) { Text("Safe zones") }
                    TextButton(onClick = onOpenAlertLog) { Text("Log") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddDevice, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add device") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(issues, key = { it.id }) { issue ->
                IssueCard(issue) {
                    if (issue.id == IssueId.BLUETOOTH_OFF) context.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) else onFixPermissions()
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Watch my devices", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (monitoring) "Alerts are on" else "Alerts are off",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = monitoring, onCheckedChange = vm::setMonitoring)
                }
            }
            val list = rows
            if (list != null && list.isEmpty()) {
                item {
                    Text(
                        "No devices yet. Pair your earbuds, speaker or watch in Bluetooth settings, then tap Add device.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(list.orEmpty(), key = { it.device.id }) { row ->
                Card(Modifier.fillMaxWidth().clickable { onOpenDevice(row.device.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.device.nickname, style = MaterialTheme.typography.titleMedium)
                        Text(statusLine(row.latest), style = MaterialTheme.typography.bodyMedium)
                        if (!row.device.alertsEnabled) Text("Alerts off for this device", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: CapabilityIssue, onFix: () -> Unit) {
    val colors = if (issue.severity == Severity.BLOCKING) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Text(issue.title, style = MaterialTheme.typography.titleSmall)
            Text(issue.detail, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onFix) { Text("Fix") }
        }
    }
}

internal fun statusLine(s: Sighting?): String {
    if (s == null) return "No activity yet"
    val what = when (s.event) {
        SightingEvent.CONNECTED -> "Connected"
        SightingEvent.DISCONNECTED -> "Disconnected"
        SightingEvent.SEEN -> "Seen nearby" + (s.rssi?.let { " ($it dBm)" } ?: "")
    }
    val where = s.accuracyM?.let { " · phone location ±${it.roundToInt()} m" } ?: " · no location"
    return "$what ${relativeTime(s.timestamp)}$where"
}
