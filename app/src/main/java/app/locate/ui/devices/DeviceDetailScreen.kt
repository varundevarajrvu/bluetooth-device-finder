package app.locate.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locate.core.ble.FakeBleSource
import app.locate.data.db.Device
import app.locate.data.db.Sighting
import app.locate.data.db.SightingEvent
import app.locate.ui.common.BackTopBar
import app.locate.ui.common.InfoCard
import app.locate.ui.common.LOCATION_DISCLAIMER
import app.locate.ui.common.clockTime
import app.locate.ui.common.openMap
import app.locate.ui.common.relativeTime
import kotlin.math.roundToInt

@Composable
fun DeviceDetailScreen(onBack: () -> Unit, vm: DeviceDetailViewModel = hiltViewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastLocated by vm.lastLocated.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val d = device

    Scaffold(
        topBar = {
            BackTopBar(d?.nickname ?: "", onBack) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Remove") }
            }
        },
    ) { padding ->
        if (d == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("${d.category.label} · ${typeLabel(d.btType)}", style = MaterialTheme.typography.bodyMedium) }
            item { LastLocationCard(d, lastLocated) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Left-behind alerts", style = MaterialTheme.typography.titleMedium)
                        Text("Alert me if I seem to leave it behind", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = d.alertsEnabled, onCheckedChange = vm::setAlertsEnabled)
                }
            }
            vm.fake?.let { fake -> item { FakeControls(fake, d.address) } }
            item { Text("History", style = MaterialTheme.typography.titleMedium) }
            if (recent.isEmpty()) item { Text("Nothing yet. Connect the device, or wait for a scan to see it.") }
            items(recent, key = { it.id }) { s ->
                SightingRow(s)
                HorizontalDivider()
            }
        }
    }

    if (renaming && d != null) {
        var name by remember { mutableStateOf(d.nickname) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting && d != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Remove ${d.nickname}?") },
            text = { Text("Its history on this phone is deleted too. It stays paired in Bluetooth settings.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(onBack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LastLocationCard(device: Device, s: Sighting?) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last known place", style = MaterialTheme.typography.titleMedium)
            val lat = s?.lat
            val lng = s?.lng
            if (s == null || lat == null || lng == null) {
                Text("No location recorded yet.")
            } else {
                Text(
                    "Your phone was here when it ${verb(s.event)} ${device.nickname}, ${relativeTime(s.timestamp)} " +
                        "(±${s.accuracyM?.roundToInt() ?: "?"} m).",
                )
                Button(onClick = { context.openMap(lat, lng, device.nickname) }) { Text("Open in maps") }
            }
            InfoCard(LOCATION_DISCLAIMER)
        }
    }
}

@Composable
private fun SightingRow(s: Sighting) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            when (s.event) {
                SightingEvent.CONNECTED -> "Connected"
                SightingEvent.DISCONNECTED -> "Disconnected"
                SightingEvent.SEEN -> "Seen" + (s.rssi?.let { " · $it dBm" } ?: "")
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        val where = if (s.lat != null && s.lng != null) {
            "%.5f, %.5f ±%d m".format(s.lat, s.lng, s.accuracyM?.roundToInt() ?: 0) +
                (s.fixTimestamp?.takeIf { s.timestamp - it > 60_000 }?.let { " (fix from ${relativeTime(it)})" } ?: "")
        } else {
            "no location"
        }
        Text("${clockTime(s.timestamp)} · $where", style = MaterialTheme.typography.bodySmall)
    }
}

/** Simulate buttons for emulator builds (-Plocate.fakeBle=true). */
@Composable
private fun FakeControls(fake: FakeBleSource, address: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fake Bluetooth", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fake.simulateConnect(address) }) { Text("Connect") }
                OutlinedButton(onClick = { fake.simulateDisconnect(address) }) { Text("Disconnect") }
                OutlinedButton(onClick = { fake.setAdvertising(address, -60) }) { Text("Advertise") }
                OutlinedButton(onClick = { fake.setAdvertising(address, null) }) { Text("Go silent") }
                OutlinedButton(onClick = { fake.simulateBluetoothOff() }) { Text("BT off") }
                OutlinedButton(onClick = { fake.simulateBluetoothOn() }) { Text("BT on") }
            }
            Text(
                "To trigger an alert on an emulator: Connect, wait 30 s, Disconnect, then move the emulator's location " +
                    "more than 150 m (Extended controls → Location → Routes).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun verb(e: SightingEvent) = when (e) {
    SightingEvent.CONNECTED -> "connected to"
    SightingEvent.DISCONNECTED -> "lost"
    SightingEvent.SEEN -> "last saw"
}
