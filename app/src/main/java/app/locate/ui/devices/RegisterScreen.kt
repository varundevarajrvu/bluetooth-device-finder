package app.locate.ui.devices

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locate.data.db.BtType
import app.locate.data.db.DeviceCategory
import app.locate.ui.common.BackTopBar
import app.locate.ui.common.InfoCard
import app.locate.ui.common.safeStart

@Composable
fun RegisterScreen(onDone: () -> Unit, vm: RegisterViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Candidate?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBonded() }

    Scaffold(topBar = { BackTopBar("Add a device", onDone) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard(
                    "Only devices paired with this phone can be added, so Locate can't be used to follow other people's devices. " +
                        "Signal strength appears when a device is advertising nearby; many earbuds don't advertise while connected.",
                )
            }
            if (!state.hasPermission) item { Text("Locate needs the Nearby devices permission to list your devices.") }
            if (!state.bluetoothOn) item { Text("Bluetooth is off.") }
            item { Text("Paired devices", style = MaterialTheme.typography.titleMedium) }
            if (state.candidates.isEmpty()) item { Text("No paired devices left to add.") }
            items(state.candidates, key = { it.info.address }) { c ->
                Card(Modifier.fillMaxWidth().clickable { picked = c }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.info.name ?: c.info.address, style = MaterialTheme.typography.titleMedium)
                        Text("${typeLabel(c.info.type)} · ${c.live?.let { "${it.rssi} dBm" } ?: "not advertising"}")
                    }
                }
            }
            item {
                OutlinedButton(onClick = { context.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, Modifier.fillMaxWidth()) {
                    Text("Pair a new device in Bluetooth settings")
                }
            }
            if (state.unpairedNearby.isNotEmpty()) {
                item { Text("Nearby, not paired (${state.unpairedNearby.size})", style = MaterialTheme.typography.titleMedium) }
                items(state.unpairedNearby, key = { it.address }) { s ->
                    Text(
                        "${s.name} · ${s.rssi} dBm. Pair it first to add it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    picked?.let { c -> RegisterDialog(c, onDismiss = { picked = null }, onSave = { name, cat -> vm.register(c, name, cat, onDone) }) }
}

@Composable
private fun RegisterDialog(c: Candidate, onDismiss: () -> Unit, onSave: (String, DeviceCategory) -> Unit) {
    var nickname by remember { mutableStateOf(c.info.name.orEmpty()) }
    var category by remember { mutableStateOf(guessCategory(c.info.name)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${c.info.name ?: "device"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("Nickname") }, singleLine = true)
                Text("What is it?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceCategory.entries.forEach { cat ->
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(nickname, category) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun typeLabel(type: BtType) = when (type) {
    BtType.CLASSIC -> "Bluetooth Classic"
    BtType.LE -> "Bluetooth LE"
    BtType.DUAL -> "Classic + LE"
    BtType.UNKNOWN -> "Bluetooth"
}

private fun guessCategory(name: String?): DeviceCategory {
    val n = name?.lowercase() ?: return DeviceCategory.OTHER
    return when {
        listOf("bud", "pods", "airdopes", "tws", "earbud").any { it in n } -> DeviceCategory.EARBUDS
        listOf("headphone", "wh-", "rockerz").any { it in n } -> DeviceCategory.HEADPHONES
        listOf("speaker", "stone", "jbl", "boom").any { it in n } -> DeviceCategory.SPEAKER
        listOf("watch", "band", "fit").any { it in n } -> DeviceCategory.WATCH
        else -> DeviceCategory.OTHER
    }
}
